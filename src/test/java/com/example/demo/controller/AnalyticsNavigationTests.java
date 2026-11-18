package com.example.demo.controller;

import com.example.demo.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AnalyticsNavigationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalyticsService analytics;

    @Test void officialAndPurchaseLinksResolveOnServerAndCountEachNavigationOnce() throws Exception {
        jdbc.update("DELETE FROM sake_page_details WHERE sake_id = 1");
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (1, ?)",
                "{\"id\":1,\"officialUrl\":\"https://brewery.example/product\",\"purchaseLinks\":["
                        + "{\"url\":\"https://official-shop.example/product\",\"official\":true}]}");
        long clicks = clicks();
        long views = views();
        mvc.perform(get("/sake/1/outbound/purchase").param("url", "https://untrusted.invalid/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://official-shop.example/product"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/sake/1/outbound/official")).andExpect(status().isFound());
        assertThat(clicks()).isEqualTo(clicks + 2);
        assertThat(views()).isEqualTo(views);
    }

    @Test void missingDestinationAndAutomaticRequestsDoNotCountClicks() throws Exception {
        long clicks = clicks();
        mvc.perform(get("/sake/9223372036854775807/outbound/official")).andExpect(status().isNotFound());
        mvc.perform(get("/sake/1/outbound/unknown")).andExpect(status().isNotFound());
        for (String purpose : new String[]{"prefetch", "prefetch;prerender"}) {
            mvc.perform(get("/sake/1/outbound/official").header("Sec-Purpose", purpose))
                    .andExpect(status().isFound());
        }
        mvc.perform(get("/sake/1/outbound/official").header("User-Agent", "Googlebot/2.1"))
                .andExpect(status().isFound());
        mvc.perform(head("/sake/1/outbound/official")).andExpect(status().isFound());
        // 登録されているが外部URLのない銘柄も404で、カウントしない。
        jdbc.update("DELETE FROM sake_page_details WHERE sake_id = 2");
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (2, ?)",
                "{\"id\":2,\"name\":\"リンクなし\"}");
        mvc.perform(get("/sake/2/outbound/official")).andExpect(status().isNotFound());
        mvc.perform(get("/sake/2/outbound/purchase")).andExpect(status().isNotFound());
        assertThat(clicks()).isEqualTo(clicks);
    }

    @Test void htmlViewsCountReloadsWithoutSavingQueriesAndExcludeAdminAuthAssetsAndErrors() throws Exception {
        long views = views();
        mvc.perform(get("/search").param("keyword", "秘密の検索語")).andExpect(status().isOk());
        mvc.perform(get("/search")).andExpect(status().isOk());
        mvc.perform(get("/sake/1")).andExpect(status().isOk());
        assertThat(views()).isEqualTo(views + 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_page_views WHERE page_path LIKE '%?%'", Long.class))
                .isZero();
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/css/admin.css")).andExpect(status().isOk());
        mvc.perform(get("/sake/9223372036854775807")).andExpect(status().isNotFound());
        mvc.perform(head("/search")).andExpect(status().isOk());
        mvc.perform(get("/search").header("Purpose", "prefetch")).andExpect(status().isOk());
        mvc.perform(get("/search").header("User-Agent", "ExampleCrawler/1.0")).andExpect(status().isOk());
        mvc.perform(get("/admin").with(user("analytics-admin").roles("ADMIN"))).andExpect(status().isOk());
        assertThat(views()).isEqualTo(views + 3);
    }

    @Test void dashboardAndTrailingSlashRequireAdministratorAndShowAnalytics() throws Exception {
        for (String path : new String[]{"/admin", "/admin/"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection());
            mvc.perform(get(path).with(user("member").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("administrator").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(view().name("admin/index"))
                    .andExpect(model().attribute("analyticsAvailable", true))
                    .andExpect(model().attributeExists("analytics"));
        }
    }

    private long clicks() { return analytics.dashboard().totalClicks(); }
    private long views() { return analytics.dashboard().totalViews(); }
}
