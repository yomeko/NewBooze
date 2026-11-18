package com.example.demo.service;

import com.example.demo.repository.AnalyticsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実DBの加算・集計SQLを確認する。既存データは消さず、専用データは終了時に取り消す。 */
@SpringBootTest
@Transactional
class AnalyticsIntegrationTests {
    @Autowired AnalyticsService analytics;
    @Autowired AnalyticsRepository repository;
    @Autowired JdbcTemplate jdbc;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test void countsEachPositiveTagOnceAndKeepsTheAttributesRecordedAtClickTime() {
        long sakeId = sake("属性記録");
        long strong = tag("辛口");
        long light = tag("軽快");
        long zero = tag("未設定");
        long later = tag("後から追加");
        link(sakeId, strong, 3);
        link(sakeId, light, 1);
        link(sakeId, zero, 0);
        link(sakeId, later, 0);
        long previousTotal = analytics.dashboard().totalClicks();

        analytics.recordOfficialClick(sakeId);
        analytics.recordOfficialClick(sakeId);

        assertThat(sakeClicks(sakeId)).isEqualTo(2);
        assertThat(tagClicks(strong, sakeId)).isEqualTo(2);
        assertThat(tagClicks(light, sakeId)).isEqualTo(2);
        assertThat(tagClicks(zero, sakeId)).isZero();
        assertThat(tagClicks(later, sakeId)).isZero();

        jdbc.update("DELETE FROM sake_tags WHERE sake_id = ? AND tag_id = ?", sakeId, strong);
        jdbc.update("UPDATE sake_tags SET score = 4 WHERE sake_id = ? AND tag_id = ?", sakeId, later);
        analytics.recordOfficialClick(sakeId);

        var dashboard = analytics.dashboard();
        assertThat(dashboard.totalClicks()).isEqualTo(previousTotal + 3);
        assertThat(sakeClicks(sakeId)).isEqualTo(3);
        assertThat(tagClicks(strong, sakeId)).isEqualTo(2);
        assertThat(tagClicks(light, sakeId)).isEqualTo(3);
        assertThat(tagClicks(later, sakeId)).isEqualTo(1);
        var pastTag = dashboard.tags().stream().filter(item -> item.tagId() == strong).findFirst().orElseThrow();
        assertThat(pastTag.count()).isEqualTo(2);
        assertThat(pastTag.topSakes()).singleElement().satisfies(item -> {
            assertThat(item.sakeId()).isEqualTo(sakeId);
            assertThat(item.count()).isEqualTo(2);
        });
        var uncountedTag = dashboard.tags().stream().filter(item -> item.tagId() == zero).findFirst().orElseThrow();
        assertThat(uncountedTag.count()).isZero();
        assertThat(uncountedTag.topSakes()).isEmpty();
    }

    @Test void untaggedSakeStillCountsAndAnUnknownSakeDoesNotCreateAnOrphan() {
        long sakeId = sake("タグなし");
        long previousTotal = analytics.dashboard().totalClicks();

        analytics.recordOfficialClick(sakeId);
        analytics.recordOfficialClick(Long.MAX_VALUE);

        assertThat(sakeClicks(sakeId)).isEqualTo(1);
        assertThat(analytics.dashboard().totalClicks()).isEqualTo(previousTotal + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_tag_clicks WHERE sake_id = ?",
                Long.class, sakeId)).isZero();
    }

    @Test void limitsRankingsAndUsesSakeIdToOrderEqualCounts() {
        long tagId = tag("ランキング");
        long baseline = jdbc.queryForObject(
                "SELECT COALESCE(MAX(click_count), 0) + 100 FROM analytics_sake_clicks", Long.class);
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            long sakeId = sake("順位" + index);
            ids.add(sakeId);
            long count = baseline + (index == 11 ? 1 : 0);
            jdbc.update("INSERT INTO analytics_sake_clicks(sake_id, click_count) VALUES (?, ?)", sakeId, count);
            jdbc.update("INSERT INTO analytics_tag_clicks(tag_id, sake_id, click_count) VALUES (?, ?, ?)",
                    tagId, sakeId, count);
        }
        // 計測時のタグ行があれば、現在のsake_tagsに対応がなくてもランキングへ残る。
        var dashboard = analytics.dashboard();
        List<Long> expectedTopTen = new ArrayList<>(List.of(ids.getLast()));
        expectedTopTen.addAll(ids.subList(0, 9));
        assertThat(dashboard.topSakes()).extracting(AnalyticsService.SakeCount::sakeId)
                .containsExactlyElementsOf(expectedTopTen);

        var tagged = dashboard.tags().stream().filter(item -> item.tagId() == tagId).findFirst().orElseThrow();
        assertThat(tagged.count()).isEqualTo(12 * baseline + 1);
        assertThat(tagged.topSakes()).extracting(AnalyticsService.SakeCount::sakeId)
                .containsExactly(ids.getLast(), ids.get(0), ids.get(1), ids.get(2), ids.get(3));
    }

    @Test void aggregatesViewsByJapaneseDateAndFillsFourteenDaysIncludingEmptyDays() {
        // UTCでは1月31日、日本では2月1日となる時計で日付境界を確認する。
        Clock clock = Clock.fixed(Instant.parse("2400-01-31T15:05:00Z"), ZoneOffset.UTC);
        var fixed = new AnalyticsService(repository, clock);
        LocalDate today = LocalDate.of(2400, 2, 1);
        long previousTotal = fixed.dashboard().totalViews();
        assertThat(fixed.dashboard().dailyViews()).allSatisfy(day -> assertThat(day.count()).isZero());

        fixed.recordPageView("/");
        fixed.recordPageView("/");
        fixed.recordPageView("/search");
        pageViews(today.minusDays(1), 5);
        pageViews(today.minusDays(13), 2);
        pageViews(today.minusDays(14), 9);

        // 同じDBを読む新しいサービスでも集計を引き継ぐ。
        var dashboard = new AnalyticsService(repository, clock).dashboard();
        assertThat(dashboard.totalViews()).isEqualTo(previousTotal + 19);
        assertThat(dashboard.todayViews()).isEqualTo(3);
        assertThat(dashboard.dailyViews()).hasSize(14);
        assertThat(dashboard.dailyViews()).extracting(AnalyticsService.DailyViews::date)
                .containsExactlyElementsOf(today.minusDays(13).datesUntil(today.plusDays(1)).toList());
        assertThat(dashboard.dailyViews().getFirst()).isEqualTo(new AnalyticsService.DailyViews(today.minusDays(13), 2, 40));
        assertThat(dashboard.dailyViews().get(12)).isEqualTo(new AnalyticsService.DailyViews(today.minusDays(1), 5, 100));
        assertThat(dashboard.dailyViews().getLast()).isEqualTo(new AnalyticsService.DailyViews(today, 3, 60));
        assertThat(dashboard.dailyViews().subList(1, 12)).allSatisfy(day -> {
            assertThat(day.count()).isZero();
            assertThat(day.percentage()).isZero();
        });
        assertThat(jdbc.queryForObject(
                "SELECT view_count FROM analytics_page_views WHERE view_date = ? AND page_path = '/'",
                Long.class, today)).isEqualTo(2);
    }

    @Test void rejectsQueriesAndInvalidIdentifiersWithoutRecordingThem() {
        long previousViews = analytics.dashboard().totalViews();
        long previousClicks = analytics.dashboard().totalClicks();
        for (String path : List.of("search", "/search?keyword=secret", "/guide#store", "/" + "x".repeat(255))) {
            assertThatThrownBy(() -> analytics.recordPageView(path)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> analytics.recordPageView(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analytics.recordOfficialClick(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analytics.recordOfficialClick(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(analytics.dashboard().totalViews()).isEqualTo(previousViews);
        assertThat(analytics.dashboard().totalClicks()).isEqualTo(previousClicks);
    }

    private long sake(String name) {
        jdbc.update("INSERT INTO sake_types(name) VALUES (?)", "集計用酒種" + name + suffix);
        long typeId = lastId();
        jdbc.update("INSERT INTO sake(name, sake_type_id) VALUES (?, ?)", "集計用" + name + suffix, typeId);
        return lastId();
    }

    private long tag(String name) {
        jdbc.update("INSERT INTO tags(name, category) VALUES (?, '集計テスト')", name + suffix);
        return lastId();
    }

    private void link(long sakeId, long tagId, int score) {
        jdbc.update("INSERT INTO sake_tags(sake_id, tag_id, score) VALUES (?, ?, ?)", sakeId, tagId, score);
    }

    private long sakeClicks(long sakeId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(click_count), 0) FROM analytics_sake_clicks WHERE sake_id = ?",
                Long.class, sakeId);
    }

    private long tagClicks(long tagId, long sakeId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(click_count), 0) FROM analytics_tag_clicks WHERE tag_id = ? AND sake_id = ?",
                Long.class, tagId, sakeId);
    }

    private void pageViews(LocalDate date, int count) {
        jdbc.update("INSERT INTO analytics_page_views(view_date, page_path, view_count) VALUES (?, ?, ?)",
                date, "/analytics-test-" + suffix, count);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
