package com.example.demo.dto;

import com.example.demo.service.AnalyticsService.DailyViews;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 日別PVの実件数を、管理トップのSVG折れ線グラフ用の座標へ変換する。 */
public record PageViewChart(long maximum, List<Point> points, List<Tick> ticks, String polyline) {
    private static final double LEFT = 56;
    private static final double RIGHT = 584;
    private static final double TOP = 24;
    private static final double BASELINE = 208;

    public PageViewChart {
        points = List.copyOf(points);
        ticks = List.copyOf(ticks);
    }

    public record Point(int index, LocalDate date, long count, double x, double y) {}
    public record Tick(long value, double y) {}

    public static PageViewChart from(List<DailyViews> dailyViews) {
        List<DailyViews> days = List.copyOf(dailyViews);
        if (days.stream().anyMatch(day -> day.count() < 0))
            throw new IllegalArgumentException("閲覧数は0以上にしてください");
        long maximum = Math.max(1, days.stream().mapToLong(DailyViews::count).max().orElse(0));
        List<Point> points = IntStream.range(0, days.size()).mapToObj(index -> {
            DailyViews day = days.get(index);
            double x = days.size() <= 1 ? LEFT : LEFT + (RIGHT - LEFT) * index / (days.size() - 1);
            return new Point(index, day.date(), day.count(), x, y(day.count(), maximum));
        }).toList();
        List<Tick> ticks = java.util.stream.LongStream.of(0, maximum / 2, maximum).distinct()
                .mapToObj(value -> new Tick(value, y(value, maximum))).toList();
        String polyline = points.stream().map(point -> String.format(Locale.ROOT, "%.2f,%.2f", point.x(), point.y()))
                .collect(Collectors.joining(" "));
        return new PageViewChart(maximum, points, ticks, polyline);
    }

    private static double y(long count, long maximum) {
        // 整数の百分率を使わず、少ない日の差やLongの上限付近でも実件数の比率を保つ。
        return BASELINE - (double) count / maximum * (BASELINE - TOP);
    }
}
