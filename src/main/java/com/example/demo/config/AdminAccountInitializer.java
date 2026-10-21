package com.example.demo.config;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 明示的に有効にした初回セットアップ時だけ、外部設定から管理者を作成する。
 * 通常起動ではDBを参照せず、既存アカウントの上書きや管理者への昇格も行わない。
 */
@Configuration
public class AdminAccountInitializer {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+$");

    // CommandLineRunnerは、アプリ起動時の準備が終わった後に1回実行される処理。
    @Bean
    CommandLineRunner createAdmin(UserRepository users, PasswordEncoder encoder,
            @Value("${app.admin.bootstrap-enabled:false}") boolean enabled,
            @Value("${app.admin.email:}") String email,
            @Value("${app.admin.password:}") String password,
            @Value("${app.admin.name:管理者}") String name) {
        return args -> {
            if (!enabled) {
                return;
            }
            validate(email, password, name);
            if (users.findByEmail(email).isPresent()) {
                return;
            }
            User user = new User();
            user.setName(name);
            user.setEmail(email);
            user.setPasswordHash(encoder.encode(password));
            user.setAdmin(true);
            user.setEmailVerified(true);
            users.save(user);
        };
    }

    private void validate(String email, String password, String name) {
        if (email == null || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw new IllegalStateException("初期管理者のメールアドレス設定が不正です。");
        }
        if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 50) {
            throw new IllegalStateException("初期管理者の表示名は1〜50文字で設定してください。");
        }
        if (!isStrongPassword(password)) {
            // 設定値自体は、例外やログに含めない。
            throw new IllegalStateException("初期管理者のパスワードは12文字以上・UTF-8で72バイト以内、"
                    + "英大文字・英小文字・数字・記号の3種類以上を使い、既定値や単純な繰り返しを避けてください。");
        }
    }

    private boolean isStrongPassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72 || password.isBlank()) {
            return false;
        }
        int types = 0;
        types += password.chars().anyMatch(c -> c >= 'A' && c <= 'Z') ? 1 : 0;
        types += password.chars().anyMatch(c -> c >= 'a' && c <= 'z') ? 1 : 0;
        types += password.chars().anyMatch(c -> c >= '0' && c <= '9') ? 1 : 0;
        types += password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) ? 1 : 0;
        String lower = password.toLowerCase(Locale.ROOT);
        if (types < 3 || lower.startsWith("password") || lower.startsWith("changeme")
                || lower.startsWith("change-me") || lower.startsWith("default") || lower.startsWith("admin")
                || lower.startsWith("welcome") || lower.startsWith("qwerty") || lower.startsWith("letmein")) {
            return false;
        }
        // 短い同一パターンの反復（例: Ab1!Ab1!Ab1!）を初期パスワードにしない。
        for (int length = 1; length <= password.length() / 2; length++) {
            if (password.length() % length == 0
                    && password.substring(0, length).repeat(password.length() / length).equals(password)) {
                return false;
            }
        }
        return true;
    }
}
