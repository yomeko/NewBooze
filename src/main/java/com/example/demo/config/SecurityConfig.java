package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * パスワードハッシュ化にBCryptを採用。
     * ソルト生成・照合処理をライブラリ側が自動で行うため、
     * 自前実装に比べて安全性・実装コストの両面で有利。
     * (不確かですが、Spring Security公式でも標準的な選択肢として案内されています)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // 未ログインでも閲覧可能な画面(S01〜S05, ログイン/新規登録, 静的リソース)
                .requestMatchers(
                    "/", "/search", "/sake/**",
                    "/diagnosis/**",
                    "/login", "/signup",
                    "/css/**", "/js/**", "/images/**"
                ).permitAll()
                // お気に入り(S06)・マイページ(S08)はログイン必須
                .requestMatchers("/favorites/**", "/mypage/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")           // 独自のログイン画面を使用
                .loginProcessingUrl("/login")  // フォームのPOST先。Spring Securityが自動で処理する
                // ログイン後は好み診断（/diagnosis）へ誘導する。
                // 第2引数 false は「常にこのURLへ飛ばすわけではない」の意味で、
                // お気に入り(/favorites)等の認証必須ページへ直接アクセスして
                // ログイン画面に飛ばされたケースでは、Spring Securityが記憶している
                // 元のリクエスト(SavedRequest)を優先して復元する。
                // trueにすると常に/diagnosisへ強制遷移してしまうため注意。
                .defaultSuccessUrl("/diagnosis", false)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            );
            // CSRF保護はデフォルトで有効のままにしている。
            // Thymeleafの<form>タグ(th:action使用時)は自動でCSRFトークンを埋め込むため、
            // 特別な対応は不要。

        return http.build();
    }
}
