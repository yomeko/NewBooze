package com.example.demo.config;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * アプリ起動時に、初期管理者のアカウントが存在するかを確認する。
 * 環境変数で初期情報を明示したときだけ作成し、既存アカウントは変更しない。
 * {@code @Configuration}は設定をまとめるクラス、@BeanはSpringに管理してもらう処理や道具の指定。
 */
@Configuration
public class AdminAccountInitializer {
    // CommandLineRunnerは、アプリ起動時の準備が終わった後に1回実行される処理。
    @Bean
    CommandLineRunner createAdmin(UserRepository users, PasswordEncoder encoder,
            @Value("${app.admin.email:}") String email,
            @Value("${app.admin.password:}") String password) {
        return args -> {
            if (email.isBlank() && password.isBlank()) return;
            if (email.isBlank() || email.strip().length() > 255 || password.length() < 16
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("初期管理者にはADMIN_EMAILと16文字以上・72バイト以内のADMIN_PASSWORDを設定してください");
            }
            var existing = users.findByEmail(email.strip());
            if (existing.isPresent() && !existing.get().isAdmin()) {
                throw new IllegalStateException("初期管理者IDは既存の一般ユーザーと異なるものを設定してください");
            }
            if (existing.isEmpty()) {
                User user = new User();
                user.setName("管理者");
                user.setEmail(email.strip());
                user.setPasswordHash(encoder.encode(password));
                user.setAdmin(true);
                users.save(user);
            }
        };
    }
}
