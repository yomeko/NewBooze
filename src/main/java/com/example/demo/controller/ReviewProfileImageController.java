package com.example.demo.controller;

import com.example.demo.repository.UserProfileImageRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 公開レビューを書いたユーザーのプロフィール画像を返す。
 * {@code @RestController}はHTMLの画面名ではなく、画像などのデータそのものをブラウザへ送る指定。
 * 公開レビューがないユーザーや、画像を登録していないユーザーには404を返す。
 */
@RestController
public class ReviewProfileImageController {
    private final UserProfileImageRepository images;
    private final JdbcTemplate jdbc;

    public ReviewProfileImageController(UserProfileImageRepository images, JdbcTemplate jdbc) {
        this.images = images;
        this.jdbc = jdbc;
    }

    /**
     * 指定ユーザーに公開レビューがあるか確認してから、画像データを返す。
     * SQLのEXISTSは対象の行が1件でもあるかを調べる。?にはuserIdの値を渡す。
     */
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
