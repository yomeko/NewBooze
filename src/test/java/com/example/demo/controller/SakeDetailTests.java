package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.SakeInteractionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * アプリとDBを使い、銘柄の検索・詳細・レビュー・お気に入りの一連の動作を確認するテスト。
 * MockMvcでブラウザの操作を再現し、HTML・公開範囲・ページ移動・保存結果を確かめる。
 * {@code @Transactional}が、このテスト内で行ったDBの変更を終了時に取り消す。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SakeDetailTests {
    @Test void unifiedTasteDropdownFiltersProductsAndKeepsSelection() throws Exception {
        String html = mvc.perform(get("/search").param("taste", "辛口"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("八海山 特別本醸造")))
            .andExpect(content().string(not(containsString("獺祭 純米大吟醸45"))))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("<select id=\"search-taste\" name=\"taste\">",
                "value=\"フルーティー\"", "value=\"甘口\"")
            .doesNotContain("id=\"search-keyword\"")
            .containsPattern("<option[^>]*value=\"辛口\"[^>]*selected=\"selected\"");
        for (String taste : java.util.List.of("甘口", "フルーティー")) {
            mvc.perform(get("/search").param("taste", taste))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("獺祭 純米大吟醸45")))
                .andExpect(content().string(not(containsString("八海山 特別本醸造"))));
        }
    }

    // 入力表記の違い、詳細ページのリンク、未登録タグの検索結果をまとめて確認する。
    @Test void hashtagsSearchSharedDiagnosisTags() throws Exception {
        for (String keyword : java.util.List.of("#辛口", "＃辛口", "辛口")) {
            mvc.perform(get("/search").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("八海山 特別本醸造")))
                .andExpect(content().string(not(containsString("獺祭 純米大吟醸45"))));
        }
        mvc.perform(get("/sake/8"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("#辛口</a>")));
        mvc.perform(get("/search").param("keyword", "#存在しないタグ"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("条件に合う日本酒が見つかりませんでした。")));
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SakeInteractionService interactions;
    @Autowired com.example.demo.service.SakeCatalogService catalog;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private CustomUserDetails account() {
        User user = new User();
        user.setName("ページ検証");
        user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setPasswordHash("{noop}unused-test-password");
        return new CustomUserDetails(users.saveAndFlush(user));
    }

    // 商品写真が未登録でもJSONの紹介情報が表示され、空の項目は省かれることを確認する。
    @Test void rendersJsonWithoutProductPhotosAndHidesMissingSections() throws Exception {
        jdbc.update("UPDATE sake SET image_url = NULL WHERE id = 1");
        mvc.perform(get("/sake/1")).andExpect(status().isOk())
            .andExpect(content().string(containsString("株式会社 獺祭")))
            .andExpect(content().string(not(containsString("2,475円（税込）"))))
            .andExpect(content().string(not(containsString("価格・購入先"))))
            .andExpect(content().string(containsString("href=\"https://www.dassaistore.com/product-detail/47\"")))
            .andExpect(content().string(not(containsString("href=\"#purchase\""))))
            .andExpect(content().string(not(containsString("class=\"sake-product-photo\""))))
            .andExpect(content().string(containsString("class=\"logo-mark\"")))
            .andExpect(content().string(containsString("class=\"sake-home-link\"")))
            .andExpect(content().string(not(containsString("合う料理"))));
        mvc.perform(get("/sake/2")).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("どんな味わい？"))))
            .andExpect(content().string(not(containsString("価格・購入先"))));
        mvc.perform(get("/sake/9223372036854775807")).andExpect(status().isNotFound());
    }

    // 写真・購入先・お気に入りが同じ商品操作欄に配置されることを確認する。
    @Test void purchaseAndFavoriteStayInsideProductSidebar() throws Exception {
        for (var request : java.util.List.of(get("/sake/1"), get("/sake/1").with(user(account())))) {
            String html = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            int start = html.indexOf("<div class=\"sake-product-sidebar\"");
            int end = html.indexOf("<div class=\"sake-product-details\"");
            assertThat(start).isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(start);
            String sidebar = html.substring(start, end);
            assertThat(sidebar).contains("sake-header-actions", "sake-favorite",
                    "https://www.dassaistore.com/product-detail/47");
            assertThat(sidebar.indexOf("sake-favorite"))
                    .isLessThan(sidebar.indexOf("https://www.dassaistore.com/product-detail/47"));
            assertThat(html).doesNotContain("sake-purchase-bar");
        }
    }

    // レビューの保存と更新、入力したHTMLの文字表示、非公開設定を確認する。
    @Test void reviewPersistsUpdatesEscapesTextAndIsPrivate() throws Exception {
        var owner = account();
        var other = account();
        mvc.perform(post("/mypage/sake/1/review").with(user(owner)).with(csrf())
            .param("rating", "4").param("comment", "<script>private</script>"))
            .andExpect(redirectedUrl("/sake/1#review"));
        assertThat(interactions.review(owner.getUserId(), 1).orElseThrow().rating()).isEqualTo(4);
        mvc.perform(get("/sake/1").with(user(owner))).andExpect(status().isOk())
            .andExpect(content().string(containsString("&lt;script&gt;private&lt;/script&gt;")))
            .andExpect(content().string(not(containsString("<script>private</script>"))));
        mvc.perform(get("/sake/1").with(user(other)))
            .andExpect(content().string(not(containsString("private&lt;/script&gt;"))));
        mvc.perform(post("/mypage/sake/1/review").with(user(owner)).with(csrf())
            .param("rating", "5").param("comment", "更新"));
        assertThat(interactions.review(owner.getUserId(), 1).orElseThrow().comment()).isEqualTo("更新");
        mvc.perform(post("/mypage/sake/1/review/delete").with(user(other)).with(csrf()));
        assertThat(interactions.review(owner.getUserId(), 1)).isPresent();
        mvc.perform(post("/mypage/sake/1/review/delete").with(user(owner)).with(csrf()));
        assertThat(interactions.review(owner.getUserId(), 1)).isEmpty();
    }

    // 星とコメントの入力チェック、およびログインとCSRFトークンが必要なことを確認する。
    @Test void validatesReviewsAndRequiresAuthenticationAndCsrf() throws Exception {
        var owner = account();
        mvc.perform(post("/mypage/sake/1/review").with(user(owner)).with(csrf())
            .param("rating", "6")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/mypage/sake/1/review").with(user(owner)).with(csrf())
            .param("rating", "3").param("comment", "あ".repeat(501)))
            .andExpect(flash().attributeExists("error"));
        assertThat(interactions.review(owner.getUserId(), 1)).isEmpty();
        mvc.perform(post("/mypage/sake/1/review").with(user(owner)).param("rating", "3"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/mypage/sake/1/review").with(csrf()).param("rating", "3"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    // 公開レビューの表示と並び替え、非公開へ変更した後の非表示を確認する。
    @Test void publicReviewsAreVisibleSortedAndCanBecomePrivate() throws Exception {
        var high = account();
        var low = account();
        mvc.perform(post("/mypage/sake/1/review").with(user(high)).with(csrf())
            .param("rating", "5").param("comment", "<script>公開感想</script>").param("published", "true"))
            .andExpect(status().is3xxRedirection());
        interactions.saveReview(low.getUserId(), 1, 2, "低い評価", true);
        var desc = interactions.publicReviews(1, "ratingDesc", 0);
        assertThat(desc.content().getFirst().rating()).isEqualTo(5);
        assertThat(interactions.publicReviews(1, "ratingAsc", 0).content().getFirst().rating()).isEqualTo(2);
        mvc.perform(get("/sake/1").param("reviewSort", "ratingDesc"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("&lt;script&gt;公開感想&lt;/script&gt;")))
            .andExpect(content().string(not(containsString("<script>公開感想</script>"))));
        // Form submission allows publication to be revoked by omitting the checkbox.
        mvc.perform(post("/mypage/sake/1/review").with(user(high)).with(csrf())
            .param("rating", "5").param("comment", "非公開に変更"));
        assertThat(interactions.publicReviews(1, "newest", 0).total()).isEqualTo(desc.total() - 1);
        mvc.perform(get("/sake/1")).andExpect(content().string(not(containsString("非公開に変更"))));
    }

    // 公開レビューの投稿者画像と表示位置が使われ、公開状態に応じて画像を返すことを確認する。
    @Test void reviewAvatarUsesAuthorsImageAndCropAndRespectsPublication() throws Exception {
        var owner = account();
        var other = account();
        byte[] image = new byte[] {1, 2, 3};
        jdbc.update("INSERT INTO user_profile_images (user_id, image_data, content_type, position_x, position_y, zoom) VALUES (?, ?, ?, ?, ?, ?)",
                owner.getUserId(), image, "image/png", 25, 75, 150);
        interactions.saveReview(owner.getUserId(), 1, 4, "画像付き", true);
        interactions.saveReview(other.getUserId(), 1, 3, "画像なし", true);
        String imageUrl = "/sake/reviewers/" + owner.getUserId() + "/profile-image";
        mvc.perform(get("/sake/1"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("src=\"" + imageUrl + "\"")))
            .andExpect(content().string(containsString("object-position:25% 75%;transform:scale(1.5)")))
            .andExpect(content().string(not(containsString("src=\"/sake/reviewers/" + other.getUserId()))));
        mvc.perform(get(imageUrl)).andExpect(status().isOk())
            .andExpect(content().contentType("image/png")).andExpect(content().bytes(image));
        mvc.perform(get("/sake/reviewers/" + other.getUserId() + "/profile-image"))
            .andExpect(status().isNotFound());
        interactions.saveReview(owner.getUserId(), 1, 4, "非公開", false);
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
    }

    // レビューのページ番号と未知の並び順を、使える範囲へ補正することを確認する。
    @Test void reviewPaginationAndSortFallbackAreBounded() throws Exception {
        // Isolate public-review fixtures; transaction rollback restores preexisting data.
        jdbc.update("UPDATE sake_reviews SET published = 0 WHERE sake_id = 2");
        for (int i = 0; i < 12; i++) {
            var owner = account();
            interactions.saveReview(owner.getUserId(), 2, i % 5 + 1, "感想" + i, true);
            jdbc.update("UPDATE sake_reviews SET updated_at = ? WHERE user_id = ? AND sake_id = 2",
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 1, 1, 0, i)), owner.getUserId());
        }
        var first = interactions.publicReviews(2, "newest", 0);
        assertThat(first.total()).isEqualTo(12);
        assertThat(first.content()).hasSize(10);
        assertThat(first.content().getFirst().comment()).isEqualTo("感想11");
        var last = interactions.publicReviews(2, "ratingAsc", Integer.MAX_VALUE);
        assertThat(last.content()).hasSize(2);
        assertThat(last.pageNumber()).isEqualTo(1);
        assertThat(interactions.publicReviews(2, "rating; DROP TABLE users", -1).sort()).isEqualTo("newest");
        mvc.perform(get("/sake/2").param("reviewSort", "ratingAsc"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("reviewPage=1")));
        mvc.perform(get("/sake/2").param("reviewPage", "1").param("reviewSort", "ratingAsc"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("reviewPage=0")));
    }

    // 検索のページ分割前に並び順を決め、非公開評価を平均へ含めないことを確認する。
    @Test void catalogSortsAllResultsBeforePaginationAndExcludesPrivateRatings() throws Exception {
        jdbc.update("UPDATE sake_reviews SET published = 0");
        var one = account();
        var two = account();
        interactions.saveReview(one.getUserId(), 1, 5, "", true);
        interactions.saveReview(one.getUserId(), 2, 2, "", true);
        interactions.saveReview(two.getUserId(), 2, 4, "", true);
        interactions.saveReview(one.getUserId(), 3, 5, "private", false);
        entityManager.clear();
        var desc = catalog.search("", "", "", null, null, "", "ratingDesc", 0);
        assertThat(desc.content().getFirst().id()).isEqualTo(1);
        assertThat(desc.content().get(1).id()).isEqualTo(2);
        assertThat(desc.content().get(1).averageRating()).isEqualTo(3.0);
        assertThat(desc.content().get(1).reviewCount()).isEqualTo(2);
        var asc = catalog.search("", "", "", null, null, "", "ratingAsc", 0);
        assertThat(asc.content().getFirst().id()).isEqualTo(2);
        assertThat(asc.content().get(1).id()).isEqualTo(1);
        assertThat(catalog.search("", "", "", null, null, "", "reviewCount", 0).content().getFirst().id()).isEqualTo(2);
        assertThat(catalog.search("", "新政", "", null, null, "", "ratingDesc", 0).totalElements()).isEqualTo(1);
        mvc.perform(get("/search").param("sort", "ratingDesc"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("★ 5.0 / 5（1件）")));
        mvc.perform(get("/search").param("sort", "ratingAsc").param("page", "1"))
            .andExpect(status().isOk());
    }

    // 詳細画面からお気に入りを登録・解除できることを確認する。
    @Test void favoritesCanBeAddedAndRemovedFromDetail() throws Exception {
        var owner = account();
        mvc.perform(post("/mypage/sake/1/favorite").with(user(owner)).with(csrf()).param("selected", "true"))
            .andExpect(redirectedUrl("/sake/1"));
        assertThat(interactions.isFavorite(owner.getUserId(), 1)).isTrue();
        mvc.perform(get("/sake/1").with(user(owner))).andExpect(status().isOk())
            .andExpect(content().string(containsString("お気に入り登録済み（解除）")));
        mvc.perform(post("/mypage/sake/1/favorite").with(user(owner)).with(csrf()).param("selected", "false"));
        assertThat(interactions.isFavorite(owner.getUserId(), 1)).isFalse();
    }
}
