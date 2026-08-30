package com.example.demo.repository;

import com.example.demo.entity.DrinkPost;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrinkPostRepository extends JpaRepository<DrinkPost, Long> {
    List<DrinkPost> findByUserIdOrderByCreatedAtDesc(Long userId);
}
