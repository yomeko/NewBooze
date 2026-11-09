package com.example.demo.controller;

import com.example.demo.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.UUID;
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
    private static final String RESET_FORMS_SESSION_ATTRIBUTE = "passwordResetForms";
    private static final int MAX_RESET_FORMS = 8;
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
    public String resetForm(@RequestParam(required = false) String token,
            @RequestParam(required = false) String formId, HttpServletRequest request,
            HttpServletResponse response, Model model, RedirectAttributes redirect) {
        preventStorage(response);
        if (token != null) {
            // リンクの秘密をURLから除き、このセッション専用のフォームIDだけを渡す。
            if (token.matches("[A-Za-z0-9_-]{43}")) {
                redirect.addAttribute("formId", resetForms(request, true).remember(token));
            }
            return "redirect:/reset-password";
        }
        ResetForms forms = resetForms(request, false);
        if (forms == null || forms.tokenFor(formId) == null) return renderForm(model, null, INVALID_LINK);
        return renderForm(model, formId, null);
    }

    @PostMapping("/reset-password")
    public String reset(@RequestParam(required = false) String formId, @RequestParam(defaultValue = "") String newPassword,
            @RequestParam(defaultValue = "") String confirmPassword, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        preventStorage(response);
        ResetForms forms = resetForms(request, false);
        String token = forms == null ? null : forms.tokenFor(formId);
        if (token == null) return renderForm(model, null, INVALID_LINK);
        if (!newPassword.equals(confirmPassword)) {
            return renderForm(model, formId, "確認用パスワードが一致しません");
        }
        boolean changed;
        try {
            // 期限・再利用はServiceがDBの行ロック下で判定する。
            changed = passwords.reset(token, newPassword);
        } catch (IllegalArgumentException exception) {
            return renderForm(model, formId, exception.getMessage());
        }
        forms.remove(formId);
        return changed ? "redirect:/login?passwordReset" : renderForm(model, null, INVALID_LINK);
    }

    private String renderForm(Model model, String formId, String error) {
        model.addAttribute("canReset", formId != null);
        model.addAttribute("formId", formId);
        if (error != null) model.addAttribute("error", error);
        return "auth/reset-password";
    }

    // 最初のリンクを複数タブで同時に開いても、セッションのフォーム一覧を上書きしない。
    private synchronized ResetForms resetForms(HttpServletRequest request, boolean create) {
        var session = request.getSession(create);
        if (session == null) return null;
        if (session.getAttribute(RESET_FORMS_SESSION_ATTRIBUTE) instanceof ResetForms forms) return forms;
        if (!create) return null;
        ResetForms forms = new ResetForms();
        session.setAttribute(RESET_FORMS_SESSION_ATTRIBUTE, forms);
        return forms;
    }

    private static final class ResetForms implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private final LinkedHashMap<String, String> tokens = new LinkedHashMap<>();

        synchronized String remember(String token) {
            if (tokens.size() >= MAX_RESET_FORMS) tokens.pollFirstEntry();
            String formId = UUID.randomUUID().toString();
            tokens.put(formId, token);
            return formId;
        }

        synchronized String tokenFor(String formId) {
            return tokens.get(formId);
        }

        synchronized void remove(String formId) {
            tokens.remove(formId);
        }
    }

    private void preventStorage(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
