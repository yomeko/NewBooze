package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AccountSessionValidationFilterTests {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AccountSessionValidationFilter filter = new AccountSessionValidationFilter(users, encoder);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test void unchangedAccountKeepsSessionAndContinues() throws Exception {
        User current = signedInAccount(false);
        when(users.findById(42L)).thenReturn(Optional.of(current));
        var request = request("/mypage");
        var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("revokedStates")
    void changedAuthenticationRevokesSessionBeforeProtectedHandler(String label, Consumer<User> change) throws Exception {
        User current = signedInAccount(true);
        change.accept(current);
        when(users.findById(42L)).thenReturn(Optional.of(current));
        var request = request("/admin");
        request.setContextPath("/newbooze");
        var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verifyNoInteractions(chain);
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/newbooze/login?sessionExpired");
    }

    static Stream<Arguments> revokedStates() {
        return Stream.of(
                Arguments.of("password reset", (Consumer<User>) user -> user.setPasswordHash("new-hash")),
                Arguments.of("email unverified", (Consumer<User>) user -> user.setEmailVerified(false)),
                Arguments.of("email changed", (Consumer<User>) user -> user.setEmail("other@example.com")),
                Arguments.of("admin role removed", (Consumer<User>) user -> user.setAdmin(false)));
    }

    @Test void deletedAccountRevokesSession() throws Exception {
        signedInAccount(false);
        when(users.findById(42L)).thenReturn(Optional.empty());
        var request = request("/mypage");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verifyNoInteractions(chain);
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?sessionExpired");
    }

    @Test void legacyDefaultAdministratorSessionIsAlsoRevoked() throws Exception {
        User current = signedInAccount(true);
        when(users.findById(42L)).thenReturn(Optional.of(current));
        when(encoder.matches("admin", "current-hash")).thenReturn(true);
        var request = request("/admin");
        var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(chain);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?sessionExpired");
    }

    @Test void newlyGrantedAdminRoleRevokesSessionAndRequiresFreshAuthentication() throws Exception {
        User current = signedInAccount(false);
        current.setAdmin(true);
        when(users.findById(42L)).thenReturn(Optional.of(current));
        var request = request("/mypage");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verifyNoInteractions(chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?sessionExpired");
    }

    @Test void unchangedAdminPasswordIsOnlyCheckedOncePerSession() throws Exception {
        User current = signedInAccount(true);
        when(users.findById(42L)).thenReturn(Optional.of(current));
        for (int i = 0; i < 2; i++) {
            filter.doFilter(request("/admin"), new MockHttpServletResponse(), chain);
        }
        verify(encoder, times(1)).matches("admin", "current-hash");
        verify(users, times(2)).findById(42L);
    }

    @Test void staticFilesDoNotQueryDatabase() throws Exception {
        signedInAccount(false);
        var request = request("/css/app.css");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verifyNoInteractions(users, encoder);
        verify(chain).doFilter(request, response);
    }

    @Test void anonymousRequestsDoNotQueryDatabase() throws Exception {
        var request = request("/login");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verifyNoInteractions(users, encoder);
        verify(chain).doFilter(request, response);
    }

    @Test void testPrincipalsWithoutApplicationAccountsAreNotReloaded() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("test-user", null, java.util.List.of()));
        var request = request("/mypage");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verifyNoInteractions(users, encoder);
        verify(chain).doFilter(request, response);
    }

    private User signedInAccount(boolean admin) {
        User current = new User();
        current.setId(42L);
        current.setEmail("member@example.com");
        current.setName("会員");
        current.setPasswordHash("current-hash");
        current.setAdmin(admin);
        current.setEmailVerified(true);
        CustomUserDetails principal = new CustomUserDetails(current);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        return current;
    }

    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
