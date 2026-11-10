package com.example.demo.controller;

import com.example.demo.dto.TastePresentation;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.DiagnosisService;
import com.example.demo.service.SakeInteractionService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 好み診断の質問画面、結果画面、本人の診断履歴を担当する。
 * 質問の回答をDiagnosisServiceへ渡し、好みの特徴とおすすめ銘柄をHTMLへ渡す。
 * 誰でも診断できるが、回答と点数をデータベースへ保存するのはログイン中だけ。
 */
@Controller
public class DiagnosisController {

    private static final int HISTORY_PAGE_SIZE = 10;
    private static final int MAX_RECENT_RESULTS = 8;
    private static final String RESULTS_SESSION_ATTRIBUTE = "diagnosisResults";
    private final DiagnosisService diagnosisService;
    private final SakeInteractionService interactions;

    public DiagnosisController(DiagnosisService diagnosisService, SakeInteractionService interactions) {
        this.diagnosisService = diagnosisService;
        this.interactions = interactions;
    }

    /**
     * 質問文と選択肢の一覧を準備し、diagnosis.htmlへ渡す。
     */
    @GetMapping("/diagnosis")
    public String diagnosis(Model model) {
        var questions = diagnosisService.questions();
        model.addAttribute("questions", questions);
        model.addAttribute("questionCount", questions.stream().filter(q -> q.conditionChoiceId() == null).count()
                + (questions.stream().anyMatch(q -> q.conditionChoiceId() != null) ? 2 : 0));
        return "diagnosis";
    }

    @GetMapping("/mypage/diagnosis-history")
    public String history(@RequestParam(name = "page", defaultValue = "0") String pageNumber,
                          @AuthenticationPrincipal CustomUserDetails principal, Model model) {
        // 古いページへのリンクや不正なページ番号からも、最初のページへ戻れるようにする。
        int page;
        try {
            page = Integer.parseInt(pageNumber.strip());
        } catch (NumberFormatException ex) {
            return "redirect:/mypage/diagnosis-history";
        }
        if (page < 0 || page > Integer.MAX_VALUE / HISTORY_PAGE_SIZE) return "redirect:/mypage/diagnosis-history";

        // /mypage/** はログイン必須。表示対象はリクエストの値ではなくログイン中の本人に固定する。
        var history = diagnosisService.history(principal.getUserId(), PageRequest.of(page, HISTORY_PAGE_SIZE));
        if (page > 0 && history.isEmpty()) return "redirect:/mypage/diagnosis-history";
        model.addAttribute("history", history);
        return "diagnosis-history";
    }

    /**
     * 選択肢IDを集計し、ログイン中なら診断履歴と好みの点数を保存する。
     * 表示用の結果はセッションに置き、再読み込みでフォームを再送しないようGETへ移動する。
     * 集計結果が空なら、回答を選び直せるよう質問画面へ戻す。
     */
    @PostMapping("/diagnosis/result")
    public String submit(@RequestParam(name = "choice", required = false) List<Long> choices,
                         @AuthenticationPrincipal CustomUserDetails principal, HttpServletRequest request,
                         Model model, RedirectAttributes redirect) {
        if (!diagnosisService.validAnswers(choices)) {
            model.addAttribute("diagnosisError", "回答を確認できませんでした。最初から診断してください。");
            return diagnosis(model);
        }
        Long loginUserId = principal != null ? principal.getUserId() : null;
        Map<String, Integer> preferences = diagnosisService.aggregateAndPersist(choices, loginUserId);

        if (preferences.isEmpty()) return "redirect:/diagnosis";

        var result = new DiagnosisResult(loginUserId, Map.copyOf(preferences),
                List.copyOf(diagnosisService.answerSummaries(choices)));
        redirect.addAttribute("resultId", recentResults(request, true).remember(result));
        return "redirect:/diagnosis/result";
    }

    @GetMapping("/diagnosis/result")
    public String result(@RequestParam(required = false) String resultId,
                         @AuthenticationPrincipal CustomUserDetails principal, HttpServletRequest request,
                         Model model, RedirectAttributes redirect) {
        Long loginUserId = principal != null ? principal.getUserId() : null;
        DiagnosisResult result = findResult(request, resultId, loginUserId);
        if (result == null) return resultUnavailable(redirect);

        var preferences = result.preferences();
        var recommendations = diagnosisService.recommend(preferences);
        model.addAttribute("preferences", preferences);
        model.addAttribute("quadrant", TastePresentation.quadrant(preferences));
        model.addAttribute("tendencies", TastePresentation.tendencies(preferences));
        model.addAttribute("answers", result.answers());
        model.addAttribute("recommendations", recommendations);
        model.addAttribute("saved", loginUserId != null);
        model.addAttribute("resultId", resultId);
        model.addAttribute("favoriteSakeIds", loginUserId == null ? Set.of() : interactions.favoriteSakeIds(loginUserId,
                recommendations.stream().map(item -> item.sake().id()).toList()));
        return "diagnosis-result";
    }

    @PostMapping("/mypage/diagnosis/favorite")
    public String favorite(@RequestParam String resultId, @RequestParam long sakeId, @RequestParam boolean selected,
                           @AuthenticationPrincipal CustomUserDetails principal, HttpServletRequest request,
                           RedirectAttributes redirect) {
        long userId = principal.getUserId();
        if (findResult(request, resultId, userId) == null) return resultUnavailable(redirect);

        redirect.addAttribute("resultId", resultId);
        try {
            interactions.favorite(userId, sakeId, selected);
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() != HttpStatus.NOT_FOUND) throw ex;
            redirect.addFlashAttribute("favoriteError", "この銘柄は見つかりません。おすすめを選び直してください。");
            return "redirect:/diagnosis/result#diagnosis-recommendations";
        }
        return "redirect:/diagnosis/result#diagnosis-favorite-" + sakeId;
    }

    private DiagnosisResult findResult(HttpServletRequest request, String resultId, Long userId) {
        DiagnosisResults results = recentResults(request, false);
        DiagnosisResult result = results == null ? null : results.find(resultId);
        return result != null && Objects.equals(result.userId(), userId) ? result : null;
    }

    private String resultUnavailable(RedirectAttributes redirect) {
        redirect.addFlashAttribute("diagnosisError", "診断結果を表示できませんでした。もう一度診断してください。");
        return "redirect:/diagnosis";
    }

    // 同じセッションで複数のタブから送信しても、結果の一覧を上書きしない。
    private synchronized DiagnosisResults recentResults(HttpServletRequest request, boolean create) {
        var session = request.getSession(create);
        if (session == null) return null;
        if (session.getAttribute(RESULTS_SESSION_ATTRIBUTE) instanceof DiagnosisResults results) return results;
        if (!create) return null;
        var results = new DiagnosisResults();
        session.setAttribute(RESULTS_SESSION_ATTRIBUTE, results);
        return results;
    }

    private record DiagnosisResult(Long userId, Map<String, Integer> preferences,
                                   List<DiagnosisService.AnswerSummary> answers) implements Serializable {}

    private static final class DiagnosisResults implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private final LinkedHashMap<String, DiagnosisResult> results = new LinkedHashMap<>();

        synchronized String remember(DiagnosisResult result) {
            if (results.size() >= MAX_RECENT_RESULTS) results.pollFirstEntry();
            String resultId = UUID.randomUUID().toString();
            results.put(resultId, result);
            return resultId;
        }

        synchronized DiagnosisResult find(String resultId) {
            return results.get(resultId);
        }
    }
}
