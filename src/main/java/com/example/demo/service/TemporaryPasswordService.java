package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemporaryPasswordService {
    private static final String CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";
    private static final int PASSWORD_LENGTH = 14;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();
    private final String fromAddress;

    public TemporaryPasswordService(UserRepository users, PasswordEncoder passwordEncoder,
            JavaMailSender mailSender, @Value("${app.mail.from}") String fromAddress) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /** 登録がある場合だけ仮パスワードを発行する。戻り値で登録有無は公開しない。 */
    @Transactional
    public void issueFor(String email) {
        users.findByEmail(email.trim()).ifPresent(this::issueAndSend);
    }

    private void issueAndSend(User user) {
        String temporaryPassword = generatePassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setTemporaryPassword(true);
        users.save(user);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("[NEWBOOZE] 仮パスワードのお知らせ");
        message.setText(user.getName() + " さん\n\n"
                + "仮パスワードを発行しました。\n\n"
                + "仮パスワード: " + temporaryPassword + "\n\n"
                + "このパスワードでログイン後、マイページで必ず新しいパスワードに変更してください。\n"
                + "心当たりがない場合は、このメールを破棄してください。");
        mailSender.send(message);
    }

    private String generatePassword() {
        StringBuilder result = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            result.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return result.toString();
    }
}
