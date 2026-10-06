package com.example.demo.controller;

import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.DiagnosisService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 好み診断の質問画面と結果画面を担当する。
 * 質問の回答をDiagnosisServiceへ渡し、好みの特徴とおすすめ銘柄をHTMLへ渡す。
 * 誰でも診断できるが、回答と点数をデータベースへ保存するのはログイン中だけ。
 */
@Controller
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    /**
     * 質問文と選択肢の一覧を準備し、diagnosis.htmlへ渡す。
     */
    @GetMapping("/diagnosis")
    public String diagnosis(Model model) {
        model.addAttribute("questions", diagnosisService.questions());
        return "diagnosis";
    }

    /**
     * フォームから届いた選択肢IDの一覧を、好みの点数とおすすめ銘柄に変換する。
     * 未ログインならユーザーIDをnullにして、保存せず結果だけを表示する。
     * 集計結果が空なら、回答を選び直せるよう質問画面へ戻す。
     */
    @PostMapping("/diagnosis/result")
    public String result(@RequestParam(name = "choice", required = false) List<Long> choices,
                          // 未ログイン時、principalはnullになる（SecurityConfigで/diagnosis/**はpermitAllのため）
                          @AuthenticationPrincipal CustomUserDetails principal,
                          Model model) {

        Long loginUserId = principal != null ? principal.getUserId() : null;
        Map<String, Integer> preferences = diagnosisService.aggregateAndPersist(choices, loginUserId);

        if (preferences.isEmpty()) return "redirect:/diagnosis";

        model.addAttribute("preferences", preferences);
        model.addAttribute("tendencies", com.example.demo.dto.TastePresentation.tendencies(preferences));
        model.addAttribute("answers", diagnosisService.answerSummaries(choices));
        model.addAttribute("recommendations", diagnosisService.recommend(preferences));
        // 画面側で「診断結果を保存しました」等の案内を出し分けたい場合に使えるよう、
        // ログイン状態も合わせて渡しておく
        model.addAttribute("saved", loginUserId != null);
        return "diagnosis-result";
    }
}
