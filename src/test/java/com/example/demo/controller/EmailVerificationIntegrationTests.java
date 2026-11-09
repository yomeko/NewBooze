package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.EmailVerificationService;
import com.example.demo.service.PasswordResetService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
    private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger(80);
    private static final String PASSWORD = "verification-test-password";
    private static final Pattern TOKEN = Pattern.compile("[?&]token=((?:signup_|change_)?[A-Za-z0-9_-]{43})");
    private static final Pattern RESEND_STATUS = Pattern.compile("<[^>]+\\bid=\"resend-status\"[^>]*>([^<]*)<");

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private EmailVerificationService verification;
    @Autowired private PasswordResetService passwordResets;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private JavaMailSender mail;

    private final List<String> testEmails = new ArrayList<>();
    private String requestIp;

    @BeforeEach
    void resetMailAndAuthentication() {
        reset(mail);
        requestIp = "198.51.100." + CLIENT_SEQUENCE.incrementAndGet();
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

        mvc.perform(completeRegistration(oldToken))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
                .andExpect(model().attributeDoesNotExist("verified"));
        assertThat(readUser(email).isEmailVerified()).isFalse();
        assertThat(readUser(email).getEmailVerificationHash()).isEqualTo(after.getEmailVerificationHash());

        mvc.perform(completeRegistration(newToken))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true))
                .andExpect(unauthenticated());
        assertThat(readUser(email).isEmailVerified()).isTrue();
        assertThat(verification.verify(newToken, "メール認証統合テスト", PASSWORD, PASSWORD)).isFalse();
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", PASSWORD))
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

        mvc.perform(get("/verify-email").with(client())).andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("sent"));
        verify(mail).send(any(SimpleMailMessage.class));

        assertThat(verification.verify(token, "メール認証統合テスト", PASSWORD, PASSWORD)).isTrue();
        assertThat(resendStatus(resendPage(resend(email)))).isEqualTo(genericStatus);
        String unknown = newEmail();
        assertThat(resendStatus(resendPage(resend(unknown)))).isEqualTo(genericStatus);

        verify(mail).send(any(SimpleMailMessage.class));
        assertThat(users.findByEmail(unknown)).isEmpty();
        assertThat(readUser(email).getEmailVerificationHash()).isNull();
    }

    @Test
    void missingAndInvalidResendEmailsRedirectToAnErrorWithoutSending() throws Exception {
        var requests = List.of(post("/verify-email/resend").with(client()).with(csrf()),
                post("/verify-email/resend").with(client()).with(csrf()).param("email", ""),
                post("/verify-email/resend").with(client()).with(csrf()).param("email", "   "),
                post("/verify-email/resend").with(client()).with(csrf()).param("email", "invalid"),
                post("/verify-email/resend").with(client()).with(csrf()).param("email", "x@y"),
                post("/verify-email/resend").with(client()).with(csrf())
                        .param("email", "x".repeat(256) + "@example.invalid"));
        for (var request : requests) {
            MvcResult result = mvc.perform(request)
                    .andExpect(redirectedUrl("/verify-email"))
                    .andExpect(flash().attributeExists("error"))
                    .andExpect(response -> assertThat(response.getFlashMap().containsKey("sent")).isFalse())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andReturn();
            mvc.perform(get("/verify-email").with(client()).flashAttrs(result.getFlashMap()))
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

        MvcResult result = mvc.perform(post("/verify-email/resend").with(client()).with(csrf()).param("email", email))
                .andExpect(redirectedUrl("/verify-email"))
                .andExpect(flash().attributeExists("error"))
                .andExpect(response -> assertThat(response.getFlashMap().containsKey("sent")).isFalse())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        mvc.perform(get("/verify-email").with(client()).flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"))
                .andExpect(model().attributeDoesNotExist("sent"));

        User after = readUser(email);
        assertThat(after.getEmailVerificationHash()).isEqualTo(before.getEmailVerificationHash());
        assertThat(after.getEmailVerificationExpiresAt()).isEqualTo(before.getEmailVerificationExpiresAt());
        assertThat(after.getEmailVerificationSentAt()).isEqualTo(before.getEmailVerificationSentAt());
        assertThat(verification.verify(previousToken, "メール認証統合テスト", PASSWORD, PASSWORD)).isTrue();
        verify(mail).send(any(SimpleMailMessage.class));
    }

    @Test
    void signupStaysLoggedOutAndOnlyAnExplicitConfirmationEnablesLogin() throws Exception {
        String email = newEmail();
        String attackerPassword = "attacker-chosen-password";
        mvc.perform(post("/signup").with(client()).with(csrf()).param("email", email)
                        .param("name", "先に登録した人").param("password", attackerPassword))
                .andExpect(redirectedUrl("/verify-email"))
                .andExpect(unauthenticated());
        String token = sentToken();
        User pending = readUser(email);
        assertThat(pending.isEmailVerified()).isFalse();
        assertThat(token).startsWith("signup_");
        assertThat(pending.getEmailVerificationHash()).matches("h[0-9a-f]{63}");
        assertThat(pending.getName()).isNotEqualTo("先に登録した人");
        assertThat(encoder.matches(attackerPassword, pending.getPasswordHash())).isFalse();

        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());

        mvc.perform(get("/verify-email").with(client()).param("token", token)).andExpect(status().isOk());
        User afterGet = readUser(email);
        assertThat(afterGet.isEmailVerified()).isFalse();
        assertThat(afterGet.getEmailVerificationHash()).isEqualTo(pending.getEmailVerificationHash());

        mvc.perform(completeRegistration(token))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true)).andExpect(unauthenticated());
        assertThat(readUser(email).isEmailVerified()).isTrue();
        assertThat(readUser(email).getEmailVerificationHash()).isNull();
        assertThat(verification.verify(token, "メール認証統合テスト", PASSWORD, PASSWORD)).isFalse();

        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", attackerPassword))
                .andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
    }

    @Test
    void legacyPendingAddressChangeRequiresExistingPasswordAndNeverReplacesIt() throws Exception {
        String email = newEmail();
        String oldPassword = "legacy-existing-password";
        String oldHash = encoder.encode(oldPassword);
        String token = "L".repeat(43);
        createLegacyPendingAccount(email, token, oldHash);

        mvc.perform(post("/verify-email").with(client()).with(csrf()).param("token", token))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(readUser(email).isEmailVerified()).isFalse();

        mvc.perform(completeRegistration(token))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(readUser(email).isEmailVerified()).isFalse();

        mvc.perform(post("/verify-email").with(client()).with(csrf())
                        .param("token", token).param("password", oldPassword))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true));
        assertThat(readUser(email).getName()).isEqualTo("旧方式の表示名");
        assertThat(readUser(email).getPasswordHash()).isEqualTo(oldHash);
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", oldPassword))
                .andExpect(redirectedUrl("/")).andExpect(authenticated().withUsername(email));
    }

    @Test
    void changingAnExistingAddressRequiresBothTheLinkAndTheExistingPasswordAfterResend() throws Exception {
        String email = newEmail();
        String changedEmail = newEmail();
        String passwordHash = encoder.encode(PASSWORD);
        User existing = new User();
        existing.setName("既存会員");
        existing.setEmail(email);
        existing.setPasswordHash(passwordHash);
        Long id = users.saveAndFlush(existing).getId();

        assertThat(verification.updateProfile(id, "変更後の表示名", changedEmail)).isTrue();
        String previousToken = sentToken();
        assertThat(previousToken).startsWith("change_");
        allowResend(changedEmail);
        reset(mail);
        resend(changedEmail);
        String newToken = sentToken();
        assertThat(newToken).startsWith("change_").isNotEqualTo(previousToken);
        assertThat(readUser(changedEmail).getEmailVerificationHash()).matches("g[0-9a-f]{63}");

        mvc.perform(get("/verify-email").with(client()).param("token", newToken))
                .andExpect(status().isOk()).andExpect(model().attribute("signup", false));
        mvc.perform(post("/verify-email").with(client()).with(csrf()).param("token", newToken))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        mvc.perform(completeRegistration(newToken.substring("change_".length())))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(readUser(changedEmail).isEmailVerified()).isFalse();

        mvc.perform(post("/verify-email").with(client()).with(csrf())
                        .param("token", newToken).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true)).andExpect(unauthenticated());
        User after = readUser(changedEmail);
        assertThat(after.isEmailVerified()).isTrue();
        assertThat(after.getPasswordHash()).isEqualTo(passwordHash);
        assertThat(after.getName()).isEqualTo("変更後の表示名");
    }

    @Test
    void resendingLegacyPendingLinkStillRequiresTheExistingPassword() throws Exception {
        String email = newEmail();
        String existingPassword = "existing-password";
        String existingHash = encoder.encode(existingPassword);
        String legacyToken = "M".repeat(43);
        createLegacyPendingAccount(email, legacyToken, existingHash);

        mvc.perform(completeRegistration(legacyToken))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(readUser(email).isEmailVerified()).isFalse();
        allowResend(email);
        reset(mail);
        resend(email);

        String changeToken = sentToken();
        assertThat(changeToken).startsWith("change_");
        mvc.perform(post("/verify-email").with(client()).with(csrf())
                        .param("token", changeToken).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(readUser(email).isEmailVerified()).isFalse();
        mvc.perform(post("/verify-email").with(client()).with(csrf())
                        .param("token", changeToken).param("password", existingPassword))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true));
        assertThat(readUser(email).getPasswordHash()).isEqualTo(existingHash);
    }

    @Test
    void signupConfirmationAndResendRequireCsrf() throws Exception {
        String email = newEmail();
        mvc.perform(post("/signup").with(client()).param("name", "メール認証統合テスト")
                        .param("email", email).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(users.findByEmail(email)).isEmpty();
        verifyNoInteractions(mail);

        verification.register(signup(email));
        String token = sentToken();
        String previousHash = readUser(email).getEmailVerificationHash();
        reset(mail);

        mvc.perform(post("/verify-email").with(client()).param("token", token)).andExpect(status().isForbidden());
        mvc.perform(post("/verify-email/resend").with(client()).param("email", email)).andExpect(status().isForbidden());

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
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
        verifyNoInteractions(mail);
    }

    @Test
    void passwordResetRequestsCannotChangeAnUnverifiedAccountsPasswordOrBypassVerification()
            throws Exception {
        String email = newEmail();
        verification.register(signup(email));
        String token = sentToken();
        String previousHash = readUser(email).getPasswordHash();
        passwordResets.requestFor(email);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        User pending = readUser(email);
        assertThat(pending.getPasswordHash()).isEqualTo(previousHash);
        assertThat(pending.getPasswordResetHash()).isNull();
        assertThat(pending.isTemporaryPassword()).isFalse();
        assertThat(pending.isEmailVerified()).isFalse();

        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        assertThat(verification.verify(token, "メール認証統合テスト", PASSWORD, PASSWORD)).isTrue();
        mvc.perform(post("/login").with(client()).with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email));
    }

    private RequestPostProcessor client() {
        return request -> { request.setRemoteAddr(requestIp); return request; };
    }

    private String newEmail() {
        String email = "verification-" + UUID.randomUUID() + "@example.invalid";
        testEmails.add(email);
        return email;
    }

    private SignupForm signup(String email) {
        SignupForm form = new SignupForm();
        form.setEmail(email);
        return form;
    }

    private MockHttpServletRequestBuilder completeRegistration(String token) {
        return post("/verify-email").with(client()).with(csrf()).param("token", token)
                .param("name", "メール認証統合テスト").param("password", PASSWORD).param("confirmPassword", PASSWORD);
    }

    private void createLegacyPendingAccount(String email, String token, String passwordHash) throws Exception {
        User user = new User();
        user.setName("旧方式の表示名");
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setEmailVerified(false);
        user.setEmailVerificationHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))));
        user.setEmailVerificationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        users.saveAndFlush(user);
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
        return mvc.perform(post("/verify-email/resend").with(client()).with(csrf()).param("email", email))
                .andExpect(redirectedUrl("/verify-email"))
                .andExpect(flash().attribute("sent", true))
                .andExpect(flash().attribute("resendEmail", email.trim()))
                .andExpect(response -> assertThat(response.getFlashMap().containsKey("error")).isFalse())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
    }

    private MvcResult resendPage(MvcResult result) throws Exception {
        return mvc.perform(get("/verify-email").with(client()).flashAttrs(result.getFlashMap()))
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
