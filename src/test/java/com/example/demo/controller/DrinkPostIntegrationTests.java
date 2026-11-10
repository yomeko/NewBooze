package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実際のログインとDBを使い、投稿者・別会員・管理者に許可される操作を検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
class DrinkPostIntegrationTests {
    private static final String PASSWORD = "drink-post-test-password";
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger(100);

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> testUserIds = new ArrayList<>();

    @AfterEach void deleteOnlyTestAccounts() {
        // テストで作ったIDに限定し、投稿・いいね・通報は既存の外部キーで連動削除する。
        testUserIds.forEach(id -> users.findById(id).ifPresent(users::delete));
    }

    @Test void membersCanPostButInvalidOrForgedRequestsDoNotWriteToTheFeed() throws Exception {
        var author = signIn(false);
        var reader = signIn(false);
        mvc.perform(get("/posts")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
        mvc.perform(post("/posts").session(author.session()).param("sakeName", "CSRF未送信"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/posts").session(author.session()).with(csrf()).param("sakeName", " "))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/posts").session(author.session()).with(csrf()).param("sakeName", "\u00A0 \u3000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("お酒の名前を入力してください")));
        mvc.perform(post("/posts").session(author.session()).with(csrf())
                .param("sakeName", "投稿テスト").param("comment", "死ね"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drink_posts WHERE user_id = ?", Long.class,
                author.user().getId())).isZero();

        String name = "投稿-" + UUID.randomUUID();
        mvc.perform(post("/posts").session(author.session()).with(csrf())
                .param("sakeName", name).param("comment", "<b>感想</b>")
                .param("userId", reader.user().getId().toString()))
                .andExpect(redirectedUrl("/posts"));
        long postId = findPostId(author.user(), name);
        assertThat(jdbc.queryForObject("SELECT comment FROM drink_posts WHERE id = ?", String.class, postId))
                .isEqualTo("<b>感想</b>");

        String html = mvc.perform(get("/posts").session(reader.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(name, author.user().getName(), "&lt;b&gt;感想&lt;/b&gt;")
                .doesNotContain("<b>感想</b>", author.user().getEmail());
    }

    @Test void repeatingLikeAndUnlikeKeepsTheRequestedStateAndOnlyOwnerCanDelete() throws Exception {
        var author = signIn(false);
        var reader = signIn(false);
        long postId = createPost(author, "削除権限");

        // 同じ要求が再送されても、すでに反映された状態を反転しないことを確認する。
        for (String liked : List.of("true", "true", "false", "false", "true")) {
            mvc.perform(post("/posts/{id}/like", postId).session(reader.session()).with(csrf())
                    .param("liked", liked)).andExpect(redirectedUrl("/posts"));
            assertThat(likeCount(postId)).isEqualTo(Boolean.parseBoolean(liked) ? 1L : 0L);
        }

        mvc.perform(post("/posts/{id}/delete", postId).session(reader.session()).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(postCount(postId)).isEqualTo(1L);
        mvc.perform(post("/posts/{id}/delete", postId).session(author.session()).with(csrf()))
                .andExpect(redirectedUrl("/posts"));
        assertThat(postCount(postId)).isZero();
        assertThat(likeCount(postId)).isZero();
        mvc.perform(post("/posts/{id}/like", postId).session(reader.session()).with(csrf())
                .param("liked", "true")).andExpect(status().isNotFound());
    }

    @Test void adminCanDismissOneReportOrDeleteItsPostAndAllRelatedActivity() throws Exception {
        var author = signIn(false);
        var reader = signIn(false);
        var anotherReader = signIn(false);
        var admin = signIn(true);
        long postId = createPost(author, "通報の対象");
        mvc.perform(post("/posts/{id}/like", postId).session(reader.session()).with(csrf())
                .param("liked", "true")).andExpect(redirectedUrl("/posts"));
        mvc.perform(post("/posts/{id}/report", postId).session(author.session()).with(csrf())
                .param("reason", "ABUSE")).andExpect(status().isForbidden());
        mvc.perform(post("/posts/{id}/report", postId).session(reader.session()).with(csrf())
                .param("reason", "UNSUPPORTED")).andExpect(status().isBadRequest());
        assertThat(reportCount(postId)).isZero();
        report(reader, postId, "ABUSE");
        report(reader, postId, "SPAM");
        long firstReportId = findReportId(reader.user(), postId, "ABUSE");
        report(anotherReader, postId, "OFF_TOPIC");
        long secondReportId = findReportId(anotherReader.user(), postId, "OFF_TOPIC");
        assertThat(reportCount(postId)).isEqualTo(2L);

        mvc.perform(get("/admin/post-reports").session(reader.session())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/post-reports/{id}/dismiss", firstReportId)
                .session(reader.session()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/post-reports/{id}/dismiss", firstReportId).session(admin.session()))
                .andExpect(status().isForbidden());
        assertThat(reportCount(postId)).isEqualTo(2L);

        String queue = mvc.perform(get("/admin/post-reports").session(admin.session())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(queue).contains("/admin/post-reports/" + firstReportId + "/dismiss",
                "/admin/post-reports/" + secondReportId + "/delete-post");
        mvc.perform(post("/admin/post-reports/{id}/dismiss", firstReportId)
                .session(admin.session()).with(csrf())).andExpect(redirectedUrl("/admin/post-reports"));
        assertThat(postCount(postId)).isEqualTo(1L);
        assertThat(likeCount(postId)).isEqualTo(1L);
        assertThat(reportCount(postId)).isEqualTo(1L);
        mvc.perform(post("/admin/post-reports/{id}/delete-post", firstReportId)
                .session(admin.session()).with(csrf())).andExpect(status().isNotFound());

        mvc.perform(post("/admin/post-reports/{id}/delete-post", secondReportId)
                .session(admin.session()).with(csrf())).andExpect(redirectedUrl("/admin/post-reports"));
        assertThat(postCount(postId)).isZero();
        assertThat(likeCount(postId)).isZero();
        assertThat(reportCount(postId)).isZero();
    }

    private Account signIn(boolean admin) throws Exception {
        var user = new User();
        UUID id = UUID.randomUUID();
        user.setName("投稿検証-" + id);
        user.setEmail("post-" + id + "@example.invalid");
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setEmailVerified(true);
        user.setAdmin(admin);
        user = users.saveAndFlush(user);
        testUserIds.add(user.getId());
        String ip = "198.51.100." + CLIENT_SEQUENCE.incrementAndGet();
        var login = mvc.perform(post("/login").with(request -> { request.setRemoteAddr(ip); return request; })
                .with(csrf()).param("username", user.getEmail()).param("password", PASSWORD))
                .andExpect(authenticated().withUsername(user.getEmail())).andReturn();
        return new Account(user, (MockHttpSession) login.getRequest().getSession(false));
    }

    private long createPost(Account author, String name) throws Exception {
        mvc.perform(post("/posts").session(author.session()).with(csrf())
                .param("sakeName", name).param("comment", "試飲の感想"))
                .andExpect(redirectedUrl("/posts"));
        return findPostId(author.user(), name);
    }

    private long findPostId(User author, String name) {
        return jdbc.queryForObject("SELECT id FROM drink_posts WHERE user_id = ? AND sake_name = ?",
                Long.class, author.getId(), name);
    }

    private void report(Account reader, long postId, String reason) throws Exception {
        mvc.perform(post("/posts/{id}/report", postId).session(reader.session()).with(csrf())
                .param("reason", reason)).andExpect(redirectedUrl("/posts"));
    }

    private long findReportId(User reader, long postId, String reason) {
        return jdbc.queryForObject("SELECT id FROM drink_post_reports WHERE reporter_id = ? AND post_id = ? AND reason = ?",
                Long.class, reader.getId(), postId, reason);
    }

    private long postCount(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM drink_posts WHERE id = ?", Long.class, postId);
    }

    private long likeCount(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM drink_post_likes WHERE post_id = ?", Long.class, postId);
    }

    private long reportCount(long postId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM drink_post_reports WHERE post_id = ?", Long.class, postId);
    }

    private record Account(User user, MockHttpSession session) {}
}
