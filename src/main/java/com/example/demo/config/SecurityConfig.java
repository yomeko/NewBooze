package com.example.demo.config;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.security.AuthenticationRateLimiter;
import com.example.demo.security.AuthenticationRateLimitFilter;
import com.example.demo.security.AccountSessionValidationFilter;
import com.example.demo.repository.UserRepository;
import com.example.demo.validation.Utf8InputLength;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * どのページを誰が使えるか、ログイン・ログアウトをどう処理するかを決める。
 * Spring Securityは、この設定に従って画面処理より先にログイン状態や権限を確認する。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final List<String> MEMBER_RETURN_PAGES = List.of(
            "/posts", "/ranking", "/pairings", "/goods", "/mypage", "/mypage/account", "/mypage/diagnosis-history");
    private static final Pattern SAKE_ACTION_RETURN_PATH =
            Pattern.compile("/mypage/sake/[1-9][0-9]{0,18}/(?:favorite|review)");

    /**
     * パスワードをBCryptでハッシュ化し、ログイン時に照合する道具を用意する。
     * ハッシュ化は、元の文字列をそのまま保存せず、照合用の値へ変換する処理。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder() {
            @Override
            protected boolean matchesNonNull(String rawPassword, String encodedPassword) {
                // BCryptが末尾を無視して同じパスワードと判定しないよう、照合も設定時と同じ上限で行う。
                return Utf8InputLength.isAtMost(rawPassword, 72) && super.matchesNonNull(rawPassword, encodedPassword);
            }
        };
    }

    /**
     * URLごとのアクセス権限と、ログイン・ログアウトの動きを組み立てる。
     * 上から順にURLを照合し、最初に一致した権限ルールを使う。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationRateLimiter limiter,
            UserRepository users, PasswordEncoder encoder) throws Exception {
        var requestCache = new HttpSessionRequestCache();
        // ログイン後に開く会員画面と銘柄詳細への復帰用GETだけをセッションに保持する。
        requestCache.setRequestMatcher(request -> "GET".equals(request.getMethod())
                && isMemberPage(request.getContextPath(), request.getRequestURI()));

        http
            .addFilterAfter(new AuthenticationRateLimitFilter(limiter), CsrfFilter.class)
            .addFilterBefore(new AccountSessionValidationFilter(users, encoder), AuthorizationFilter.class)
            .requestCache(cache -> cache.requestCache(requestCache))
            .headers(headers -> headers.referrerPolicy(policy ->
                policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(auth -> auth
                // 管理者専用のURLを先に確認する。hasRole("ADMIN")はROLE_ADMINという権限を要求する。
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                // 未ログインでも閲覧可能な画面(S01〜S05, ログイン/新規登録, 静的リソース)
                .requestMatchers(
                    "/", "/guide", "/search", "/sake/**",
                    "/brewery-map", "/contact", "/categories", "/tags",
                    "/about", "/privacy", "/external-transmission",
                    "/diagnosis/**",
                    // 未認証の会員も認証・再送を利用できるよう公開する。POSTのCSRF保護は維持する。
                    "/login", "/signup", "/forgot-password", "/reset-password", "/verify-email", "/verify-email/resend",
                    "/css/**", "/js/**", "/images/**"
                ).permitAll()
                // お気に入り(S06)・マイページ(S08)はログイン必須
                .requestMatchers("/favorites/**", "/mypage/**").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")           // 独自のログイン画面を使用
                .loginProcessingUrl("/login")  // フォームのPOST先。Spring Securityが自動で処理する
                .successHandler((request, response, authentication) -> {
                    var saved = requestCache.getRequest(request, response);
                    requestCache.removeRequest(request, response);
                    String contextPath = request.getContextPath();
                    var user = (CustomUserDetails) authentication.getPrincipal();
                    if (user.isTemporaryPassword()) {
                        response.sendRedirect(contextPath + "/mypage?passwordChangeRequired");
                    } else if (user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
                        response.sendRedirect(contextPath + "/admin");
                    } else if (saved instanceof DefaultSavedRequest page && isMemberPage(contextPath, page.getRequestURI())) {
                        // 保存されたホスト名を使わず、同じアプリ内のパスと検索条件だけで移動する。
                        String query = page.getQueryString();
                        response.sendRedirect(query == null ? page.getRequestURI() : page.getRequestURI() + "?" + query);
                    } else {
                        response.sendRedirect(contextPath + "/");
                    }
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

    private static boolean isMemberPage(String contextPath, String requestUri) {
        if (!requestUri.startsWith(contextPath)) return false;
        String path = requestUri.substring(contextPath.length());
        return MEMBER_RETURN_PAGES.contains(path) || SAKE_ACTION_RETURN_PATH.matcher(path).matches();
    }
}
