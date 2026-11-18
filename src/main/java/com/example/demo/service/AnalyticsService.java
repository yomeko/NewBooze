package com.example.demo.service;

import com.example.demo.repository.AnalyticsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理者トップで使う公式リンクの人気集計と、日本時間の日別PVを用意する。 */
@Service
public class AnalyticsService {
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final int CHART_DAYS = 14;
    private final AnalyticsRepository analytics;
    private final Clock clock;

    @Autowired
    public AnalyticsService(AnalyticsRepository analytics) {
        this(analytics, Clock.system(JAPAN));
    }

    public AnalyticsService(AnalyticsRepository analytics, Clock clock) {
        this.analytics = analytics;
        this.clock = clock.withZone(JAPAN);
    }

    public record Dashboard(long totalClicks, long totalViews, long todayViews, List<SakeCount> topSakes,
            List<TagCount> tags, List<DailyViews> dailyViews) {}
    public record SakeCount(long sakeId, String name, long count) {}
    public record TagCount(long tagId, String name, long count, List<SakeCount> topSakes) {}
    public record DailyViews(LocalDate date, long count, int percentage) {}

    /** 銘柄とその時点の属性への加算を、同じトランザクションで確定する。 */
    @Transactional
    public void recordOfficialClick(long sakeId) {
        if (sakeId <= 0) throw new IllegalArgumentException("日本酒IDは正の整数にしてください");
        analytics.incrementOfficialClick(sakeId);
    }

    /** 保存するのはクエリーを除いたページのパスと日別件数だけ。会員IDやIPは記録しない。 */
    @Transactional
    public void recordPageView(String pagePath) {
        if (pagePath == null || !pagePath.startsWith("/") || pagePath.length() > 255
                || pagePath.contains("?") || pagePath.contains("#"))
            throw new IllegalArgumentException("集計するページのパスを指定してください");
        analytics.incrementPageView(LocalDate.now(clock), pagePath);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        LocalDate today = LocalDate.now(clock);
        Map<Long, List<SakeCount>> byTag = analytics.topSakesByTag().stream().collect(Collectors.groupingBy(
                AnalyticsRepository.TaggedSakeCount::tagId, LinkedHashMap::new,
                Collectors.mapping(item -> new SakeCount(item.sakeId(), item.name(), item.count()), Collectors.toList())));
        List<TagCount> tags = analytics.tagTotals().stream()
                .map(tag -> new TagCount(tag.tagId(), tag.name(), tag.count(),
                        List.copyOf(byTag.getOrDefault(tag.tagId(), List.of())))).toList();
        List<SakeCount> topSakes = analytics.topSakes().stream()
                .map(item -> new SakeCount(item.sakeId(), item.name(), item.count())).toList();
        LocalDate firstDate = today.minusDays(CHART_DAYS - 1);
        Map<LocalDate, Long> counts = analytics.dailyViews(firstDate, today).stream()
                .collect(Collectors.toMap(AnalyticsRepository.DailyCount::date, AnalyticsRepository.DailyCount::count));
        long maximum = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<DailyViews> dailyViews = firstDate.datesUntil(today.plusDays(1)).map(date -> {
            long count = counts.getOrDefault(date, 0L);
            int percentage = count == 0 || maximum == 0 ? 0 : Math.max(1, (int) Math.round(100.0 * count / maximum));
            return new DailyViews(date, count, percentage);
        }).toList();
        return new Dashboard(analytics.totalClicks(), analytics.totalViews(), analytics.viewsOn(today),
                topSakes, tags, dailyViews);
    }
}
