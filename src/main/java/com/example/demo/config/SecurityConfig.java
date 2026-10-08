package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.example.demo.security.CustomUserDetails;

/**
 * どのページを誰が使えるか、ログイン・ログアウトをどう処理するかを決める。
 * Spring Securityは、この設定に従って画面処理より先にログイン状態や権限を確認する。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * パスワードをBCryptでハッシュ化し、ログイン時に照合する道具を用意する。
     * ハッシュ化は、元の文字列をそのまま保存せず、照合用の値へ変換する処理。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * URLごとのアクセス権限と、ログイン・ログアウトの動きを組み立てる。
     * 上から順にURLを照合し、最初に一致した権限ルールを使う。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // 管理者専用のURLを先に確認する。hasRole("ADMIN")はROLE_ADMINという権限を要求する。
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                // 未ログインでも閲覧可能な画面(S01〜S05, ログイン/新規登録, 静的リソース)
                .requestMatchers(
                    "/", "/search", "/sake/**",
                    "/brewery-map", "/contact", "/categories", "/tags",
                    "/about", "/privacy", "/external-transmission",
                    "/diagnosis/**",
                    "/login", "/signup", "/forgot-password", "/verify-email", "/verify-email/resend",
                    "/css/**", "/js/**", "/images/**"
                ).permitAll()
                // お気に入り(S06)・マイページ(S08)はログイン必須
                .requestMatchers("/favorites/**", "/mypage/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")           // 独自のログイン画面を使用
                .loginProcessingUrl("/login")  // フォームのPOST先。Spring Securityが自動で処理する
                // ログイン成功後の移動先をここで決める。
                // 仮パスワードなら変更案内のあるマイページ、管理者なら管理画面、一般ユーザーならホーム。
                // 新規登録後はメール認証を完了してから通常のログインを行う。
                .successHandler((request, response, authentication) -> {
                    CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
                    response.sendRedirect(user.isTemporaryPassword() ? "/mypage?passwordChangeRequired" : (user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? "/admin" : "/"));
                })
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            );
            // CSRF保護は、別のサイトから本人の意図しない保存操作をされるのを防ぐ仕組み。
            // th:actionを使うPOSTフォームには、送信元を確認するためのトークンが自動で入る。

        return http.build();
    }
}
