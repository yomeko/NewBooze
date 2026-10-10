package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.*;
import com.example.demo.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * アプリとDBを使い、管理者のログイン・アクセス権限・銘柄登録を確認するテスト。
 * MockMvcは実際のブラウザの代わりにURLへのアクセスを再現する。
 * {@code @Transactional}により、このテスト内で変更したDBの内容は終了時に取り消される。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTests {
    private static final String ADMIN_EMAIL = "admin-integration@example.test";
    private static final String ADMIN_PASSWORD = "test-only-password-2026";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SakeRepository sake;
    @Autowired SakeTypeRepository types;
    @Autowired PasswordEncoder encoder;
    @Autowired com.example.demo.service.SakeImageService images;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach void createTestAdministrator() {
        User admin = new User();
        admin.setName("admin");
        admin.setEmail(ADMIN_EMAIL);
        admin.setPasswordHash(encoder.encode(ADMIN_PASSWORD));
        admin.setAdmin(true);
        users.save(admin);
    }

    // 管理者だけが管理画面を開けて、通常ログイン後も管理画面へ進むことを確認する。
    @Test void adminLoginAndProtectedPages() throws Exception {
        User admin = users.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(encoder.matches(ADMIN_PASSWORD, admin.getPasswordHash())).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", ADMIN_EMAIL).param("password", ADMIN_PASSWORD))
                .andExpect(authenticated().withRoles("USER", "ADMIN"))
                .andExpect(redirectedUrl("/admin"));
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
        for (String path : java.util.List.of("/admin", "/admin/users", "/admin/sake/new")) {
            mvc.perform(get(path).with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user(new CustomUserDetails(admin)))).andExpect(status().isOk());
        }
    }

    // 名前検索、登録の入力チェック、権限とCSRFの確認、保存後の検索表示まで確認する。
    @Test void searchUsersAndRegisterSake() throws Exception {
        var admin = user(new CustomUserDetails(users.findByEmail(ADMIN_EMAIL).orElseThrow()));
        mvc.perform(get("/admin/users").with(admin).param("keyword", "admin"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("admin</td>")));
        mvc.perform(get("/admin/users").with(admin).param("keyword", "不存在ユーザー123"))
                .andExpect(content().string(containsString("該当するユーザーがいません")));
        long count = sake.count();
        mvc.perform(post("/admin/sake/new").with(admin).with(csrf()).param("name", ""))
                .andExpect(status().isOk()).andExpect(content().string(containsString("銘柄名を入力してください")));
        mvc.perform(post("/admin/sake/new").with(user("ordinary").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/sake/new").with(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/sake/new").with(admin).with(csrf())
                .param("name", "管理登録テスト").param("sakeTypeId", types.findAll().getFirst().getId().toString())
                .param("region", "新潟").param("abv", "15.5").param("price", "1800"))
                .andExpect(redirectedUrl("/admin/sake/new"));
        assertThat(sake.count()).isEqualTo(count + 1);
        mvc.perform(get("/search").param("keyword", "管理登録テスト"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("管理登録テスト")));
    }
    @Test void registersAndServesProductImageAndRejectsInvalidFiles() throws Exception {
        var admin = user(new CustomUserDetails(users.findByEmail(ADMIN_EMAIL).orElseThrow()));
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        var file = new org.springframework.mock.web.MockMultipartFile("image", "photo.png", "image/png", output.toByteArray());
        String typeId = types.findAll().getFirst().getId().toString();
        long count = sake.count();
        mvc.perform(multipart("/admin/sake/new").file(file).with(user("ordinary").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/admin/sake/new").file(file).with(admin))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/admin/sake/new")
                .file(new org.springframework.mock.web.MockMultipartFile("image", "fake.png", "image/png", "invalid".getBytes()))
                .with(admin).with(csrf()).param("name", "不正画像テスト").param("sakeTypeId", typeId))
                .andExpect(status().isOk()).andExpect(content().string(containsString("正しい画像を選択してください")));
        assertThat(sake.count()).isEqualTo(count);
        mvc.perform(multipart("/admin/sake/new").file(file).with(admin).with(csrf())
                .param("name", "画像登録テスト").param("sakeTypeId", typeId))
                .andExpect(redirectedUrl("/admin/sake/new"));
        Long id = jdbc.queryForObject("SELECT id FROM sake WHERE name = ?", Long.class, "画像登録テスト");
        String url = sake.findById(id).orElseThrow().getImageUrl();
        try {
            mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(content().contentType("image/png"))
                    .andExpect(result -> assertThat(javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(result.getResponse().getContentAsByteArray())).getWidth()).isEqualTo(8));
            mvc.perform(get("/sake/" + id)).andExpect(status().isOk())
                    .andExpect(content().string(containsString("src=\"" + url + "\"")));
            mvc.perform(get("/sake/images/invalid.png")).andExpect(status().isNotFound());
        } finally {
            images.delete(url);
        }
    }


    @Test void detailFormatPersistsAndDisplaysAllIntroductionSections() throws Exception {
        var admin = user(new CustomUserDetails(users.findByEmail(ADMIN_EMAIL).orElseThrow()));
        var tagId = jdbc.queryForObject("SELECT MIN(id) FROM tags", Long.class);
        String typeId = types.findAll().getFirst().getId().toString();
        mvc.perform(get("/admin/sake/new").with(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("どんな味わい？")))
                .andExpect(content().string(containsString("合う料理")));
        mvc.perform(post("/admin/sake/new").with(admin).with(csrf())
                .param("name", "商品詳細フォーマットテスト").param("sakeTypeId", typeId)
                .param("brewery", "紹介テスト酒造").param("region", "新潟")
                .param("introduction", "お米の甘みと華やかな香りを楽しむ一本。")
                .param("taste", "やさしい甘み\nすっきりした後味")
                .param("aroma", "華やかな香り").param("recommendedFor", "香りを楽しみたい人")
                .param("drinking", "少し冷やして").param("food", "白身魚のお刺身")
                .param("officialUrl", "https://brewery.example/product")
                .param("purchaseUrl", "https://shop.example/product").param("purchaseLabel", "公式ショップ")
                .param("tagIds", tagId.toString()))
                .andExpect(redirectedUrl("/admin/sake/new"));
        Long id = jdbc.queryForObject("SELECT id FROM sake WHERE name = ?", Long.class, "商品詳細フォーマットテスト");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sake_page_details WHERE sake_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sake_tags WHERE sake_id = ?", Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/sake/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("紹介テスト酒造")))
                .andExpect(content().string(containsString("お米の甘みと華やかな香りを楽しむ一本。")))
                .andExpect(content().string(containsString("やさしい甘み")))
                .andExpect(content().string(containsString("すっきりした後味")))
                .andExpect(content().string(containsString("華やかな香り")))
                .andExpect(content().string(containsString("香りを楽しみたい人")))
                .andExpect(content().string(containsString("少し冷やして")))
                .andExpect(content().string(containsString("白身魚のお刺身")))
                .andExpect(content().string(containsString("href=\"https://shop.example/product\"")))
                .andExpect(content().string(containsString("href=\"https://brewery.example/product\"")));
        mvc.perform(get("/search").param("keyword", "紹介テスト酒造"))
                .andExpect(content().string(containsString("商品詳細フォーマットテスト")));
    }

    @Test void invalidDetailInputsPreserveFormAndDoNotSave() throws Exception {
        var admin = user(new CustomUserDetails(users.findByEmail(ADMIN_EMAIL).orElseThrow()));
        long count = sake.count();
        mvc.perform(post("/admin/sake/new").with(admin).with(csrf())
                .param("name", "不正URLテスト").param("sakeTypeId", types.findAll().getFirst().getId().toString())
                .param("officialUrl", "javascript:alert(1)").param("purchaseUrl", "http://example.com")
                .param("taste", "入力した味わい").param("tagIds", "9223372036854775807"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("有効なURLを入力してください")))
                .andExpect(content().string(containsString("一覧にあるタグを選択してください")))
                .andExpect(content().string(containsString("入力した味わい")));
        mvc.perform(post("/admin/sake/new").with(admin).with(csrf())
                .param("name", "長い紹介文テスト").param("sakeTypeId", types.findAll().getFirst().getId().toString())
                .param("introduction", "あ".repeat(161)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("紹介文は160文字以内")));
        assertThat(sake.count()).isEqualTo(count);
    }
}
