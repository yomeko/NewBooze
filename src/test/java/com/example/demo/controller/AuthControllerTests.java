package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContext;
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

/** DBを使わず、登録のバイト境界・入力エラー表示・成功後の認証を確認する。 */
class AuthControllerTests {
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder());
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
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(users, encoder))
                .setViewResolvers(views).build();
    }

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test void signupExplainsPasswordLimitsAndLinksToPrivacyBeforeRegistration() throws Exception {
        String html = mvc.perform(get("/signup")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("UTF-8で72バイト以内", "日本語の多くは24文字", "href=\"/privacy\"",
                "登録情報の利用目的", "aria-describedby=\"password-help\"");
        verifyNoInteractions(users, encoder);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overLimitPasswords")
    void overLimitPasswordStaysOnSignupWithExplanationAndDoesNotSave(String label, String password) throws Exception {
        var result = mvc.perform(post("/signup").param("name", "登録テスト").param("email", "signup@example.com")
                .param("password", password))
                .andExpect(status().isOk()).andExpect(view().name("auth/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "password"))
                .andReturn();
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("パスワードはUTF-8で72バイト以内にしてください", "value=\"登録テスト\"",
                "value=\"signup@example.com\"", "aria-invalid=\"true\"", "role=\"alert\"")
                .doesNotContain("value=\"" + password + "\"");
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users, encoder);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedPasswords")
    void allowedPasswordIsHashedSavedAndAutomaticallyLoggedIn(String label, String password) throws Exception {
        when(users.findByEmail("signup@example.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        var result = mvc.perform(post("/signup").param("name", "登録テスト").param("email", "signup@example.com")
                .param("password", password))
                .andExpect(redirectedUrl("/diagnosis")).andReturn();
        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(password);
        assertThat(encoder.matches(password, saved.getValue().getPasswordHash())).isTrue();
        assertThat(saved.getValue().isAdmin()).isFalse();
        SecurityContext context = (SecurityContext) result.getRequest().getSession(false)
                .getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(((CustomUserDetails) context.getAuthentication().getPrincipal()).getUserId()).isEqualTo(42L);
    }

    @Test void shortPasswordIsStillRejectedBeforeEncoding() throws Exception {
        mvc.perform(post("/signup").param("name", "登録テスト").param("email", "signup@example.com")
                .param("password", "short12"))
                .andExpect(status().isOk()).andExpect(view().name("auth/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "password"));
        verifyNoInteractions(users, encoder);
    }

    @Test void duplicateEmailStillShowsFieldErrorWithoutSaving() throws Exception {
        when(users.findByEmail("signup@example.com")).thenReturn(Optional.of(new User()));
        mvc.perform(post("/signup").param("name", "登録テスト").param("email", "signup@example.com")
                .param("password", "valid123"))
                .andExpect(status().isOk()).andExpect(view().name("auth/signup"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "email"));
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
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
