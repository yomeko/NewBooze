package com.example.demo.service;

import com.example.demo.entity.Favorite;
import com.example.demo.entity.FavoriteId;
import com.example.demo.repository.FavoriteRepository;
import com.example.demo.repository.SakeRepository;
import com.example.demo.repository.UserRepository;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SakeInteractionService {
    private final FavoriteRepository favorites;
    private final SakeRepository sake;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public SakeInteractionService(FavoriteRepository favorites, SakeRepository sake,
            UserRepository users, JdbcTemplate jdbc) {
        this.favorites = favorites;
        this.sake = sake;
        this.users = users;
        this.jdbc = jdbc;
    }

    public record Review(int rating, String comment) {}

    public boolean isFavorite(long userId, long sakeId) {
        return favorites.existsByIdUserIdAndIdSakeId(userId, sakeId);
    }

    public Optional<Review> review(long userId, long sakeId) {
        return jdbc.query("SELECT rating, comment FROM sake_reviews WHERE user_id = ? AND sake_id = ?",
                (rs, row) -> new Review(rs.getInt("rating"), rs.getString("comment")), userId, sakeId)
                .stream().findFirst();
    }

    @Transactional
    public void favorite(long userId, long sakeId, boolean selected) {
        var product = sake.findById(sakeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        FavoriteId id = new FavoriteId(userId, sakeId);
        if (!selected) {
            favorites.deleteById(id);
        } else if (!favorites.existsById(id)) {
            Favorite favorite = new Favorite();
            favorite.setId(id);
            favorite.setSake(product);
            favorite.setUser(users.getReferenceById(userId));
            favorites.save(favorite);
        }
    }

    @Transactional
    public void saveReview(long userId, long sakeId, int rating, String comment) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("星を1〜5から選んでください。");
        comment = comment == null ? "" : comment.strip();
        if (comment.codePointCount(0, comment.length()) > 500)
            throw new IllegalArgumentException("コメントは500文字以内で入力してください。");
        requireSake(sakeId);
        jdbc.update("""
                INSERT INTO sake_reviews (user_id, sake_id, rating, comment) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rating = VALUES(rating), comment = VALUES(comment), updated_at = CURRENT_TIMESTAMP
                """, userId, sakeId, rating, comment);
    }

    @Transactional
    public void deleteReview(long userId, long sakeId) {
        requireSake(sakeId);
        jdbc.update("DELETE FROM sake_reviews WHERE user_id = ? AND sake_id = ?", userId, sakeId);
    }

    private void requireSake(long sakeId) {
        if (!sake.existsById(sakeId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
