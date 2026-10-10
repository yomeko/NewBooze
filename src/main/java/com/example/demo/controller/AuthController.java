package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * ログイン画面の表示と、アカウントの新規登録を担当する。
 * 登録したユーザーはそのままログイン状態になり、好み診断へ進む。
 * 通常のログイン時のパスワード確認は、Spring Security（ログインを管理する仕組み）が行う。
 */
@Controller
public class AuthController {

    // 登録後のログイン情報をセッションへ保存し、次のページでも本人として扱えるようにする。
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
     * 保存後は自動ログインさせ、redirectでブラウザを好み診断のURLへ移動させる。
     */
    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form,
                          BindingResult bindingResult,
                          HttpServletRequest request,
                          HttpServletResponse response) {

        if (bindingResult.hasErrors()) {
            return "auth/signup"; // 入力エラー時は同じ画面に戻す
        }
        if (form.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            bindingResult.rejectValue("password", "tooLong", "パスワードが長すぎます。短くしてください");
            return "auth/signup";
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
        User saved = userRepository.save(user);

        autoLogin(saved, request, response);

        return "redirect:/diagnosis";
    }

    /**
     * 登録が成功したユーザーをログイン状態にする。
     * 登録処理で保存したUserを使って認証情報を作り、今回の処理とHTTPセッションに保存する。
     * HTTPセッションは、次のページに移動してもログイン状態を覚えておくための仕組み。
     */
    private void autoLogin(User user, HttpServletRequest request, HttpServletResponse response) {
        // 匿名状態で発行済みのセッションIDを、登録後の認証には引き継がない。
        if (request.getSession(false) != null) request.changeSessionId();
        CustomUserDetails userDetails = new CustomUserDetails(user);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // ここでHTTPセッションに保存しないと、次のリクエスト（リダイレクト先の/diagnosis表示等）で
        // 認証情報が失われ、未ログイン扱いに戻ってしまう。
        securityContextRepository.saveContext(context, request, response);
    }
}
