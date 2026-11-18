package com.example.demo.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 閲覧数と公式サイトへの遷移数を、個人情報を持たない集計値として保存する。 */
@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SakeCount(long sakeId, String name, long count) {}
    public record TagTotal(long tagId, String name, long count) {}
    public record TaggedSakeCount(long tagId, long sakeId, String name, long count) {}
    public record DailyCount(LocalDate date, long count) {}

    /** 同時にクリックされても、DB内の加算で取りこぼさない。存在しない銘柄は保存しない。 */
    public void incrementOfficialClick(long sakeId) {
        jdbc.update("""
                INSERT INTO analytics_sake_clicks (sake_id, click_count)
                SELECT id, 1 FROM sake WHERE id = ?
                ON DUPLICATE KEY UPDATE click_count = click_count + 1
                """, sakeId);
        // タグの重みは件数に掛けず、その時点の正のタグすべてへ1件ずつ加算する。
        jdbc.update("""
                INSERT INTO analytics_tag_clicks (tag_id, sake_id, click_count)
                SELECT tag_id, sake_id, 1 FROM sake_tags WHERE sake_id = ? AND score > 0
                ORDER BY tag_id
                ON DUPLICATE KEY UPDATE click_count = click_count + 1
                """, sakeId);
    }

    public void incrementPageView(LocalDate date, String pagePath) {
        jdbc.update("""
                INSERT INTO analytics_page_views (view_date, page_path, view_count) VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE view_count = view_count + 1
                """, date, pagePath);
    }

    public long totalClicks() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(click_count), 0) FROM analytics_sake_clicks", Long.class);
    }

    public long totalViews() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(view_count), 0) FROM analytics_page_views", Long.class);
    }

    public long viewsOn(LocalDate date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(view_count), 0) FROM analytics_page_views WHERE view_date = ?",
                Long.class, date);
    }

    public List<SakeCount> topSakes() {
        return jdbc.query("""
                SELECT c.sake_id, s.name, c.click_count
                FROM analytics_sake_clicks c JOIN sake s ON s.id = c.sake_id
                WHERE c.click_count > 0 ORDER BY c.click_count DESC, c.sake_id ASC LIMIT 10
                """, (rs, row) -> new SakeCount(rs.getLong("sake_id"), rs.getString("name"), rs.getLong("click_count")));
    }

    /** 未クリックのタグも0件で返す。現在のsake_tagsには結び直さず、記録済みの属性で集計する。 */
    public List<TagTotal> tagTotals() {
        return jdbc.query("""
                SELECT t.id, t.name, COALESCE(SUM(c.click_count), 0) AS click_count
                FROM tags t LEFT JOIN analytics_tag_clicks c ON c.tag_id = t.id
                GROUP BY t.id, t.name ORDER BY click_count DESC, t.id ASC
                """, (rs, row) -> new TagTotal(rs.getLong("id"), rs.getString("name"), rs.getLong("click_count")));
    }

    /** DB側で各タグの上位5件に絞り、タグ数に比例した件数だけ読み取る。 */
    public List<TaggedSakeCount> topSakesByTag() {
        return jdbc.query("""
                SELECT ranked.tag_id, ranked.sake_id, s.name, ranked.click_count
                FROM (
                    SELECT tag_id, sake_id, click_count,
                           ROW_NUMBER() OVER (PARTITION BY tag_id ORDER BY click_count DESC, sake_id ASC) AS position
                    FROM analytics_tag_clicks WHERE click_count > 0
                ) ranked JOIN sake s ON s.id = ranked.sake_id
                WHERE ranked.position <= 5 ORDER BY ranked.tag_id ASC, ranked.position ASC
                """, (rs, row) -> new TaggedSakeCount(rs.getLong("tag_id"), rs.getLong("sake_id"),
                        rs.getString("name"), rs.getLong("click_count")));
    }

    public List<DailyCount> dailyViews(LocalDate firstDate, LocalDate lastDate) {
        return jdbc.query("""
                SELECT view_date, SUM(view_count) AS view_count FROM analytics_page_views
                WHERE view_date BETWEEN ? AND ? GROUP BY view_date ORDER BY view_date ASC
                """, (rs, row) -> new DailyCount(rs.getDate("view_date").toLocalDate(), rs.getLong("view_count")),
                firstDate, lastDate);
    }
}
