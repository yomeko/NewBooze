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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SakeDetailTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired SakeInteractionService interactions;

    private CustomUserDetails account() {
        User user = new User();
        user.setName("ページ検証");
        user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setPasswordHash("{noop}unused-test-password");
        return new CustomUserDetails(users.saveAndFlush(user));
    }

    @Test void rendersJsonWithoutPhotosAndHidesMissingSections() throws Exception {
        mvc.perform(get("/sake/1")).andExpect(status().isOk())
            .andExpect(content().string(containsString("株式会社 獺祭")))
            .andExpect(content().string(containsString("2,475円（税込）")))
            .andExpect(content().string(containsString("https://www.dassaistore.com/product-detail/47")))
            .andExpect(content().string(not(containsString("<img"))))
            .andExpect(content().string(not(containsString("合う料理"))));
        mvc.perform(get("/sake/2")).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("どんな味わい？"))))
            .andExpect(content().string(not(containsString("価格・購入先"))));
        mvc.perform(get("/sake/9223372036854775807")).andExpect(status().isNotFound());
    }

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
