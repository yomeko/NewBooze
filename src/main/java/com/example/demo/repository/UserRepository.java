package com.example.demo.repository;

import com.example.demo.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    // S07のログイン処理（email + password_hash照合）で使用
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
