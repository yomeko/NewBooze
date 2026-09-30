package com.example.demo.controller;

import com.example.demo.repository.UserProfileImageRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReviewProfileImageController {
    private final UserProfileImageRepository images;
    private final JdbcTemplate jdbc;

    public ReviewProfileImageController(UserProfileImageRepository images, JdbcTemplate jdbc) {
        this.images = images;
        this.jdbc = jdbc;
    }

    @GetMapping("/sake/reviewers/{userId}/profile-image")
    public ResponseEntity<byte[]> profileImage(@PathVariable long userId) {
        boolean published = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM sake_reviews WHERE user_id = ? AND published = 1)",
                Boolean.class, userId));
        if (!published) return ResponseEntity.notFound().build();
        return images.findById(userId)
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getContentType()))
                        .cacheControl(CacheControl.noCache())
                        .body(image.getImageData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
