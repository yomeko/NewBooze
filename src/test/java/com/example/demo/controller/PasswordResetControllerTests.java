package com.example.demo.controller;

import com.example.demo.service.PasswordResetService;
import java.net.URI;
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

    @Test void aLinkGetOnlyTransfersTheTokenToTheSessionAndRedirectsWithAnOpaqueFormId() throws Exception {
        String token = "A".repeat(43);
        var entry = mvc.perform(get("/reset-password").param("token", token))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
        MockHttpSession session = (MockHttpSession) entry.getRequest().getSession(false);
        String location = entry.getResponse().getRedirectedUrl();
        assertThat(location).matches("/reset-password\\?formId=[0-9a-f-]{36}").doesNotContain(token);
        String formId = URI.create(location).getQuery().substring("formId=".length());
        String html = mvc.perform(get(URI.create(location)).session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("canReset", true)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("name=\"formId\"", formId, "name=\"newPassword\"", "name=\"confirmPassword\"", "UTF-8で72バイト以内")
                .doesNotContain(token, "name=\"token\"");
        verifyNoInteractions(passwords);
    }

    @Test void aSuccessfulPostConsumesTheSessionTokenAndRedirectsToLogin() throws Exception {
        String token = "A".repeat(43);
        MockHttpSession session = new MockHttpSession();
        String formId = openForm(session, token);
        when(passwords.reset(token, "new-valid-password")).thenReturn(true);
        mvc.perform(post("/reset-password").session(session).param("formId", formId).param("newPassword", "new-valid-password")
                .param("confirmPassword", "new-valid-password"))
                .andExpect(redirectedUrl("/login?passwordReset"));
        mvc.perform(post("/reset-password").session(session).param("formId", formId).param("newPassword", "other-password")
                .param("confirmPassword", "other-password"))
                .andExpect(model().attribute("canReset", false));
        verify(passwords, times(1)).reset(anyString(), anyString());
    }

    @Test void passwordValidationKeepsTheTokenAndNeverRendersThePassword() throws Exception {
        String token = "B".repeat(43);
        MockHttpSession session = new MockHttpSession();
        String formId = openForm(session, token);
        when(passwords.reset(token, "short12")).thenThrow(new IllegalArgumentException(PasswordResetService.PASSWORD_REQUIREMENTS));
        String html = mvc.perform(post("/reset-password").session(session).param("formId", formId).param("newPassword", "short12")
                .param("confirmPassword", "short12"))
                .andExpect(model().attribute("canReset", true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(PasswordResetService.PASSWORD_REQUIREMENTS, formId).doesNotContain("short12", token);
        mvc.perform(get("/reset-password").session(session).param("formId", formId))
                .andExpect(model().attribute("canReset", true));
    }

    @Test void mismatchedConfirmationDoesNotConsumeOrAttemptTheToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String formId = openForm(session, "C".repeat(43));
        mvc.perform(post("/reset-password").session(session).param("formId", formId).param("newPassword", "new-valid-password")
                .param("confirmPassword", "different-password"))
                .andExpect(model().attribute("canReset", true)).andExpect(model().attributeExists("error"));
        verifyNoInteractions(passwords);
    }

    @Test void missingOrInvalidLinksHaveTheSameErrorAndCannotSubmit() throws Exception {
        mvc.perform(get("/reset-password")).andExpect(model().attribute("canReset", false))
                .andExpect(model().attributeExists("error"));
        MockHttpSession session = new MockHttpSession();
        String formId = openForm(session, "C".repeat(43));
        mvc.perform(get("/reset-password").session(session).param("token", "invalid"))
                .andExpect(redirectedUrl("/reset-password"));
        mvc.perform(post("/reset-password").session(session).param("newPassword", "new-valid-password")
                .param("confirmPassword", "new-valid-password"))
                .andExpect(model().attribute("canReset", false));
        mvc.perform(post("/reset-password").session(new MockHttpSession()).param("formId", formId)
                .param("newPassword", "new-valid-password").param("confirmPassword", "new-valid-password"))
                .andExpect(model().attribute("canReset", false));
        mvc.perform(get("/reset-password").session(session).param("formId", formId))
                .andExpect(model().attribute("canReset", true));
        verifyNoInteractions(passwords);
    }

    @Test void openingMoreThanEightFormsDropsOnlyTheOldest() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String oldest = openForm(session, "A".repeat(43));
        String second = openForm(session, "B".repeat(43));
        for (int i = 2; i <= 8; i++) openForm(session, String.valueOf(i).repeat(43));

        mvc.perform(get("/reset-password").session(session).param("formId", oldest))
                .andExpect(model().attribute("canReset", false));
        mvc.perform(get("/reset-password").session(session).param("formId", second))
                .andExpect(model().attribute("canReset", true));
    }

    private String openForm(MockHttpSession session, String token) throws Exception {
        String location = mvc.perform(get("/reset-password").session(session).param("token", token))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        return URI.create(location).getQuery().substring("formId=".length());
    }
}
