package com.example.demo.service;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 認証トークンのハッシュのみを保存し、メール送信失敗時は登録・更新を戻す。 */
@Service
public class EmailVerificationService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JavaMailSender mail;
    private final String from;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(UserRepository users, PasswordEncoder encoder, JavaMailSender mail,
            @Value("${app.mail.from}") String from, @Value("${app.base-url}") String baseUrl) {
        this.users = users;
        this.encoder = encoder;
        this.mail = mail;
        this.from = from;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        if (!this.baseUrl.matches("https?://[^\\s?#]+")) {
            throw new IllegalArgumentException("app.base-urlにはアプリの公開URLを指定してください");
        }
    }

    @Transactional
    public void register(SignupForm form) {
        User user = new User();
        user.setName(form.getName());
        user.setEmail(form.getEmail().trim());
        user.setPasswordHash(encoder.encode(form.getPassword()));
        user.setEmailVerified(false);
        send(user);
    }

    /** 登録の有無・認証済みかは呼び出し側へ公開しない。連続再送を60秒制限する。 */
    @Transactional
    public void resend(String email) {
        users.findForVerificationByEmail(email.trim()).filter(u -> !u.isEmailVerified())
                .filter(u -> u.getEmailVerificationSentAt() == null
                        || !u.getEmailVerificationSentAt().isAfter(LocalDateTime.now().minusSeconds(60)))
                .ifPresent(this::send);
    }

    @Transactional
    public boolean verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        var candidate = users.findByEmailVerificationHash(hash(token));
        if (candidate.isEmpty()) return false;
        User user = candidate.get();
        if (user.isEmailVerified() || user.getEmailVerificationExpiresAt() == null
                || !user.getEmailVerificationExpiresAt().isAfter(LocalDateTime.now())) return false;
        user.setEmailVerified(true);
        user.setEmailVerificationHash(null);
        user.setEmailVerificationExpiresAt(null);
        users.save(user);
        return true;
    }

    private void send(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        user.setEmailVerificationHash(hash(token));
        user.setEmailVerificationExpiresAt(now.plusHours(24));
        user.setEmailVerificationSentAt(now);
        users.saveAndFlush(user);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[一升の出会い] メールアドレスの認証");
        message.setText(user.getName() + " さん\n\n新規登録を完了するには、次のリンクを開いて認証ボタンを押してください。\n"
                + baseUrl + "/verify-email?token=" + token
                + "\n\nリンクは24時間有効です。再送すると以前のリンクは無効になります。\n"
                + "心当たりがない場合は、このメールを破棄してください。");
        mail.send(message);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
