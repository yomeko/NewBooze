package com.example.demo.controller;

import com.example.demo.service.TemporaryPasswordService;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PasswordResetController {
    private final TemporaryPasswordService temporaryPasswords;

    public PasswordResetController(TemporaryPasswordService temporaryPasswords) {
        this.temporaryPasswords = temporaryPasswords;
    }

    @GetMapping("/forgot-password")
    public String form() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String send(@RequestParam String email, Model model) {
        if (email == null || !email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
                || email.trim().length() > 255) {
            model.addAttribute("error", "正しいメールアドレスを入力してください");
            return "auth/forgot-password";
        }
        try {
            temporaryPasswords.issueFor(email);
            // ユーザー列挙を防ぐため、未登録時も同じ表示にする。
            model.addAttribute("sent", true);
        } catch (MailException exception) {
            model.addAttribute("error", "メールを送信できませんでした。時間をおいてもう一度お試しください");
        }
        return "auth/forgot-password";
    }
}
