package com.example.demo.controller;

import com.example.demo.service.PasswordResetService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpSession;
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

/** 画面・URLから秘密が漏れず、GETではパスワードを更新しないことを確認する。 */
class PasswordResetControllerTests {
    private final PasswordResetService passwords = mock(PasswordResetService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver); engine.addDialect(new SpringSecurityDialect());
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine); views.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(passwords)).setViewResolvers(views).build();
    }

    @Test void requestUsesTheSamePublicResponseEvenWhenSmtpFails() throws Exception {
        mvc.perform(post("/forgot-password").param("email", "unknown@example.test"))
                .andExpect(redirectedUrl("/forgot-password")).andExpect(flash().attribute("sent", true));
        doThrow(new MailSendException("simulated failure")).when(passwords).requestFor("known@example.test");
        mvc.perform(post("/forgot-password").param("email", "known@example.test"))
                .andExpect(redirectedUrl("/forgot-password")).andExpect(flash().attribute("sent", true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test void malformedEmailDoesNotReachTheService() throws Exception {
        mvc.perform(post("/forgot-password").param("email", "bad"))
                .andExpect(view().name("auth/forgot-password")).andExpect(model().attributeExists("error"));
        verifyNoInteractions(passwords);
    }

    @Test void aLinkGetOnlyTransfersTheTokenToTheSessionAndRedirectsToACleanUrl() throws Exception {
        String token = "A".repeat(43);
        var entry = mvc.perform(get("/reset-password").param("token", token))
                .andExpect(redirectedUrl("/reset-password"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
        MockHttpSession session = (MockHttpSession) entry.getRequest().getSession(false);
        assertThat(session.getAttribute(PasswordResetController.RESET_TOKEN_SESSION_ATTRIBUTE)).isEqualTo(token);
        String html = mvc.perform(get("/reset-password").session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("canReset", true)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("name=\"newPassword\"", "name=\"confirmPassword\"", "UTF-8で72バイト以内")
                .doesNotContain(token, "name=\"token\"");
        verifyNoInteractions(passwords);
    }

    @Test void aSuccessfulPostConsumesTheSessionTokenAndRedirectsToLogin() throws Exception {
        String token = "A".repeat(43);
        MockHttpSession session = session(token);
        when(passwords.reset(token, "new-valid-password")).thenReturn(true);
        mvc.perform(post("/reset-password").session(session).param("newPassword", "new-valid-password")
                .param("confirmPassword", "new-valid-password"))
                .andExpect(redirectedUrl("/login?passwordReset"));
        assertThat(session.getAttribute(PasswordResetController.RESET_TOKEN_SESSION_ATTRIBUTE)).isNull();
        mvc.perform(post("/reset-password").session(session).param("newPassword", "other-password")
                .param("confirmPassword", "other-password"))
                .andExpect(model().attribute("canReset", false));
        verify(passwords, times(1)).reset(anyString(), anyString());
    }

    @Test void passwordValidationKeepsTheTokenAndNeverRendersThePassword() throws Exception {
        String token = "B".repeat(43);
        MockHttpSession session = session(token);
        when(passwords.reset(token, "short12")).thenThrow(new IllegalArgumentException(PasswordResetService.PASSWORD_REQUIREMENTS));
        String html = mvc.perform(post("/reset-password").session(session).param("newPassword", "short12")
                .param("confirmPassword", "short12"))
                .andExpect(model().attribute("canReset", true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(PasswordResetService.PASSWORD_REQUIREMENTS).doesNotContain("short12", token);
        assertThat(session.getAttribute(PasswordResetController.RESET_TOKEN_SESSION_ATTRIBUTE)).isEqualTo(token);
    }

    @Test void mismatchedConfirmationDoesNotConsumeOrAttemptTheToken() throws Exception {
        MockHttpSession session = session("C".repeat(43));
        mvc.perform(post("/reset-password").session(session).param("newPassword", "new-valid-password")
                .param("confirmPassword", "different-password"))
                .andExpect(model().attribute("canReset", true)).andExpect(model().attributeExists("error"));
        verifyNoInteractions(passwords);
    }

    @Test void missingOrInvalidLinksHaveTheSameErrorAndCannotSubmit() throws Exception {
        mvc.perform(get("/reset-password")).andExpect(model().attribute("canReset", false))
                .andExpect(model().attributeExists("error"));
        MockHttpSession session = session("C".repeat(43));
        mvc.perform(get("/reset-password").session(session).param("token", "invalid"))
                .andExpect(redirectedUrl("/reset-password"));
        assertThat(session.getAttribute(PasswordResetController.RESET_TOKEN_SESSION_ATTRIBUTE)).isNull();
        mvc.perform(post("/reset-password").session(session).param("newPassword", "new-valid-password")
                .param("confirmPassword", "new-valid-password"))
                .andExpect(model().attribute("canReset", false));
        verifyNoInteractions(passwords);
    }

    private MockHttpSession session(String token) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PasswordResetController.RESET_TOKEN_SESSION_ATTRIBUTE, token);
        return session;
    }
}
