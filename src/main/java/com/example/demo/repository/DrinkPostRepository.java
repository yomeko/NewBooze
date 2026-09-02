package com.example.demo.repository;

import com.example.demo.entity.DrinkPost;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrinkPostRepository extends JpaRepository<DrinkPost, Long> {
    List<DrinkPost> findByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = "user")
    List<DrinkPost> findAllByOrderByCreatedAtDesc();

    boolean existsByUserIdAndSakeNameIgnoreCaseAndCommentAndCreatedAtAfter(
            Long userId, String sakeName, String comment, LocalDateTime after);

    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);
}
