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

/**
 * お気に入りとレビューを読み取り、保存・削除する処理。
 * お気に入りはRepository、レビューはJdbcTemplate（SQLを実行する道具）を使う。
 * SQLの?には引数の値を渡し、ユーザーの入力をSQLの文章に直接つなげない。
 */
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

    // 本人の保存済み評価。publishedは、ほかの人にも公開するかどうか。
    public record Review(int rating, String comment, boolean published) {}
    // 公開一覧で表示する投稿者、画像の表示設定、評価、コメント、更新日時。
    public record PublicReview(long userId, String displayName, boolean hasProfileImage,
            int positionX, int positionY, int zoom, int rating, String comment, java.time.LocalDateTime updatedAt) {}
    // 公開レビューの1ページ分と、全体の件数・平均・前後のページの情報。
    public record ReviewPage(java.util.List<PublicReview> content, long total, double average,
            int pageNumber, int totalPages, String sort) {
        public boolean hasPrevious() { return pageNumber > 0; }
        public boolean hasNext() { return pageNumber + 1 < totalPages; }
    }

    /**
     * 公開レビューの総件数・平均評価と、1ページ分の10件を取得する。
     * 並び順は決められた3種類から選び、ページ番号は存在する範囲へ収める。
     * 非公開レビューは件数・平均・一覧のどれにも含めない。
     */
    @Transactional(readOnly = true)
    public ReviewPage publicReviews(long sakeId, String requestedSort, int requestedPage) {
        String sort = switch (requestedSort == null ? "newest" : requestedSort) {
            case "ratingDesc", "ratingAsc" -> requestedSort;
            default -> "newest";
        };
        // 並び替え用のSQLは固定の文字列から選ぶ。入力された文字列をそのままSQLに加えない。
        String order = switch (sort) {
            case "ratingDesc" -> "r.rating DESC, r.updated_at DESC, r.user_id DESC";
            case "ratingAsc" -> "r.rating ASC, r.updated_at DESC, r.user_id DESC";
            default -> "r.updated_at DESC, r.user_id DESC";
        };
        double[] summary = jdbc.queryForObject(
                "SELECT COUNT(*), COALESCE(AVG(rating), 0) FROM sake_reviews WHERE sake_id = ? AND published = 1",
                (rs, row) -> new double[] {rs.getLong(1), rs.getDouble(2)}, sakeId);
        long total = (long) summary[0];
        int totalPages = (int) Math.max(1, (total + 9) / 10);
        int page = Math.clamp(requestedPage, 0, totalPages - 1);
        var content = jdbc.query("""
                SELECT u.id, u.name, p.user_id AS image_user_id,
                       COALESCE(p.position_x, 50) AS position_x, COALESCE(p.position_y, 50) AS position_y,
                       COALESCE(p.zoom, 100) AS zoom, r.rating, r.comment, r.updated_at FROM sake_reviews r
                JOIN users u ON u.id = r.user_id
                LEFT JOIN user_profile_images p ON p.user_id = u.id
                WHERE r.sake_id = ? AND r.published = 1
                """ + " ORDER BY " + order + " LIMIT 10 OFFSET ?",
                (rs, row) -> new PublicReview(rs.getLong("id"), rs.getString("name"), rs.getObject("image_user_id") != null,
                        rs.getInt("position_x"), rs.getInt("position_y"), rs.getInt("zoom"), rs.getInt("rating"),
                        rs.getString("comment"), rs.getTimestamp("updated_at").toLocalDateTime()), sakeId, page * 10);
        return new ReviewPage(content, total, summary[1], page, totalPages, sort);
    }

    /**
     * 本人と銘柄の組み合わせが、お気に入りに保存済みかを確認する。
     */
    public boolean isFavorite(long userId, long sakeId) {
        return favorites.existsByIdUserIdAndIdSakeId(userId, sakeId);
    }

    /**
     * 本人がこの銘柄へ保存したレビューを、公開・非公開にかかわらず取得する。
     */
    public Optional<Review> review(long userId, long sakeId) {
        return jdbc.query("SELECT rating, comment, published FROM sake_reviews WHERE user_id = ? AND sake_id = ?",
                (rs, row) -> new Review(rs.getInt("rating"), rs.getString("comment"), rs.getBoolean("published")), userId, sakeId)
                .stream().findFirst();
    }

    /**
     * 銘柄の存在を確認してから、本人のお気に入りを追加・解除する。
     * 登録済みなら同じ組み合わせを追加しない。
     */
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

    /**
     * 星は1〜5、コメントは500文字以内か確認し、レビューを保存する。
     * 同じ人・同じ銘柄のレビューがあれば更新するSQLなので、1人1銘柄につき1件になる。
     */
    @Transactional
    public void saveReview(long userId, long sakeId, int rating, String comment, boolean published) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("星を1〜5から選んでください。");
        comment = comment == null ? "" : comment.strip();
        // 絵文字などJavaでは2文字分として数える文字も、ここでは1文字として数える。
        if (comment.codePointCount(0, comment.length()) > 500)
            throw new IllegalArgumentException("コメントは500文字以内で入力してください。");
        requireSake(sakeId);
        jdbc.update("""
                INSERT INTO sake_reviews (user_id, sake_id, rating, comment, published) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rating = VALUES(rating), comment = VALUES(comment), published = VALUES(published), updated_at = CURRENT_TIMESTAMP
                """, userId, sakeId, rating, comment, published);
    }

    /**
     * 本人とこの銘柄の組み合わせに一致するレビューだけを削除する。
     */
    @Transactional
    public void deleteReview(long userId, long sakeId) {
        requireSake(sakeId);
        jdbc.update("DELETE FROM sake_reviews WHERE user_id = ? AND sake_id = ?", userId, sakeId);
    }

    /**
     * 指定された銘柄が存在するかを確認し、なければ404を返す。
     */
    private void requireSake(long sakeId) {
        if (!sake.existsById(sakeId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
