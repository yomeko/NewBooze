package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.EmailVerificationService;
import org.springframework.mail.MailException;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * ログイン画面の表示と、アカウントの新規登録を担当する。
 * 登録したユーザーへ認証メールを送り、確認が済むまでログインを制限する。
 * 通常のログイン時のパスワード確認は、Spring Security（ログインを管理する仕組み）が行う。
 */
@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final EmailVerificationService verification;

    public AuthController(UserRepository userRepository, EmailVerificationService verification) {
        this.userRepository = userRepository;
        this.verification = verification;
    }

    /**
     * ログイン画面を表示する。returnする文字列はtemplates以下のHTML名。
     * フォームから届くPOST /loginのパスワード確認はSecurityConfigの設定に従って処理される。
     */
    @GetMapping("/login")
    public String loginForm() {
        return "auth/login";
    }

    /**
     * 空のSignupFormを用意して新規登録画面へ渡す。
     */
    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("signupForm", new SignupForm());
        return "auth/signup";
    }

    /**
     * 入力とメールアドレスの重複を確認し、ユーザーを保存する。
     * {@code @Valid}が入力ルールを確認し、bindingResultに問題の内容が入る。
     * 保存後は認証メールの確認案内へ移動する。
     */
    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form,
                          BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            return "auth/signup"; // 入力エラー時は同じ画面に戻す
        }

        // メールアドレスの重複チェック(usersテーブルのUNIQUE制約と二重にチェック)
        if (userRepository.findByEmail(form.getEmail().trim()).isPresent()) {
            bindingResult.rejectValue("email", "duplicate", "このメールアドレスは既に登録されています");
            return "auth/signup";
        }

        try {
            verification.register(form);
        } catch (MailException exception) {
            bindingResult.reject("mail", "認証メールを送信できませんでした。時間をおいて再度登録してください。");
            return "auth/signup";
        } catch (DataIntegrityViolationException exception) {
            bindingResult.rejectValue("email", "duplicate", "このメールアドレスは既に登録されています");
            return "auth/signup";
        }
        return "redirect:/verify-email";
    }

}
