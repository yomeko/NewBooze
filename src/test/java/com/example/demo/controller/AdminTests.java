package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.*;
import com.example.demo.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
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
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SakeRepository sake;
    @Autowired SakeTypeRepository types;
    @Autowired PasswordEncoder encoder;

    // 管理者だけが管理画面を開けて、通常ログイン後も管理画面へ進むことを確認する。
    @Test void adminLoginAndProtectedPages() throws Exception {
        User admin = users.findByEmail("admin").orElseThrow();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(encoder.matches("admin", admin.getPasswordHash())).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", "admin").param("password", "admin"))
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
        var admin = user(new CustomUserDetails(users.findByEmail("admin").orElseThrow()));
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
}
