package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * S07: ログイン画面表示。
     * フォームのPOST先(/login)自体はSecurityConfigでSpring Securityに委譲済みのため、
     * このControllerでは画面表示のみを担当する。
     */
    @GetMapping("/login")
    public String loginForm() {
        return "auth/login";
    }

    /** S07: 新規登録画面表示 */
    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("signupForm", new SignupForm());
        return "auth/signup";
    }

    /** S07: 新規登録処理 */
    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form,
                          BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            return "auth/signup"; // 入力エラー時は同じ画面に戻す
        }

        // メールアドレスの重複チェック(usersテーブルのUNIQUE制約と二重にチェック)
        if (userRepository.findByEmail(form.getEmail()).isPresent()) {
            bindingResult.rejectValue("email", "duplicate", "このメールアドレスは既に登録されています");
            return "auth/signup";
        }

        User user = new User();
        user.setName(form.getName());
        user.setEmail(form.getEmail());
        // 平文パスワードは保存せず、必ずハッシュ化してから保存する
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        userRepository.save(user);

        return "redirect:/login?registered";
    }
}
