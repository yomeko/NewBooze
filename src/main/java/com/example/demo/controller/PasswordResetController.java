package com.example.demo.controller;

import com.example.demo.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 再設定要求の受付と、メールリンクを使った新しいパスワードの確定を担当する。 */
@Controller
public class PasswordResetController {
    static final String RESET_TOKEN_SESSION_ATTRIBUTE = "passwordResetToken";
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);
    private static final String INVALID_LINK = "リンクが無効、期限切れ、または使用済みです。パスワード再設定をもう一度依頼してください。";
    private final PasswordResetService passwords;

    public PasswordResetController(PasswordResetService passwords) {
        this.passwords = passwords;
    }

    @GetMapping("/forgot-password")
    public String form(HttpServletResponse response) {
        preventStorage(response);
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String send(@RequestParam(defaultValue = "") String email, Model model,
            RedirectAttributes redirect, HttpServletResponse response) {
        preventStorage(response);
        String normalized = email.trim();
        if (normalized.length() > 255 || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            model.addAttribute("error", "正しいメールアドレスを入力してください");
            return "auth/forgot-password";
        }
        try {
            passwords.requestFor(normalized);
        } catch (MailException exception) {
            // SMTP障害でも登録の有無を推測できる応答にしない。宛先・トークン・例外本文は記録しない。
            logger.warn("パスワード再設定メールの送信に失敗しました");
        }
        redirect.addFlashAttribute("sent", true);
        return "redirect:/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam(required = false) String token, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        preventStorage(response);
        if (token != null) {
            // 初回は表示を返さず、リンクの秘密をURLから除いてからフォームを表示する。
            if (token.matches("[A-Za-z0-9_-]{43}")) {
                request.getSession().setAttribute(RESET_TOKEN_SESSION_ATTRIBUTE, token);
            } else {
                var session = request.getSession(false);
                if (session != null) session.removeAttribute(RESET_TOKEN_SESSION_ATTRIBUTE);
            }
            return "redirect:/reset-password";
        }
        var session = request.getSession(false);
        boolean canReset = session != null && session.getAttribute(RESET_TOKEN_SESSION_ATTRIBUTE) instanceof String;
        model.addAttribute("canReset", canReset);
        if (!canReset) model.addAttribute("error", INVALID_LINK);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String reset(@RequestParam(defaultValue = "") String newPassword,
            @RequestParam(defaultValue = "") String confirmPassword, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        preventStorage(response);
        var session = request.getSession(false);
        String token = session != null && session.getAttribute(RESET_TOKEN_SESSION_ATTRIBUTE) instanceof String value
                ? value : null;
        if (token == null) return invalidLink(model);
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("canReset", true);
            model.addAttribute("error", "確認用パスワードが一致しません");
            return "auth/reset-password";
        }
        try {
            if (!passwords.reset(token, newPassword)) {
                session.removeAttribute(RESET_TOKEN_SESSION_ATTRIBUTE);
                return invalidLink(model);
            }
        } catch (IllegalArgumentException exception) {
            model.addAttribute("canReset", true);
            model.addAttribute("error", exception.getMessage());
            return "auth/reset-password";
        }
        session.removeAttribute(RESET_TOKEN_SESSION_ATTRIBUTE);
        return "redirect:/login?passwordReset";
    }

    private String invalidLink(Model model) {
        model.addAttribute("canReset", false);
        model.addAttribute("error", INVALID_LINK);
        return "auth/reset-password";
    }

    private void preventStorage(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
