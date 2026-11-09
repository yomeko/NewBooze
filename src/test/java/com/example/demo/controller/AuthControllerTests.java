package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.dto.SignupForm;
import com.example.demo.service.EmailVerificationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DBを使わず、登録開始・認証時のパスワード入力・入力エラー表示を確認する。 */
class AuthControllerTests {
    private static final String TOKEN = "signup_" + "A".repeat(43);
    private final UserRepository users = mock(UserRepository.class);
    private final EmailVerificationService verification = mock(EmailVerificationService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new SpringSecurityDialect());
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var confirmationService = new EmailVerificationService(users, encoder, mock(JavaMailSender.class),
                "sender@example.invalid", "https://example.invalid");
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(users, verification),
                        new EmailVerificationController(confirmationService))
                .setViewResolvers(views).build();
    }

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test void signupRequestsOnlyAnEmailAndLinksToPrivacyBeforeRegistration() throws Exception {
        String html = mvc.perform(get("/signup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("href=\"/privacy\"", "登録情報の利用目的", "確認メールを送る")
                .doesNotContain("name=\"password\"", "name=\"name\"");
        verifyNoInteractions(users, verification);
    }

    @Test void signupRequestsVerificationWithoutLoggingIn() throws Exception {
        var result = mvc.perform(post("/signup").param("email", "signup@example.com"))
                .andExpect(redirectedUrl("/verify-email")).andReturn();
        var form = ArgumentCaptor.forClass(SignupForm.class);
        verify(verification).register(form.capture());
        assertThat(form.getValue().getEmail()).isEqualTo("signup@example.com");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
        verify(users, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overLimitPasswords")
    void overLimitPasswordStaysOnConfirmationWithExplanationAndDoesNotSave(String label, String password) throws Exception {
        User user = pendingUser();
        var result = mvc.perform(post("/verify-email").param("token", TOKEN).param("name", "登録テスト")
                        .param("password", password).param("confirmPassword", password))
                .andExpect(status().isOk()).andExpect(view().name("auth/verify-email"))
                .andExpect(model().attributeExists("error"))
                .andReturn();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("UTF-8で72バイト以内", "日本語の多くは24文字", "value=\"登録テスト\"",
                "aria-describedby=\"password-help\"", "role=\"alert\"")
                .doesNotContain("value=\"" + password + "\"");
        assertThat(user.isEmailVerified()).isFalse();
        verify(users, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedPasswords")
    void allowedPasswordCompletesConfirmationWithoutLoggingIn(String label, String password) throws Exception {
        User user = pendingUser();
        mvc.perform(post("/verify-email").param("token", TOKEN).param("name", "登録テスト")
                        .param("password", password).param("confirmPassword", password))
                .andExpect(status().isOk()).andExpect(model().attribute("verified", true));
        assertThat(user.getName()).isEqualTo("登録テスト");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void shortPasswordIsRejectedBeforeEncoding() throws Exception {
        User user = pendingUser();
        mvc.perform(post("/verify-email").param("token", TOKEN).param("name", "登録テスト")
                        .param("password", "short12").param("confirmPassword", "short12"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
        assertThat(user.getPasswordHash()).isNull();
        verify(users, never()).save(any());
    }

    @Test void duplicateEmailStillShowsFieldErrorWithoutSaving() throws Exception {
        when(users.findByEmail("signup@example.com")).thenReturn(Optional.of(new User()));
        mvc.perform(post("/signup").param("email", "signup@example.com"))
                .andExpect(status().isOk()).andExpect(view().name("auth/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "email"));
        verify(users, never()).save(any());
        verifyNoInteractions(verification);
    }

    private User pendingUser() throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
        String hash = "h" + digest.substring(1);
        var user = new User();
        user.setEmailVerified(false);
        user.setEmailVerificationHash(hash);
        user.setEmailVerificationExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        when(users.findByEmailVerificationHash(hash)).thenReturn(Optional.of(user));
        return user;
    }

    static Stream<Arguments> overLimitPasswords() {
        return Stream.of(Arguments.of("ASCII 73 bytes", "a".repeat(73)),
                Arguments.of("Japanese 25 characters, 75 bytes", "あ".repeat(25)),
                Arguments.of("Mixed 73 bytes", "あ".repeat(23) + "abcd"),
                Arguments.of("Emoji 19 characters, 76 bytes", "🍶".repeat(19)));
    }

    static Stream<Arguments> allowedPasswords() {
        return Stream.of(Arguments.of("Minimum 8 characters", "valid123"),
                Arguments.of("ASCII 72 bytes", "a".repeat(72)),
                Arguments.of("Japanese 24 characters, 72 bytes", "あ".repeat(24)),
                Arguments.of("Mixed 72 bytes", "あ".repeat(23) + "abc"),
                Arguments.of("Emoji 18 characters, 72 bytes", "🍶".repeat(18)));
    }
}
