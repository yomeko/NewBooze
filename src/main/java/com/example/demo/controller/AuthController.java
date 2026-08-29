package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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

@Controller
public class AuthController {

    // 新規登録直後の自動ログイン処理で、認証情報をHTTPセッションへ保存するために使用する。
    // (Spring Security 5.7以降、SecurityContextHolderへの設定だけでは次のリクエストに
    //  認証状態が引き継がれない仕様のため、SecurityContextRepository経由で明示的に保存する必要がある)
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

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

    /**
     * S07: 新規登録処理。
     * 登録完了後はログイン画面を経由させず、そのまま自動ログインさせたうえで
     * 好み診断(/diagnosis)へ誘導する（好み診断は新規登録直後のみ自動表示する）。
     */
    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form,
                          BindingResult bindingResult,
                          HttpServletRequest request,
                          HttpServletResponse response) {

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
        User saved = userRepository.save(user);

        autoLogin(saved, request, response);

        return "redirect:/diagnosis";
    }

    /**
     * 新規登録直後のユーザーを、パスワード再入力なしでログイン状態にする。
     * 通常のログインフローと異なりCustomUserDetailsServiceは経由せず、
     * 登録処理で取得済みのUserからCustomUserDetailsを直接生成する。
     * （直前にパスワードのハッシュ照合が既に済んでいる＝本人性は保証されているため、
     * 　ここで再度パスワード照合を行う必要はない）
     */
    private void autoLogin(User user, HttpServletRequest request, HttpServletResponse response) {
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
