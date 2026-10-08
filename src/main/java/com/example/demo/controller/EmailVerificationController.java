package com.example.demo.controller;

import com.example.demo.service.EmailVerificationService;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class EmailVerificationController {
    private final EmailVerificationService verification;

    public EmailVerificationController(EmailVerificationService verification) {
        this.verification = verification;
    }

    // メール検査ソフトがリンクを自動で開いても認証が完了しないよう、確認はPOSTで行う。
    @GetMapping("/verify-email")
    public String page(@RequestParam(required = false) String token, Model model) {
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) model.addAttribute("token", token);
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String verify(@RequestParam String token, Model model) {
        if (verification.verify(token)) model.addAttribute("verified", true);
        else model.addAttribute("error", "リンクが無効、期限切れ、または認証済みです。未認証の場合はメールを再送してください。");
        return "auth/verify-email";
    }

    @PostMapping("/verify-email/resend")
    public String resend(@RequestParam String email, Model model) {
        if (email.length() > 255 || !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            model.addAttribute("error", "正しいメールアドレスを入力してください。");
            return "auth/verify-email";
        }
        try {
            verification.resend(email);
            model.addAttribute("sent", true);
        } catch (MailException exception) {
            model.addAttribute("error", "メールを送信できませんでした。時間をおいて再度お試しください。");
        }
        return "auth/verify-email";
    }
}
