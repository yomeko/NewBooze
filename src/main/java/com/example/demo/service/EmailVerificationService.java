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

/** 登録とメール送信を同一トランザクションで行い、送信失敗時は仮登録も戻す。 */
@Service
public class EmailVerificationService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JavaMailSender mail;
    private final String from;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(UserRepository users, PasswordEncoder encoder,
            JavaMailSender mail, @Value("${app.mail.from}") String from,
            @Value("${app.public-base-url}") String baseUrl) {
        this.users = users;
        this.encoder = encoder;
        this.mail = mail;
        this.from = from;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public void register(SignupForm form) {
        User user = new User();
        user.setName(form.getName());
        user.setEmail(form.getEmail().trim());
        user.setPasswordHash(encoder.encode(form.getPassword()));
        user.setEmailVerified(false);
        users.saveAndFlush(user);
        send(user);
    }

    /** アカウントの有無を公開せず、同一ユーザーへの再送は60秒に1回まで。 */
    @Transactional
    public void resend(String email) {
        users.findForVerificationByEmail(email.trim()).filter(u -> !u.isEmailVerified())
                .filter(u -> u.getVerificationSentAt() == null
                        || !u.getVerificationSentAt().isAfter(LocalDateTime.now().minusSeconds(60)))
                .ifPresent(this::send);
    }

    /** 条件付き更新により、期限内のトークンを一度だけ使用できる。 */
    @Transactional
    public boolean verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        return users.verifyEmail(hash(token), LocalDateTime.now()) == 1;
    }

    private void send(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now();
        user.setVerificationTokenHash(hash(token));
        user.setVerificationExpiresAt(now.plusHours(24));
        user.setVerificationSentAt(now);
        users.saveAndFlush(user);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[一升の出会い] メールアドレスの確認");
        message.setText(user.getName() + " さん\n\n以下のリンクを開くと登録が完了します。\n"
                + baseUrl + "/verify-email?token=" + token
                + "\n\n有効期限は24時間です。再送すると以前のリンクは無効になります。\n"
                + "心当たりがない場合は、このメールを破棄してください。");
        mail.send(message);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
