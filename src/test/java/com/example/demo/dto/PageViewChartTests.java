package com.example.demo.dto;

import com.example.demo.service.AnalyticsService.DailyViews;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.assertj.core.api.Assertions.*;

class PageViewChartTests {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 7);

    @Test void fourteenZeroDaysStayOnTheBaselineWithFiniteCoordinates() {
        var days = IntStream.range(0, 14).mapToObj(index -> new DailyViews(FIRST_DAY.plusDays(index), 0, 0)).toList();
        var chart = PageViewChart.from(days);

        assertThat(chart.maximum()).isEqualTo(1);
        assertThat(chart.points()).hasSize(14);
        assertThat(chart.points().getFirst().x()).isEqualTo(56);
        assertThat(chart.points().getLast().x()).isEqualTo(584);
        assertThat(chart.points()).allSatisfy(point -> {
            assertThat(point.count()).isZero();
            assertThat(point.y()).isEqualTo(208);
            assertThat(Double.isFinite(point.x())).isTrue();
        });
        assertThat(chart.ticks()).extracting(PageViewChart.Tick::value).containsExactly(0L, 1L);
        assertThat(chart.polyline().split(" ")).hasSize(14);
    }

    @Test void rawCountsDetermineHeightInsteadOfRoundedPercentages() {
        var chart = PageViewChart.from(List.of(new DailyViews(FIRST_DAY, 0, 100),
                new DailyViews(FIRST_DAY.plusDays(1), 1, 1),
                new DailyViews(FIRST_DAY.plusDays(2), 2000, 0)));

        assertThat(chart.maximum()).isEqualTo(2000);
        assertThat(chart.points()).extracting(PageViewChart.Point::count).containsExactly(0L, 1L, 2000L);
        assertThat(chart.points().get(0).y()).isEqualTo(208);
        assertThat(chart.points().get(1).y()).isCloseTo(207.908, within(0.0000001));
        assertThat(chart.points().get(2).y()).isEqualTo(24);
        assertThat(chart.points()).extracting(PageViewChart.Point::x).containsExactly(56.0, 320.0, 584.0);
        assertThat(chart.points()).extracting(PageViewChart.Point::index).containsExactly(0, 1, 2);
        assertThat(chart.points()).extracting(PageViewChart.Point::date)
                .containsExactly(FIRST_DAY, FIRST_DAY.plusDays(1), FIRST_DAY.plusDays(2));
    }

    @Test void integerTickLabelsHaveTheSameScaleAsTheData() {
        var chart = PageViewChart.from(List.of(new DailyViews(FIRST_DAY, 7, 100)));

        assertThat(chart.ticks()).extracting(PageViewChart.Tick::value).containsExactly(0L, 3L, 7L);
        assertThat(chart.ticks().get(0).y()).isEqualTo(208);
        assertThat(chart.ticks().get(1).y()).isCloseTo(129.14285714285714, within(0.0000001));
        assertThat(chart.ticks().get(2).y()).isEqualTo(24);
    }

    @Test void emptyAndSingleDayInputsNeverDivideByZero() {
        var empty = PageViewChart.from(List.of());
        assertThat(empty.maximum()).isEqualTo(1);
        assertThat(empty.points()).isEmpty();
        assertThat(empty.polyline()).isEmpty();
        assertThat(empty.ticks()).allSatisfy(tick -> assertThat(Double.isFinite(tick.y())).isTrue());

        var single = PageViewChart.from(List.of(new DailyViews(FIRST_DAY, 1, 100)));
        assertThat(single.points()).containsExactly(new PageViewChart.Point(0, FIRST_DAY, 1, 56, 24));
        assertThat(single.polyline()).isEqualTo("56.00,24.00");
        assertThat(single.ticks()).extracting(PageViewChart.Tick::value).containsExactly(0L, 1L);
    }

    @Test void largeCountsDoNotOverflowTheScaleOrCoordinates() {
        var chart = PageViewChart.from(List.of(new DailyViews(FIRST_DAY, 0, 0),
                new DailyViews(FIRST_DAY.plusDays(1), Long.MAX_VALUE, 100)));

        assertThat(chart.maximum()).isEqualTo(Long.MAX_VALUE);
        assertThat(chart.points()).extracting(PageViewChart.Point::y).containsExactly(208.0, 24.0);
        assertThat(chart.ticks()).extracting(PageViewChart.Tick::value)
                .containsExactly(0L, Long.MAX_VALUE / 2, Long.MAX_VALUE);
        assertThat(chart.ticks()).allSatisfy(tick -> {
            assertThat(Double.isFinite(tick.y())).isTrue();
            assertThat(tick.y()).isBetween(24.0, 208.0);
        });
        assertThat(chart.polyline()).doesNotContain("NaN", "Infinity");
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    void polylineUsesDecimalPointsRegardlessOfTheDisplayLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var chart = PageViewChart.from(List.of(new DailyViews(FIRST_DAY, 1, 14),
                    new DailyViews(FIRST_DAY.plusDays(1), 7, 100)));
            assertThat(chart.polyline()).isEqualTo("56.00,181.71 584.00,24.00");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test void chartListsCannotBeChangedByTheCaller() {
        var days = new ArrayList<>(List.of(new DailyViews(FIRST_DAY, 4, 100)));
        var chart = PageViewChart.from(days);
        days.clear();

        assertThat(chart.points()).hasSize(1);
        assertThatThrownBy(() -> chart.points().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> chart.ticks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void negativeCountsAreRejectedInsteadOfDrawingOutsideTheAxis() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                PageViewChart.from(List.of(new DailyViews(FIRST_DAY, -1, 0))));
    }
}
