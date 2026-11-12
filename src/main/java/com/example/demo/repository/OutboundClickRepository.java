package com.example.demo.repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 公式サイトへのクリックを1件ずつ保存し、管理者向けに件数を集計する。 */
@Repository
public class OutboundClickRepository {
    private static final ZoneId CLICK_ZONE = ZoneId.of("Asia/Tokyo");
    private final JdbcTemplate jdbc;

    public OutboundClickRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long userId, Long sakeId, String targetName, String source,
            String linkKind, String destinationUrl) {
        jdbc.update("""
                INSERT INTO outbound_clicks
                    (user_id, logged_in, sake_id, target_name, source, link_kind, destination_url, clicked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, userId != null, sakeId, targetName, source, linkKind, destinationUrl,
                LocalDateTime.now(CLICK_ZONE));
    }

    // logged_inはクリック時点の状態。退会後にuser_idがNULLになっても内訳を変えない。
    public Counts summary() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN logged_in = 0 THEN 1 ELSE 0 END), 0) AS anonymous,
                       COALESCE(SUM(CASE WHEN logged_in = 1 THEN 1 ELSE 0 END), 0) AS logged_in
                FROM outbound_clicks
                """, (rs, row) -> new Counts(rs.getLong("total"), rs.getLong("anonymous"),
                        rs.getLong("logged_in")));
    }

    public long accountCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT c.user_id)
                FROM outbound_clicks c JOIN users u ON u.id = c.user_id
                """, Long.class);
    }

    public List<AccountClicks> accounts(int limit, long offset) {
        return jdbc.query("""
                SELECT u.id, u.name, COUNT(*) AS clicks, MAX(c.clicked_at) AS last_clicked_at
                FROM outbound_clicks c JOIN users u ON u.id = c.user_id
                GROUP BY u.id, u.name
                ORDER BY clicks DESC, u.id ASC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new AccountClicks(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("clicks"), rs.getObject("last_clicked_at", LocalDateTime.class)),
                limit, offset);
    }

    public record Counts(long total, long anonymous, long loggedIn) {}
    public record AccountClicks(long userId, String name, long clicks, LocalDateTime lastClickedAt) {}
}
