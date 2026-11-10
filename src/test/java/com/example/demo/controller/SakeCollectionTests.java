package com.example.demo.controller;

import com.example.demo.model.Sake;
import com.example.demo.model.SakePage;
import com.example.demo.service.SakeCatalogService;
import com.example.demo.service.SakePageService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SakeCollectionTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SakeCatalogService catalog;
    @Autowired SakePageService pages;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Test void keepsMembersOnlyRoutesBehindLogin() throws Exception {
        for (String path : List.of("/ranking", "/pairings", "/goods")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
    }

    @Test void publicCollectionsUseRegisteredValuesInLinks() throws Exception {
        String typeName = "テスト酒種" + suffix;
        String tagName = "テストタグ" + suffix;
        String breweryName = "テスト酒蔵 & " + suffix;
        long typeId = type(typeName);
        jdbc.update("INSERT INTO breweries(name, prefecture) VALUES (?, ?)", breweryName, "新潟県");
        long breweryId = lastId();
        long sakeId = sake("テスト銘柄" + suffix, typeId, breweryId);
        jdbc.update("INSERT INTO tags(name, category) VALUES (?, ?)", tagName, "味わい");
        jdbc.update("INSERT INTO sake_tags(sake_id, tag_id, score) VALUES (?, ?, 1)", sakeId, lastId());

        mvc.perform(get("/categories")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/search?type=" + encode(typeName))));
        mvc.perform(get("/tags")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/search?taste=" + encode(tagName))));
        mvc.perform(get("/brewery-map")).andExpect(status().isOk())
                .andExpect(content().string(containsString("テスト酒蔵 &amp; " + suffix)))
                .andExpect(content().string(containsString("api=1&amp;query=" + encode("新潟県 " + breweryName))));
    }

    @Test void rankingIgnoresPrivateReviewsAndBreaksTiesByPublicCount() throws Exception {
        long typeId = type("テスト酒種" + suffix);
        long onePublic = sake("公開1件" + suffix, typeId, null);
        long twoPublic = sake("公開2件" + suffix, typeId, null);
        long threePublic = sake("公開3件" + suffix, typeId, null);
        long privateOnly = sake("非公開のみ" + suffix, typeId, null);
        long firstUser = insertUser("first");
        long secondUser = insertUser("second");
        long thirdUser = insertUser("third");
        review(firstUser, onePublic, 5, true);
        review(secondUser, onePublic, 1, false);
        review(firstUser, twoPublic, 5, true);
        review(secondUser, twoPublic, 5, true);
        review(firstUser, threePublic, 4, true);
        review(secondUser, threePublic, 4, true);
        review(thirdUser, threePublic, 4, true);
        review(firstUser, privateOnly, 5, false);

        var firstPage = catalog.ranking(0);
        // 他のテストや開発用レビューの順位に依存せず、今回の銘柄同士を比較する。
        var items = IntStream.range(0, firstPage.getTotalPages()).boxed()
                .flatMap(page -> (page == 0 ? firstPage : catalog.ranking(page)).stream()).toList();
        assertThat(items).extracting(Sake::id).containsSubsequence(twoPublic, onePublic, threePublic).doesNotContain(privateOnly);
        var item = items.stream().filter(sake -> sake.id() == onePublic).findFirst().orElseThrow();
        assertThat(item.averageRating()).isEqualTo(5.0);
        assertThat(item.reviewCount()).isEqualTo(1);
        var fixtureIds = List.of(onePublic, twoPublic, threePublic);
        doReturn(new PageImpl<>(items.stream().filter(sake -> fixtureIds.contains(sake.id())).toList()))
                .when(catalog).ranking(0);
        String html = mvc.perform(get("/ranking").with(user("reader"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("公開レビューランキング")))
                .andExpect(content().string(containsString("平均 5.00 / 5")))
                .andExpect(content().string(not(containsString("好み診断で銘柄を探す"))))
                .andExpect(content().string(not(containsString("非公開のみ" + suffix))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).containsPattern("平均 5\\.00 / 5・1 件</span>\\s*<span class=\"badge\">参考評価</span>")
                .containsPattern("平均 5\\.00 / 5・2 件</span>\\s*<span class=\"badge\">参考評価</span>")
                .containsPattern("平均 4\\.00 / 5・3 件</span>\\s*</p>");
        mvc.perform(get("/ranking").with(user("reader")).param("page", Integer.toString(Integer.MAX_VALUE)))
                .andExpect(status().isOk());
    }

    @Test void emptyRankingLinksToSearchAndDiagnosis() throws Exception {
        doReturn(Page.<Sake>empty()).when(catalog).ranking(0);

        mvc.perform(get("/ranking").with(user("reader"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("公開レビューが登録されると、ここに順位が表示されます。")))
                .andExpect(content().string(containsString("class=\"button primary\" href=\"/search\">日本酒をさがす")))
                .andExpect(content().string(containsString("href=\"/diagnosis\">好み診断で銘柄を探す")));
    }

    @Test void pairingPageUsesSavedFoodAndGoodsLinksToManufacturer() throws Exception {
        String name = "料理登録のテスト銘柄" + suffix;
        long sakeId = sake(name, type("テスト酒種" + suffix), null);
        pages.save(new SakePage(sakeId, name, "", "", null, null, null, null, List.of("焼き魚"), null,
                "", null, null));

        mvc.perform(get("/pairings").with(user("reader"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/sake/" + sakeId + "\"")))
                .andExpect(content().string(containsString("焼き魚")));
        mvc.perform(get("/goods").with(user("reader"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("https://onlineshop.toyo.sasaki.co.jp/products/detail/1824")))
                .andExpect(content().string(containsString("https://www.nousaku.co.jp/product/recommend/")));
    }

    private long type(String name) {
        jdbc.update("INSERT INTO sake_types(name) VALUES (?)", name);
        return lastId();
    }

    private long sake(String name, long typeId, Long breweryId) {
        jdbc.update("INSERT INTO sake(name, sake_type_id, brewery_id) VALUES (?, ?, ?)", name, typeId, breweryId);
        return lastId();
    }

    private long insertUser(String name) {
        jdbc.update("INSERT INTO users(name, email, password_hash) VALUES (?, ?, 'unused-test-hash')",
                name, name + "-" + suffix + "@example.invalid");
        return lastId();
    }

    private void review(long userId, long sakeId, int rating, boolean published) {
        jdbc.update("INSERT INTO sake_reviews(user_id, sake_id, rating, published) VALUES (?, ?, ?, ?)",
                userId, sakeId, rating, published);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
