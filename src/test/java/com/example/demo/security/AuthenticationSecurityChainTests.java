package com.example.demo.security;

import com.example.demo.config.SecurityConfig;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 実際のSecurityFilterChainを通し、CSRF・回数制限・認証の順をDBなしで確認する。 */
class AuthenticationSecurityChainTests {
    private static final String PASSWORD = "verified-account-password";
    private AnnotationConfigWebApplicationContext context;
    private UserDetailsService accounts;
    private MockMvc mvc;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityConfig.class, TestConfiguration.class);
        context.refresh();
        accounts = context.getBean(UserDetailsService.class);
        when(accounts.loadUserByUsername(anyString())).thenThrow(new UsernameNotFoundException("invalid credentials"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void closeContext() {
        context.close();
    }

    @Test void repeatedLoginStopsBeforeAuthenticationAfterTheAccountLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", "account@example.invalid")
                    .param("password", "invalid-password"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").with(csrf()).param("username", "account@example.invalid")
                .param("password", "invalid-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        verify(accounts, times(10)).loadUserByUsername("account@example.invalid");
    }

    @Test void invalidCsrfIsForbiddenAndDoesNotConsumeTheLoginBudget() throws Exception {
        for (int i = 0; i < 25; i++) {
            mvc.perform(post("/login").with(csrf().useInvalidToken()).param("username", "account@example.invalid")
                    .param("password", "invalid-password"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(accounts);
        mvc.perform(post("/login").with(csrf()).param("username", "account@example.invalid")
                .param("password", "invalid-password"))
                .andExpect(redirectedUrl("/login?error"));
        verify(accounts).loadUserByUsername("account@example.invalid");
    }

    @Test void encodedLoginPathCannotBypassTheLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post(URI.create("/%6cogin")).with(csrf()).param("username", "encoded@example.invalid")
                    .param("password", "invalid-password"))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post(URI.create("/%6cogin")).with(csrf()).param("username", "encoded@example.invalid")
                .param("password", "invalid-password"))
                .andExpect(status().isTooManyRequests());
        verify(accounts, times(10)).loadUserByUsername("encoded@example.invalid");
    }

    @Test void applicationContextPathCannotBypassTheLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/app/login").contextPath("/app").with(csrf())
                    .param("username", "context@example.invalid").param("password", "invalid-password"))
                    .andExpect(redirectedUrl("/app/login?error"));
        }
        mvc.perform(post("/app/login").contextPath("/app").with(csrf())
                .param("username", "context@example.invalid").param("password", "invalid-password"))
                .andExpect(status().isTooManyRequests());
        verify(accounts, times(10)).loadUserByUsername("context@example.invalid");
    }

    @Test void bcryptDoesNotAcceptAnExtraSuffixAfterA72BytePassword() throws Exception {
        // 24文字の日本語でUTF-8の上限ちょうどにし、末尾が切り捨てられてもログインできないことを確認する。
        String password = "あ".repeat(24);
        var account = new User();
        account.setId(1L);
        account.setEmail("boundary@example.invalid");
        account.setPasswordHash(context.getBean(PasswordEncoder.class).encode(password));
        doReturn(new CustomUserDetails(account)).when(accounts).loadUserByUsername(account.getEmail());

        mvc.perform(post("/login").with(csrf()).param("username", account.getEmail())
                .param("password", password + "a")).andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("username", account.getEmail())
                .param("password", password)).andExpect(redirectedUrl("/"));
    }

    @Test void memberReturnsToTheRequestedPageWithItsQueryAfterLogin() throws Exception {
        var account = prepareAccount(false, false);
        var visit = mvc.perform(get("/app/ranking?page=2").contextPath("/app"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) visit.getRequest().getSession(false);

        mvc.perform(post("/app/login").contextPath("/app").session(session).with(csrf())
                .param("username", account.getEmail()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/app/ranking?page=2"));
    }

    @Test void memberIsNotSentBackToAPostActionOrAnAdminPage() throws Exception {
        var account = prepareAccount(false, false);
        var session = new MockHttpSession(context.getServletContext());
        mvc.perform(post("/posts").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin").session(session)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/login").session(session).with(csrf())
                .param("username", account.getEmail()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));
    }

    @Test void adminAndTemporaryPasswordDestinationsTakePrecedence() throws Exception {
        for (boolean temporaryPassword : new boolean[]{false, true}) {
            var account = prepareAccount(true, temporaryPassword);
            var visit = mvc.perform(get("/posts")).andExpect(status().is3xxRedirection()).andReturn();
            var session = (MockHttpSession) visit.getRequest().getSession(false);
            mvc.perform(post("/login").session(session).with(csrf())
                    .param("username", account.getEmail()).param("password", PASSWORD))
                    .andExpect(redirectedUrl(temporaryPassword ? "/mypage?passwordChangeRequired" : "/admin"));
        }
    }

    private User prepareAccount(boolean admin, boolean temporaryPassword) {
        var account = new User();
        account.setId(1L);
        account.setEmail("login-redirect@example.invalid");
        account.setPasswordHash(context.getBean(PasswordEncoder.class).encode(PASSWORD));
        account.setAdmin(admin);
        account.setTemporaryPassword(temporaryPassword);
        doReturn(new CustomUserDetails(account)).when(accounts).loadUserByUsername(account.getEmail());
        return account;
    }

    @Configuration
    @EnableWebMvc
    static class TestConfiguration {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean AuthenticationRateLimiter limiter() { return new AuthenticationRateLimiter(); }
        @Bean UserDetailsService accounts() { return mock(UserDetailsService.class); }
    }
}
