package com.example.demo.config;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * アプリ起動時に、初期管理者のアカウントが存在するかを確認する。
 * 存在しないときだけコード内の初期情報で作成し、すでにある場合は変更しない。
 * {@code @Configuration}は設定をまとめるクラス、@BeanはSpringに管理してもらう処理や道具の指定。
 */
@Configuration
public class AdminAccountInitializer {
    // CommandLineRunnerは、アプリ起動時の準備が終わった後に1回実行される処理。
    @Bean
    CommandLineRunner createAdmin(UserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (users.findByEmail("admin").isEmpty()) {
                User user = new User();
                user.setName("admin");
                user.setEmail("admin");
                user.setPasswordHash(encoder.encode("admin"));
                user.setAdmin(true);
                users.save(user);
            }
        };
    }
}
