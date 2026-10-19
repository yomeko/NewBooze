package com.example.demo.service;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Clock;
import java.net.URI;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    // テスト用Clockを受け取るコンストラクターと区別し、Springが使う入口を明示する。
    @Autowired
    public EmailVerificationService(UserRepository users, PasswordEncoder encoder, JavaMailSender mail,
            @Value("${app.mail.from}") String from, @Value("${app.base-url}") String baseUrl) {
        this(users, encoder, mail, from, baseUrl, Clock.systemUTC());
    }

    // Clockを差し替え、24時間・60秒の境界を実時間の待機なしでテストする。
    EmailVerificationService(UserRepository users, PasswordEncoder encoder, JavaMailSender mail,
            String from, String baseUrl, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.mail = mail;
        this.from = from;
        this.clock = clock;
        // 認証リンクの送信先ドメインを固定し、リクエストのHostによる差し替えを防ぐ。
        // HTTPを許可するのはローカル開発時だけとし、公開先はHTTPSを必須にする。
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

    // 保存とSMTP送信を同じトランザクションに含め、送信例外時は登録を取り消す。
    @Transactional
    public void register(SignupForm form) {
        User user = new User();
        user.setName(form.getName());
        user.setEmail(form.getEmail().trim());
        user.setPasswordHash(encoder.encode(form.getPassword()));
        // 認証が済むまでCustomUserDetails.isEnabled()がログインを拒否する。
        user.setEmailVerified(false);
        send(user);
    }

    /** 登録の有無・認証済みかは呼び出し側へ公開しない。連続再送を60秒制限する。 */
    @Transactional
    public void resend(String email) {
        // 行ロック中に送信間隔を確認し、同時に届いた再送要求の二重送信を抑える。
        users.findForVerificationByEmail(email.trim()).filter(u -> !u.isEmailVerified())
                .filter(u -> u.getEmailVerificationSentAt() == null
                        || !u.getEmailVerificationSentAt().isAfter(LocalDateTime.now(clock).minusSeconds(60)))
                .ifPresent(this::send);
    }

    @Transactional
    public boolean verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        // 生のトークンを保存せず、ハッシュで対象行をロックして一度だけ確定する。
        var candidate = users.findByEmailVerificationHash(hash(token));
        if (candidate.isEmpty()) return false;
        User user = candidate.get();
        if (user.isEmailVerified() || user.getEmailVerificationExpiresAt() == null
                || !user.getEmailVerificationExpiresAt().isAfter(LocalDateTime.now(clock))) return false;
        // 使用済みのハッシュと期限を消し、同じリンクでの再認証をできなくする。
        user.setEmailVerified(true);
        user.setEmailVerificationHash(null);
        user.setEmailVerificationExpiresAt(null);
        users.save(user);
        return true;
    }

    /** メールの変更時は新しい宛先へ再認証。SMTP失敗時は氏名・メール変更も戻す。 */
    @Transactional
    public boolean updateProfile(Long userId, String name, String email) {
        User user = users.findForProfileUpdateById(userId).orElseThrow();
        user.setName(name);
        // 氏名だけの変更やメールアドレスの大文字・小文字の差では再認証しない。
        if (user.getEmail().equalsIgnoreCase(email)) {
            users.save(user);
            return false;
        }
        user.setEmail(email);
        // 認証が済むまでCustomUserDetails.isEnabled()がログインを拒否する。
        user.setEmailVerified(false);
        send(user);
        return true;
    }

    private void send(User user) {
        // 推測しにくい256ビットの乱数を、URLに安全な43文字のトークンにする。
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime now = LocalDateTime.now(clock);
        user.setEmailVerificationHash(hash(token));
        user.setEmailVerificationExpiresAt(now.plusHours(24));
        user.setEmailVerificationSentAt(now);
        // メール送信より先にDB制約を検査し、保存できないリンクの送信を避ける。
        // 再送の場合は同じ行のハッシュを置き換えるため、以前のリンクは無効になる。
        users.saveAndFlush(user);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[一升の出会い] メールアドレスの認証");
        message.setText(user.getName() + " さん\n\n新規登録を完了するには、次のリンクを開いて認証ボタンを押してください。\n"
                + baseUrl + "/verify-email?token=" + token
                + "\n\nリンクは24時間有効です。再送すると以前のリンクは無効になります。\n"
                + "心当たりがない場合は、このメールを破棄してください。");
        // 例外を握りつぶさず呼び出し元へ返すことで、DBの登録・変更も戻す。
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
