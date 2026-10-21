package com.example.demo.controller;

import com.example.demo.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** メール内リンクの確認画面、認証の確定、確認メールの再送を担当する。 */
@Controller
public class EmailVerificationController {
    private final EmailVerificationService verification;

    public EmailVerificationController(EmailVerificationService verification) {
        this.verification = verification;
    }

    // メール検査ソフトがリンクを自動で開いても認証が完了しないよう、確認はPOSTで行う。
    @GetMapping("/verify-email")
    public String page(@RequestParam(required = false) String token, Model model, HttpServletResponse response) {
        preventStorage(response);
        // ここでは形式だけを確認し、有効期限・使用済みの判定は確定時にServiceで行う。
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) model.addAttribute("token", token);
        return "auth/verify-email";
    }

    // CSRF保護されたフォームから確定し、成功後も自動ログインはしない。
    @PostMapping("/verify-email")
    public String verify(@RequestParam String token, Model model, HttpServletResponse response) {
        preventStorage(response);
        if (verification.verify(token)) model.addAttribute("verified", true);
        else model.addAttribute("error", "リンクが無効、期限切れ、または認証済みです。未認証の場合はメールを再送してください。");
        return "auth/verify-email";
    }

    @PostMapping("/verify-email/resend")
    public String resend(@RequestParam(defaultValue = "") String email,
            RedirectAttributes redirect, HttpServletResponse response) {
        preventStorage(response);
        String normalized = email.trim();
        // 入力はURLに載せず一度限りのFlash属性で戻す。過大な値は画面へ反映しない。
        redirect.addFlashAttribute("resendEmail", normalized.length() <= 255 ? normalized : "");
        if (normalized.length() > 255 || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            redirect.addFlashAttribute("error", "正しいメールアドレスを入力してください。");
            return "redirect:/verify-email";
        }
        try {
            verification.resend(normalized);
            // 未登録・認証済み・60秒未満も共通の案内とし、登録の有無を公開しない。
            redirect.addFlashAttribute("sent", true);
        } catch (MailException exception) {
            redirect.addFlashAttribute("error", "メールを送信できませんでした。時間をおいて再度お試しください。");
        }
        // POST後はGETへ移動し、ページ更新による意図しない再送・旧リンク失効を防ぐ。
        return "redirect:/verify-email";
    }
    // トークンを含む画面のキャッシュ保存と、リンク先へのReferer送信を抑える。
    private void preventStorage(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
