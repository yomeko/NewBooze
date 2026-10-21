package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.validation.Utf8InputLength;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** メール内の一度限りのリンクで本人を確認してから、パスワードを変更する。 */
@Service
public class PasswordResetService {
    public static final String PASSWORD_REQUIREMENTS = "新しいパスワードは8文字以上、UTF-8で72バイト以内にしてください";
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JavaMailSender mail;
    private final String from;
    private final String baseUrl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PasswordResetService(UserRepository users, PasswordEncoder encoder, JavaMailSender mail,
            @Value("${app.mail.from}") String from, @Value("${app.base-url}") String baseUrl) {
        this(users, encoder, mail, from, baseUrl, Clock.systemUTC());
    }

    PasswordResetService(UserRepository users, PasswordEncoder encoder, JavaMailSender mail,
            String from, String baseUrl, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.mail = mail;
        this.from = from;
        this.clock = clock;
        URI uri = URI.create(baseUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !("https".equals(uri.getScheme())
                || (local && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("app.base-urlにはHTTPSの公開URL、またはlocalhostの開発URLを指定してください");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** 未登録・未認証・60秒未満は送らない。要求だけで現在のパスワードを変えない。 */
    @Transactional
    public void requestFor(String email) {
        LocalDateTime now = LocalDateTime.now(clock);
        users.findForPasswordResetByEmail(email.trim()).filter(User::isEmailVerified)
                .filter(user -> user.getPasswordResetSentAt() == null
                        || !user.getPasswordResetSentAt().isAfter(now.minusSeconds(60)))
                .ifPresent(user -> send(user, now));
    }

    private void send(User user, LocalDateTime now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        user.setPasswordResetHash(hash(token));
        user.setPasswordResetExpiresAt(now.plusMinutes(30));
        user.setPasswordResetSentAt(now);
        // DB制約の確認後に送信する。SMTP例外はトランザクション全体を戻す。
        users.saveAndFlush(user);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[一升の出会い] パスワード再設定のご案内");
        message.setText(user.getName() + " さん\n\nパスワードの再設定を受け付けました。次のリンクを開き、新しいパスワードを設定してください。\n"
                + baseUrl + "/reset-password?token=" + token
                + "\n\nリンクは30分間、一度だけ使えます。再送すると以前のリンクは無効になります。\n"
                + "この操作を依頼していない場合はメールを破棄してください。パスワードは変更されていません。");
        mail.send(message);
    }

    /** GETでは呼ばない。行ロックの下でリンクを検証し、変更と同時に消費する。 */
    @Transactional
    public boolean reset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || !Utf8InputLength.isAtMost(newPassword, 72)) {
            throw new IllegalArgumentException(PASSWORD_REQUIREMENTS);
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        String tokenHash = hash(token);
        var candidate = users.findByPasswordResetHash(tokenHash);
        if (candidate.isEmpty()) return false;
        User user = candidate.get();
        if (!user.isEmailVerified() || !tokenHash.equals(user.getPasswordResetHash())
                || user.getPasswordResetExpiresAt() == null
                || !user.getPasswordResetExpiresAt().isAfter(LocalDateTime.now(clock))) return false;
        user.setPasswordHash(encoder.encode(newPassword));
        user.setTemporaryPassword(false);
        user.clearPasswordResetToken();
        users.saveAndFlush(user);
        return true;
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
