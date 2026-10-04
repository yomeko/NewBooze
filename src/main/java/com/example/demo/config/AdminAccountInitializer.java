package com.example.demo.config;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminAccountInitializer {
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
