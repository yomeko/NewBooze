package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.EmailVerificationService;
import com.example.demo.service.TemporaryPasswordService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 実DBとSecurityのフィルターを通してメール認証を確認する。メール送信だけをMockに置き換える。
 * テスト全体をトランザクションで包まず、Serviceの送信失敗時のロールバックを実際に検証する。
 * 作成先は毎回異なるexample.invalidのアドレスとし、終了時にそのレコードだけを削除する。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationIntegrationTests {
    private static final String PASSWORD = "verification-test-password";
    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]{43})");
    private static final Pattern TEMPORARY_PASSWORD = Pattern.compile("仮パスワード: ([^\\r\\n]+)");
    private static final Pattern RESEND_STATUS = Pattern.compile("<[^>]+\\bid=\"resend-status\"[^>]*>([^<]*)<");

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private EmailVerificationService verification;
    @Autowired private TemporaryPasswordService temporaryPasswords;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private JavaMailSender mail;

    private final List<String> testEmails = new ArrayList<>();

    @BeforeEach
    void resetMailAndAuthentication() {
        reset(mail);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void deleteOnlyUsersCreatedByThisTest() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (String email : testEmails) {
                users.findByEmail(email).ifPresent(users::delete);
            }
            users.flush();
        });
        SecurityContextHolder.clearContext();
    }

    @Test
    void failedRegistrationMailRollsBackTheInsertedUser() {
        String email = newEmail();
        doThrow(new MailSendException("simulated SMTP failure"))
                .when(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> verification.register(signup(email)))
                .isInstanceOf(MailSendException.class);

        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test
    void failedResendPreservesThePreviouslyCommittedLinkAndSendingTime() {
        String email = newEmail();
        verification.register(signup(email));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = users.findByEmail(email).orElseThrow();
            user.setEmailVerificationSentAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
            users.saveAndFlush(user);
        });
        User before = readUser(email);
        doThrow(new MailSendException("simulated SMTP failure"))
                .when(mail).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> verification.resend(email)).isInstanceOf(MailSendException.class);

        User after = readUser(email);
        assertThat(after.isEmailVerified()).isFalse();
        assertThat(after.getEmailVerificationHash()).isEqualTo(before.getEmailVerificationHash());
        assertThat(after.getEmailVerificationExpiresAt()).isEqualTo(before.getEmailVerificationExpiresAt());
        assertThat(after.getEmailVerificationSentAt()).isEqualTo(before.getEmailVerificationSentAt());
    }

    @Test
    void aSuccessfulResendDisablesTheOldLinkAndTheNewLinkEnablesLogin() throws Exception {
        String email = newEmail();
        verification.register(signup(email));
        String oldToken = sentToken();
        allowResend(email);
        User before = readUser(email);
        reset(mail);

        MvcResult resend = resend("  " + email + "  ");
        resendPage(resend);
        String newToken = sentToken();
        User after = readUser(email);
        assertThat(after.isEmailVerified()).isFalse();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(after.getEmailVerificationHash()).isNotEqualTo(before.getEmailVerificationHash());
        assertThat(after.getEmailVerificationExpiresAt()).isAfter(before.getEmailVerificationExpiresAt());
        assertThat(after.getEmailVerificationSentAt()).isAfter(before.getEmailVerificationSentAt());

        mvc.perform(post("/verify-email").with(csrf()).param("token", oldToken))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
                .andExpect(model().attributeDoesNotExist("verified"));
        assertThat(readUser(email).isEmailVerified()).isFalse();
        assertThat(readUser(email).getEmailVerificationHash()).isEqualTo(after.getEmailVerificationHash());

        mvc.perform(post("/verify-email").with(csrf()).param("token", newToken))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true))
                .andExpect(unauthenticated());
        assertThat(readUser(email).isEmailVerified()).isTrue();
        assertThat(verification.verify(newToken)).isFalse();
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
        verify(mail).send(any(SimpleMailMessage.class));
    }

    @Test
    void repeatedVerifiedAndUnknownResendsShowTheSameGenericStatusWithoutSending() throws Exception {
        String email = newEmail();
        verification.register(signup(email));
        allowResend(email);
        reset(mail);

        MvcResult accepted = resend(email);
        String genericStatus = resendStatus(resendPage(accepted));
        String token = sentToken();
        User afterSend = readUser(email);

        assertThat(resendStatus(resendPage(resend(email)))).isEqualTo(genericStatus);
        User afterRepeatedRequest = readUser(email);
        assertThat(afterRepeatedRequest.getEmailVerificationHash()).isEqualTo(afterSend.getEmailVerificationHash());
        assertThat(afterRepeatedRequest.getEmailVerificationSentAt()).isEqualTo(afterSend.getEmailVerificationSentAt());

        mvc.perform(get("/verify-email")).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("sent"));
        verify(mail).send(any(SimpleMailMessage.class));

        assertThat(verification.verify(token)).isTrue();
        assertThat(resendStatus(resendPage(resend(email)))).isEqualTo(genericStatus);
        String unknown = newEmail();
        assertThat(resendStatus(resendPage(resend(unknown)))).isEqualTo(genericStatus);

        verify(mail).send(any(SimpleMailMessage.class));
        assertThat(users.findByEmail(unknown)).isEmpty();
        assertThat(readUser(email).getEmailVerificationHash()).isNull();
    }

    @Test
    void missingAndInvalidResendEmailsRedirectToAnErrorWithoutSending() throws Exception {
        var requests = List.of(post("/verify-email/resend").with(csrf()),
                post("/verify-email/resend").with(csrf()).param("email", ""),
                post("/verify-email/resend").with(csrf()).param("email", "   "),
                post("/verify-email/resend").with(csrf()).param("email", "invalid"),
                post("/verify-email/resend").with(csrf()).param("email", "x@y"),
                post("/verify-email/resend").with(csrf())
                        .param("email", "x".repeat(256) + "@example.invalid"));
        for (var request : requests) {
            MvcResult result = mvc.perform(request)
                    .andExpect(redirectedUrl("/verify-email"))
                    .andExpect(flash().attributeExists("error"))
                    .andExpect(response -> assertThat(response.getFlashMap().containsKey("sent")).isFalse())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andReturn();
            mvc.perform(get("/verify-email").flashAttrs(result.getFlashMap()))
                    .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        }
        verifyNoInteractions(mail);
    }

    @Test
    void aResendSmtpFailureRedirectsToAnErrorAndThePreviousLinkStillWorks() throws Exception {
        String email = newEmail();
        verification.register(signup(email));
        String previousToken = sentToken();
        allowResend(email);
        User before = readUser(email);
        reset(mail);
        doThrow(new MailSendException("simulated SMTP failure"))
                .when(mail).send(any(SimpleMailMessage.class));

        MvcResult result = mvc.perform(post("/verify-email/resend").with(csrf()).param("email", email))
                .andExpect(redirectedUrl("/verify-email"))
                .andExpect(flash().attributeExists("error"))
                .andExpect(response -> assertThat(response.getFlashMap().containsKey("sent")).isFalse())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        mvc.perform(get("/verify-email").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
                .andExpect(model().attributeDoesNotExist("sent"));

        User after = readUser(email);
        assertThat(after.getEmailVerificationHash()).isEqualTo(before.getEmailVerificationHash());
        assertThat(after.getEmailVerificationExpiresAt()).isEqualTo(before.getEmailVerificationExpiresAt());
        assertThat(after.getEmailVerificationSentAt()).isEqualTo(before.getEmailVerificationSentAt());
        assertThat(verification.verify(previousToken)).isTrue();
        verify(mail).send(any(SimpleMailMessage.class));
    }

    @Test
    void signupStaysLoggedOutAndOnlyAnExplicitConfirmationEnablesLogin() throws Exception {
        String email = newEmail();
        mvc.perform(post("/signup").with(csrf()).param("name", "メール認証統合テスト")
                        .param("email", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/verify-email"))
                .andExpect(unauthenticated());
        String token = sentToken();
        User pending = readUser(email);
        assertThat(pending.isEmailVerified()).isFalse();
        assertThat(pending.getEmailVerificationHash()).isNotEqualTo(token);

        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());

        mvc.perform(get("/verify-email").param("token", token)).andExpect(status().isOk());
        User afterGet = readUser(email);
        assertThat(afterGet.isEmailVerified()).isFalse();
        assertThat(afterGet.getEmailVerificationHash()).isEqualTo(pending.getEmailVerificationHash());

        mvc.perform(post("/verify-email").with(csrf()).param("token", token))
                .andExpect(status().isOk()).andExpect(unauthenticated());
        assertThat(readUser(email).isEmailVerified()).isTrue();
        assertThat(readUser(email).getEmailVerificationHash()).isNull();
        assertThat(verification.verify(token)).isFalse();

        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
    }

    @Test
    void signupConfirmationAndResendRequireCsrf() throws Exception {
        String email = newEmail();
        mvc.perform(post("/signup").param("name", "メール認証統合テスト")
                        .param("email", email).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(users.findByEmail(email)).isEmpty();
        verifyNoInteractions(mail);

        verification.register(signup(email));
        String token = sentToken();
        String previousHash = readUser(email).getEmailVerificationHash();
        reset(mail);

        mvc.perform(post("/verify-email").param("token", token)).andExpect(status().isForbidden());
        mvc.perform(post("/verify-email/resend").param("email", email)).andExpect(status().isForbidden());

        User pending = readUser(email);
        assertThat(pending.isEmailVerified()).isFalse();
        assertThat(pending.getEmailVerificationHash()).isEqualTo(previousHash);
        verifyNoInteractions(mail);
    }

    @Test
    void anExistingVerifiedUserCanStillLogInWithoutRequestingVerification() throws Exception {
        String email = newEmail();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User existing = new User();
            existing.setName("既存ユーザー統合テスト");
            existing.setEmail(email);
            existing.setPasswordHash(encoder.encode(PASSWORD));
            assertThat(existing.isEmailVerified()).isTrue();
            users.saveAndFlush(existing);
        });

        assertThat(new CustomUserDetails(readUser(email)).isEnabled()).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
        verifyNoInteractions(mail);
    }

    @Test
    void aTemporaryPasswordCannotBypassVerificationAndKeepsItsChangeReminderAfterConfirmation()
            throws Exception {
        String email = newEmail();
        verification.register(signup(email));
        String token = sentToken();
        temporaryPasswords.issueFor(email);
        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, times(2)).send(messages.capture());
        var matcher = TEMPORARY_PASSWORD.matcher(messages.getAllValues().get(1).getText());
        assertThat(matcher.find()).isTrue();
        String temporaryPassword = matcher.group(1);
        User pending = readUser(email);
        assertThat(pending.isTemporaryPassword()).isTrue();
        assertThat(pending.isEmailVerified()).isFalse();

        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", temporaryPassword))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        assertThat(verification.verify(token)).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", temporaryPassword))
                .andExpect(redirectedUrl("/mypage?passwordChangeRequired"))
                .andExpect(authenticated().withUsername(email));
    }

    private String newEmail() {
        String email = "verification-" + UUID.randomUUID() + "@example.invalid";
        testEmails.add(email);
        return email;
    }

    private SignupForm signup(String email) {
        SignupForm form = new SignupForm();
        form.setName("メール認証統合テスト");
        form.setEmail(email);
        form.setPassword(PASSWORD);
        return form;
    }

    private User readUser(String email) {
        return users.findByEmail(email).orElseThrow();
    }

    private void allowResend(String email) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User user = users.findByEmail(email).orElseThrow();
            user.setEmailVerificationSentAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
            user.setEmailVerificationExpiresAt(user.getEmailVerificationSentAt().plusHours(24));
            users.saveAndFlush(user);
        });
    }

    private MvcResult resend(String email) throws Exception {
        return mvc.perform(post("/verify-email/resend").with(csrf()).param("email", email))
                .andExpect(redirectedUrl("/verify-email"))
                .andExpect(flash().attribute("sent", true))
                .andExpect(flash().attribute("resendEmail", email.trim()))
                .andExpect(response -> assertThat(response.getFlashMap().containsKey("error")).isFalse())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
    }

    private MvcResult resendPage(MvcResult result) throws Exception {
        return mvc.perform(get("/verify-email").flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(model().attribute("sent", true))
                .andExpect(model().attribute("resendEmail", result.getFlashMap().get("resendEmail")))
                .andExpect(model().attributeDoesNotExist("error"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
    }

    private String resendStatus(MvcResult result) throws Exception {
        var matcher = RESEND_STATUS.matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String sentToken() {
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        var matcher = TOKEN.matcher(message.getValue().getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
