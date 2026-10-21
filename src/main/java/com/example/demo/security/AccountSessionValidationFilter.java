package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 認証後にアカウントの認証情報が変わった場合、残ったセッションで操作を続けさせない。
 * 静的ファイル以外の認証済みリクエストごとに、DB上の現在の状態を1回取得する。
 */
public class AccountSessionValidationFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AccountSessionValidationFilter(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")
                || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CustomUserDetails principal)) {
            chain.doFilter(request, response);
            return;
        }
        User current = principal.getUserId() == null ? null : users.findById(principal.getUserId()).orElse(null);
        if (!isCurrentAccount(principal, current)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendRedirect(request.getContextPath() + "/login?sessionExpired");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isCurrentAccount(CustomUserDetails principal, User current) {
        if (current == null || !current.isEmailVerified()
                || !Objects.equals(principal.getUsername(), current.getEmail())
                || !Objects.equals(principal.getPassword(), current.getPasswordHash())) {
            return false;
        }
        boolean wasAdmin = principal.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        if (wasAdmin != current.isAdmin()) {
            return false;
        }
        if (current.isAdmin() && !principal.isLegacyAdminPasswordChecked()) {
            if (encoder.matches("admin", current.getPasswordHash())) {
                return false;
            }
            principal.markLegacyAdminPasswordChecked();
        }
        return true;
    }
}
