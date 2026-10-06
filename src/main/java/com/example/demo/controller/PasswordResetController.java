package com.example.demo.controller;

import com.example.demo.service.TemporaryPasswordService;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * パスワードを忘れた人の入力画面を表示し、仮パスワードの発行を依頼する。
 * メールアドレスの形式を確認した後、実際の発行と送信はTemporaryPasswordServiceへ渡す。
 */
@Controller
public class PasswordResetController {
    private final TemporaryPasswordService temporaryPasswords;

    public PasswordResetController(TemporaryPasswordService temporaryPasswords) {
        this.temporaryPasswords = temporaryPasswords;
    }

    /**
     * 仮パスワードの送信先を入力する画面を表示する。
     */
    @GetMapping("/forgot-password")
    public String form() {
        return "auth/forgot-password";
    }

    /**
     * メールアドレスの形式を確認して発行を依頼し、送信結果を画面へ渡す。
     * 登録済みかどうかは表示に出さず、メール送信が失敗した場合は再試行の案内を出す。
     */
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
