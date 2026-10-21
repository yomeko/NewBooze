package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.PasswordResetService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SMTPはMockに置き換え、実DBのロールバック・同時使用と実SecurityのCSRFを確認する。 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetIntegrationTests {
    private static final String OLD_PASSWORD = "reset-original-password";
    private static final String NEW_PASSWORD = "reset-replacement-password";
    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]{43})");
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger(40);
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordResetService passwords;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private JavaMailSender mail;
    private final List<String> emails = new ArrayList<>();
    private String requestIp;

    @BeforeEach void clearMail() {
        reset(mail);
        requestIp = "198.51.100." + CLIENT_SEQUENCE.incrementAndGet();
    }

    @AfterEach void deleteOnlyTestUsers() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (String email : emails) users.findByEmail(email).ifPresent(users::delete);
            users.flush();
        });
    }

    @Test void smtpFailureRollsBackTheNewTokenWithoutChangingTheOldPasswordOrLink() {
        String email = createUser();
        passwords.requestFor(email);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            User user = users.findByEmail(email).orElseThrow();
            user.setPasswordResetSentAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
            users.saveAndFlush(user);
        });
        User before = users.findByEmail(email).orElseThrow();
        doThrow(new MailSendException("simulated SMTP failure")).when(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> passwords.requestFor(email)).isInstanceOf(MailSendException.class);

        User after = users.findByEmail(email).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());
        assertThat(after.getPasswordResetHash()).isEqualTo(before.getPasswordResetHash());
        assertThat(after.getPasswordResetExpiresAt()).isEqualTo(before.getPasswordResetExpiresAt());
        assertThat(after.getPasswordResetSentAt()).isEqualTo(before.getPasswordResetSentAt());
    }

    @Test void getDoesNotChangePasswordAndCsrfIsRequiredBeforeOneTimeReset() throws Exception {
        String email = createUser();
        passwords.requestFor(email);
        String token = sentToken();
        var entry = mvc.perform(get("/reset-password").with(client()).param("token", token))
                .andExpect(redirectedUrl("/reset-password"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        MockHttpSession session = (MockHttpSession) entry.getRequest().getSession(false);
        mvc.perform(get("/reset-password").with(client()).session(session)).andExpect(status().isOk());
        assertThat(encoder.matches(OLD_PASSWORD, users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(post("/reset-password").with(client()).session(session).param("newPassword", NEW_PASSWORD)
                .param("confirmPassword", NEW_PASSWORD)).andExpect(status().isForbidden());
        assertThat(encoder.matches(OLD_PASSWORD, users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(post("/reset-password").with(client()).session(session).with(csrf()).param("newPassword", NEW_PASSWORD)
                .param("confirmPassword", NEW_PASSWORD)).andExpect(redirectedUrl("/login?passwordReset"));
        User updated = users.findByEmail(email).orElseThrow();
        assertThat(encoder.matches(NEW_PASSWORD, updated.getPasswordHash())).isTrue();
        assertThat(updated.getPasswordResetHash()).isNull();
        assertThat(updated.getPasswordResetExpiresAt()).isNull();
        assertThat(passwords.reset(token, "third-valid-password")).isFalse();
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", OLD_PASSWORD))
                .andExpect(unauthenticated());
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", NEW_PASSWORD))
                .andExpect(authenticated().withUsername(email));
    }

    @Test void concurrentSubmissionsCanOnlyConsumeTheSameTokenOnce() throws Exception {
        String email = createUser();
        passwords.requestFor(email);
        String token = sentToken();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return passwords.reset(token, NEW_PASSWORD); });
            var second = pool.submit(() -> { start.await(); return passwords.reset(token, "second-valid-password"); });
            start.countDown();
            List<Boolean> outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        }
        assertThat(users.findByEmail(email).orElseThrow().getPasswordResetHash()).isNull();
    }

    @Test void aRequestPostNeedsCsrfAndKeepsTheSameReplyForUnknownAndFailedDelivery() throws Exception {
        String email = createUser();
        mvc.perform(post("/forgot-password").with(client()).param("email", email)).andExpect(status().isForbidden());
        verifyNoInteractions(mail);
        doThrow(new MailSendException("simulated SMTP failure")).when(mail).send(any(SimpleMailMessage.class));
        mvc.perform(post("/forgot-password").with(client()).with(csrf()).param("email", email))
                .andExpect(redirectedUrl("/forgot-password")).andExpect(flash().attribute("sent", true));
        mvc.perform(post("/forgot-password").with(client()).with(csrf()).param("email", "unknown-" + UUID.randomUUID() + "@example.invalid"))
                .andExpect(redirectedUrl("/forgot-password")).andExpect(flash().attribute("sent", true));
        assertThat(users.findByEmail(email).orElseThrow().getPasswordResetHash()).isNull();
    }

    @Test void aPasswordResetRevokesAnAlreadyAuthenticatedSessionOnItsNextRequest() throws Exception {
        String email = createUser();
        MockHttpSession signedIn = login(email);
        mvc.perform(get("/mypage/account").with(client()).session(signedIn)).andExpect(status().isOk())
                .andExpect(authenticated().withUsername(email));
        passwords.requestFor(email);
        assertThat(passwords.reset(sentToken(), NEW_PASSWORD)).isTrue();

        mvc.perform(get("/mypage/account").with(client()).session(signedIn))
                .andExpect(redirectedUrl("/login?sessionExpired")).andExpect(unauthenticated());

        assertThat(signedIn.isInvalid()).isTrue();
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", NEW_PASSWORD))
                .andExpect(authenticated().withUsername(email));
    }

    @Test void changingThePasswordInSettingsInvalidatesAnIssuedResetLinkButKeepsThisSession() throws Exception {
        String email = createUser();
        MockHttpSession signedIn = login(email);
        passwords.requestFor(email);
        String token = sentToken();
        assertThat(users.findByEmail(email).orElseThrow().getPasswordResetHash()).isNotNull();

        mvc.perform(post("/mypage/password").with(client()).session(signedIn).with(csrf())
                .param("currentPassword", OLD_PASSWORD).param("newPassword", NEW_PASSWORD))
                .andExpect(redirectedUrl("/mypage/account#settings"));

        User updated = users.findByEmail(email).orElseThrow();
        assertThat(updated.getPasswordResetHash()).isNull();
        assertThat(updated.getPasswordResetExpiresAt()).isNull();
        assertThat(passwords.reset(token, "third-valid-password")).isFalse();
        assertThat(encoder.matches(NEW_PASSWORD, updated.getPasswordHash())).isTrue();
        mvc.perform(get("/mypage/account").with(client()).session(signedIn)).andExpect(status().isOk())
                .andExpect(authenticated().withUsername(email));
    }

    @Test void changingTheEmailInvalidatesAnIssuedResetLinkAndRequiresVerification() throws Exception {
        String email = createUser();
        MockHttpSession signedIn = login(email);
        passwords.requestFor(email);
        String token = sentToken();
        String replacementEmail = "changed-" + UUID.randomUUID() + "@example.invalid";
        emails.add(replacementEmail);
        reset(mail);

        mvc.perform(post("/mypage/profile").with(client()).session(signedIn).with(csrf())
                .param("name", "変更後の表示名").param("email", replacementEmail))
                .andExpect(redirectedUrl("/verify-email")).andExpect(unauthenticated());

        User updated = users.findByEmail(replacementEmail).orElseThrow();
        assertThat(updated.getPasswordResetHash()).isNull();
        assertThat(updated.getPasswordResetExpiresAt()).isNull();
        assertThat(updated.isEmailVerified()).isFalse();
        assertThat(encoder.matches(OLD_PASSWORD, updated.getPasswordHash())).isTrue();
        assertThat(passwords.reset(token, NEW_PASSWORD)).isFalse();
        assertThat(signedIn.isInvalid()).isTrue();
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly(replacementEmail);
        assertThat(message.getValue().getSubject()).contains("メールアドレスの認証");
    }

    private MockHttpSession login(String email) throws Exception {
        var result = mvc.perform(post("/login").with(client()).with(csrf())
                .param("username", email).param("password", OLD_PASSWORD))
                .andExpect(authenticated().withUsername(email)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private RequestPostProcessor client() {
        return request -> { request.setRemoteAddr(requestIp); return request; };
    }

    private String createUser() {
        String email = "reset-" + UUID.randomUUID() + "@example.invalid";
        emails.add(email);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            User user = new User();
            user.setName("再設定統合テスト"); user.setEmail(email);
            user.setPasswordHash(encoder.encode(OLD_PASSWORD)); user.setEmailVerified(true);
            users.saveAndFlush(user);
        });
        return email;
    }

    private String sentToken() {
        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(messages.capture());
        var matcher = TOKEN.matcher(messages.getValue().getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
