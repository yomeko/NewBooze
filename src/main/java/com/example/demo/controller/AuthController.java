package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

/** 登録時は仮登録し、メールのリンクで認証後にログインできるようにする。 */
@Controller
public class AuthController {
    private final UserRepository users;
    private final EmailVerificationService verification;

    public AuthController(UserRepository users, EmailVerificationService verification) {
        this.users = users;
        this.verification = verification;
    }

    @GetMapping("/login")
    public String loginForm() { return "auth/login"; }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("signupForm", new SignupForm());
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form, BindingResult errors) {
        if (errors.hasErrors()) return "auth/signup";
        if (users.existsByEmail(form.getEmail().trim())) {
            errors.rejectValue("email", "duplicate", "このメールアドレスは既に登録されています。未認証の場合は確認メールを再送してください。");
            return "auth/signup";
        }
        try {
            verification.register(form);
        } catch (MailException e) {
            errors.reject("mail", "確認メールを送信できませんでした。時間をおいて再度お試しください。");
            return "auth/signup";
        } catch (DataIntegrityViolationException e) {
            errors.rejectValue("email", "duplicate", "登録できませんでした。メールアドレスをご確認ください。");
            return "auth/signup";
        }
        return "redirect:/resend-verification?sent";
    }

    @GetMapping("/verify-email")
    public String verify(@RequestParam(required = false) String token) {
        return verification.verify(token) ? "redirect:/login?registered" : "redirect:/resend-verification?invalid";
    }

    @GetMapping("/resend-verification")
    public String resendForm() { return "auth/verification"; }

    @PostMapping("/resend-verification")
    public String resend(@RequestParam(defaultValue = "") String email) {
        if (email.length() > 255 || email.isBlank()) return "redirect:/resend-verification?invalidEmail";
        try {
            verification.resend(email);
        } catch (MailException e) {
            return "redirect:/resend-verification?mailError";
        }
        return "redirect:/resend-verification?sent";
    }
}
