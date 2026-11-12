package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.OutboundClickService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公式リンクのクリック記録・本人との紐付け・管理者だけの集計を実DBで確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OutboundClickTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired OutboundClickService clicks;

    private CustomUserDetails account(boolean admin) {
        User account = new User();
        account.setName("クリック検証" + UUID.randomUUID().toString().substring(0, 8));
        account.setEmail(UUID.randomUUID() + "@example.invalid");
        account.setPasswordHash(admin ? encoder.encode("click-TestPassword-4!") : "{noop}unused-test-password");
        account.setAdmin(admin);
        account.setEmailVerified(true);
        return new CustomUserDetails(users.saveAndFlush(account));
    }

    private long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_clicks", Long.class);
    }

    // 会員登録なしでも記録でき、同じリンクを2回押した場合も2件になる。
    @Test void anonymousRepeatedClicksAreRecordedBeforeRedirectingToOfficialPurchaseUrl() throws Exception {
        long before = count();
        var summary = clicks.summary();
        var started = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Tokyo")).minusSeconds(1);
        for (int index = 0; index < 2; index++) {
            mvc.perform(post("/sake/outbound").with(csrf())
                    .param("source", "detail").param("target", "1").param("kind", "purchase"))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", "https://www.dassaistore.com/product-detail/47"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }
        assertThat(count()).isEqualTo(before + 2);
        var latest = jdbc.queryForMap("SELECT * FROM outbound_clicks ORDER BY id DESC LIMIT 1");
        assertThat(latest.get("user_id")).isNull();
        assertThat(((Number) latest.get("sake_id")).longValue()).isEqualTo(1);
        assertThat(latest.get("source")).isEqualTo("detail");
        assertThat(latest.get("link_kind")).isEqualTo("purchase");
        assertThat(latest.get("destination_url")).isEqualTo("https://www.dassaistore.com/product-detail/47");
        assertThat(latest.get("clicked_at")).isNotNull();
        java.time.LocalDateTime recordedAt = jdbc.queryForObject(
                "SELECT clicked_at FROM outbound_clicks ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getObject(1, java.time.LocalDateTime.class));
        assertThat(recordedAt).isBetween(started, java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Tokyo")));
        assertThat(clicks.summary().total()).isEqualTo(summary.total() + 2);
        assertThat(clicks.summary().anonymous()).isEqualTo(summary.anonymous() + 2);
        assertThat(clicks.summary().loggedIn()).isEqualTo(summary.loggedIn());
    }

    // 入力された会員IDや移動先URLではなく、ログイン本人と登録済みURLを使用する。
    @Test void authenticatedClicksBelongToEachPrincipalAndIgnoreForgedAccountAndDestination() throws Exception {
        var first = account(false);
        var second = account(false);
        var summary = clicks.summary();
        for (var principal : java.util.List.of(first, first, second)) {
            mvc.perform(post("/sake/outbound").with(user(principal)).with(csrf())
                    .param("source", "detail").param("target", "1").param("kind", "official")
                    .param("userId", second.getUserId().toString()).param("url", "https://attacker.invalid/"))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", "https://dassai.com/product/main/45.html"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbound_clicks WHERE user_id = ?",
                Long.class, first.getUserId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbound_clicks WHERE user_id = ?",
                Long.class, second.getUserId())).isEqualTo(1);
        assertThat(clicks.summary().loggedIn()).isEqualTo(summary.loggedIn() + 3);
        assertThat(clicks.summary().anonymous()).isEqualTo(summary.anonymous());
        var counts = clicks.accounts(0).content();
        assertThat(counts).anySatisfy(row -> {
            assertThat(row.userId()).isEqualTo(first.getUserId());
            assertThat(row.clicks()).isEqualTo(2);
            assertThat(row.lastClickedAt()).isNotNull();
        });
    }

    @Test void homeAndGuideResolveOnlyTheirConfiguredReferences() throws Exception {
        long before = count();
        mvc.perform(post("/sake/outbound").with(csrf()).param("source", "home")
                .param("target", "獺祭 純米大吟醸45").param("kind", "product"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://dassai.com/product/main/45.html"));
        mvc.perform(post("/sake/outbound").with(csrf()).param("source", "home")
                .param("target", "出羽桜 桜花吟醸").param("kind", "retailer"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://www.dewazakura.co.jp/shop/"));
        mvc.perform(post("/sake/outbound").with(csrf()).param("source", "guide")
                .param("target", "dassai").param("kind", "retailer"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://dassai.com/dealer/"));
        assertThat(count()).isEqualTo(before + 3);
    }

    @Test void purchaseFallsBackToOfficialUrlAndUsesSavedPageDetails() throws Exception {
        var page = new com.example.demo.model.SakePage(2, "新政 No.6", "", "", null, null,
                null, null, null, null, "https://example.invalid/official", null, null);
        jdbc.update("DELETE FROM sake_page_details WHERE sake_id = 2");
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (?, ?)", 2,
                new tools.jackson.databind.ObjectMapper().writeValueAsString(page));
        mvc.perform(post("/sake/outbound").with(csrf()).param("source", "detail")
                .param("target", "2").param("kind", "purchase"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://example.invalid/official"));
    }

    @Test void invalidReferencesMissingUrlsAndCsrfDoNotCreateClicks() throws Exception {
        long before = count();
        for (String[] input : new String[][] {
                {"detail", "9223372036854775807", "official"},
                {"detail", "not-a-number", "official"},
                {"detail", "1", "javascript"},
                {"home", "不存在の銘柄", "product"},
                {"home", "獺祭 純米大吟醸45", "official"},
                {"guide", "https://attacker.invalid/", "retailer"},
                {"unknown", "1", "official"}}) {
            mvc.perform(post("/sake/outbound").with(csrf()).param("source", input[0])
                    .param("target", input[1]).param("kind", input[2]))
                    .andExpect(status().isNotFound());
        }
        var empty = new com.example.demo.model.SakePage(2, "新政 No.6", "", "", null, null,
                null, null, null, null, null, null, null);
        jdbc.update("DELETE FROM sake_page_details WHERE sake_id = 2");
        jdbc.update("INSERT INTO sake_page_details (sake_id, detail_json) VALUES (?, ?)", 2,
                new tools.jackson.databind.ObjectMapper().writeValueAsString(empty));
        mvc.perform(post("/sake/outbound").with(csrf()).param("source", "detail")
                .param("target", "2").param("kind", "purchase")).andExpect(status().isNotFound());
        mvc.perform(post("/sake/outbound").param("source", "detail")
                .param("target", "1").param("kind", "purchase")).andExpect(status().isForbidden());
        assertThat(count()).isEqualTo(before);
    }

    // 表示やリンクの先読みだけでは増やさず、未ログインにもCSRF付き操作を用意する。
    @Test void renderingAndGetRequestsDoNotCountAndFormsWorkWithoutJavascript() throws Exception {
        long before = count();
        for (String path : java.util.List.of("/", "/guide", "/sake/1")) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("action=\"/sake/outbound\"")))
                    .andExpect(content().string(containsString("name=\"_csrf\"")))
                    .andExpect(content().string(containsString("target=\"_blank\"")));
        }
        mvc.perform(get("/sake/outbound").param("source", "detail")
                .param("target", "1").param("kind", "purchase")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        assertThat(count()).isEqualTo(before);
    }

    @Test void accountDeletionAnonymizesHistoryWithoutChangingTotalsOrOriginalLoginGrouping() throws Exception {
        var principal = account(false);
        mvc.perform(post("/sake/outbound").with(user(principal)).with(csrf()).param("source", "detail")
                .param("target", "1").param("kind", "official")).andExpect(status().isSeeOther());
        var before = clicks.summary();
        long id = jdbc.queryForObject("SELECT MAX(id) FROM outbound_clicks WHERE user_id = ?",
                Long.class, principal.getUserId());
        jdbc.update("DELETE FROM users WHERE id = ?", principal.getUserId());
        assertThat(jdbc.queryForObject("SELECT user_id FROM outbound_clicks WHERE id = ?", Long.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT logged_in FROM outbound_clicks WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(clicks.summary()).isEqualTo(before);
        assertThat(clicks.accounts(0).content()).noneMatch(row -> row.userId() == principal.getUserId());
    }

    @Test void reportRequiresAdminAndPaginatesAccountCounts() throws Exception {
        mvc.perform(get("/admin/outbound-clicks")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/outbound-clicks").with(user(account(false)))).andExpect(status().isForbidden());
        var administrator = account(true);
        for (int index = 0; index < 31; index++) {
            var principal = account(false);
            jdbc.update("""
                    INSERT INTO outbound_clicks (user_id, logged_in, sake_id, target_name, source, link_kind, destination_url)
                    VALUES (?, TRUE, 1, '獺祭 純米大吟醸45', 'detail', 'official', 'https://dassai.com/product/main/45.html')
                    """, principal.getUserId());
        }
        var first = clicks.accounts(-1);
        assertThat(first.number()).isZero();
        assertThat(first.content()).hasSize(30);
        assertThat(first.hasPrevious()).isFalse();
        assertThat(first.hasNext()).isTrue();
        assertThat(clicks.accounts(1).hasPrevious()).isTrue();
        mvc.perform(get("/admin/outbound-clicks").with(user(administrator)))
                .andExpect(status().isOk()).andExpect(model().attributeExists("summary", "accounts"))
                .andExpect(content().string(containsString("アカウント別")));
    }

    // 保存できない場合に件数を増やしたことにせず、戻って再試行できる案内を出す。
    @Test void persistenceFailureShowsServiceUnavailableWithRetryInsteadOfRedirecting() throws Exception {
        var service = mock(OutboundClickService.class);
        when(service.follow("detail", "1", "purchase", null))
                .thenThrow(new DataAccessResourceFailureException("internal database detail"))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("internal connection detail"));
        var standalone = MockMvcBuilders.standaloneSetup(new OutboundClickController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            standalone.perform(post("/sake/outbound").param("source", "detail")
                    .param("target", "1").param("kind", "purchase"))
                    .andExpect(status().isServiceUnavailable()).andExpect(view().name("outbound-error"))
                    .andExpect(model().attribute("backUrl", "/sake/1"))
                    .andExpect(model().attribute("source", "detail"))
                    .andExpect(model().attribute("target", "1"))
                    .andExpect(model().attribute("kind", "purchase"))
                    .andExpect(header().doesNotExist("Location"));
        }
    }
}
