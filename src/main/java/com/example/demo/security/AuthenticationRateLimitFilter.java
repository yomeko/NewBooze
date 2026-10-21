package com.example.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/** CSRF確認後、認証・メール送信の処理前に連続操作を止める。 */
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> PATHS = Set.of("/login", "/signup", "/forgot-password",
            "/reset-password", "/verify-email", "/verify-email/resend");
    private final AuthenticationRateLimiter limiter;

    public AuthenticationRateLimitFilter(AuthenticationRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATHS.contains(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String action = path(request);
        String account = "/login".equals(action) ? request.getParameter("username") : null;
        if (account != null && account.length() > 255) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "入力内容を確認してください");
            return;
        }
        // 利用者が自由に指定できるX-Forwarded-For等は参照しない。
        var decision = limiter.check(action, request.getRemoteAddr(), account);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("""
                <!doctype html><html lang="ja"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>時間をあけてお試しください | 一升の出会い</title></head><body>
                <main><h1>操作が続いています</h1><p>しばらく時間をあけて、もう一度お試しください。</p>
                <p><a href="/login">ログイン画面へ</a> <a href="/">ホームへ</a></p></main></body></html>
                """);
    }

    private static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath + (request.getPathInfo() == null ? "" : request.getPathInfo());
        }
        // コンテナーと同じデコード後のパスで判定し、文字のURLエンコードによる回避を防ぐ。
        return UriUtils.decode(request.getRequestURI().substring(request.getContextPath().length()),
                StandardCharsets.UTF_8);
    }
}
