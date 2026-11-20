package com.example.demo.controller;

import com.example.demo.service.AnalyticsService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実際の管理トップを描画し、集計値・ランキング・空と障害の表示を検証する。 */
class AdminAnalyticsRenderingTests {
    private final AnalyticsService analytics = mock(AnalyticsService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SpringSecurityDialect());
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mvc = MockMvcBuilders.standaloneSetup(new AdminAnalyticsController(analytics))
                .setViewResolvers(views).build();
    }

    @Test void rendersDailyLineChartAndBothRankingsWithoutJavaScript() throws Exception {
        var today = LocalDate.of(2026, 11, 18);
        var sakes = IntStream.rangeClosed(1, 10)
                .mapToObj(id -> new AnalyticsService.SakeCount(id, "銘柄" + id, 12 - id)).toList();
        var tags = List.of(new AnalyticsService.TagCount(1, "辛口", 1500, sakes.subList(0, 5)),
                new AnalyticsService.TagCount(2, "甘口", 0, List.of()));
        var days = IntStream.range(0, 14).mapToObj(offset ->
                new AnalyticsService.DailyViews(today.minusDays(13 - offset), offset == 13 ? 200 : offset % 3 * 20,
                        offset == 13 ? 100 : 0)).toList();
        when(analytics.dashboard()).thenReturn(new AnalyticsService.Dashboard(1500, 12345, 200, sakes, tags, days));

        String html = render("/admin");
        assertThat(html).contains("12,345", "1,500回", "200 PV", "人気の日本酒トップ10", "辛口の日本酒トップ5",
                "href=\"/sake/10\"", "datetime=\"2026-11-18\"", "直近14日間のサイト閲覧数の折れ線グラフ",
                "class=\"admin-view-line\"", "cx=\"584.0\" cy=\"24.0\"", "横軸は日付、縦軸はPVです。",
                "日別の閲覧数を確認", "<td>200 PV</td>",
                "このタグの公式サイトへの遷移はまだありません。", "タグの合計は全体の遷移数を上回ることがあります。");
        assertThat(occurrences(html, "class=\"admin-view-point\"")).isEqualTo(14);
        assertThat(occurrences(html, "class=\"admin-chart-label admin-chart-date\"")).isEqualTo(5);
        assertThat(occurrences(html, "class=\"admin-tag-ranking\"")).isEqualTo(2);
        assertThat(occurrences(html, "class=\"admin-tag-ranking\" open=\"open\"")).isEqualTo(1);
        assertThat(occurrences(html, "href=\"/sake/1\"")).isEqualTo(2);
        assertThat(occurrences(html, "href=\"/sake/6\"")).isEqualTo(1);
        assertThat(html).doesNotContain("集計情報を取得できませんでした", "admin-view-bar", "admin-view-track");
    }

    @Test void zeroCountsHaveAnExplanationAndNamesAreEscaped() throws Exception {
        var tag = new AnalyticsService.TagCount(1, "<script>alert(1)</script>", 0, List.of());
        when(analytics.dashboard()).thenReturn(new AnalyticsService.Dashboard(0, 0, 0, List.of(), List.of(tag),
                List.of(new AnalyticsService.DailyViews(LocalDate.of(2026, 11, 18), 0, 0))));
        assertThat(render("/admin/"))
                .contains("まだ公式サイトへの遷移がありません。", "&lt;script&gt;alert(1)&lt;/script&gt;", "0 PV", "cy=\"208.0\"")
                .doesNotContain("<script>alert(1)</script>", "NaN", "Infinity");
    }

    @Test void unavailableAnalyticsKeepManagementLinksAndDoNotPretendToBeZero() throws Exception {
        when(analytics.dashboard()).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(render("/admin"))
                .contains("集計情報を取得できませんでした", "href=\"/admin/sake/new\"", "href=\"/admin/users\"")
                .doesNotContain("class=\"admin-stat-grid\"", "class=\"admin-view-chart\"");
    }

    private String render(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private long occurrences(String text, String value) {
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(value)).matcher(text).results().count();
    }
}
