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
 * S02（診断画面）／S03（診断結果・推薦画面）を担当するController。
 * 外部設計書 3.1 の通り、診断自体は未ログインでも可能（認証不要）。
 * 結果の保存（diagnosis_sessions等）はログイン時のみ行う。
 */
@Controller
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    @GetMapping("/diagnosis")
    public String diagnosis(Model model) {
        model.addAttribute("questions", diagnosisService.questions());
        return "diagnosis";
    }

    @PostMapping("/diagnosis/result")
    public String result(@RequestParam(name = "choice", required = false) List<Long> choices,
                          // 未ログイン時、principalはnullになる（SecurityConfigで/diagnosis/**はpermitAllのため）
                          @AuthenticationPrincipal CustomUserDetails principal,
                          Model model) {

        Long loginUserId = principal != null ? principal.getUserId() : null;
        Map<String, Integer> preferences = diagnosisService.aggregateAndPersist(choices, loginUserId);

        if (preferences.isEmpty()) return "redirect:/diagnosis";

        model.addAttribute("preferences", preferences);
        model.addAttribute("recommendations", diagnosisService.recommend(preferences));
        // 画面側で「診断結果を保存しました」等の案内を出し分けたい場合に使えるよう、
        // ログイン状態も合わせて渡しておく
        model.addAttribute("saved", loginUserId != null);
        return "diagnosis-result";
    }
}
