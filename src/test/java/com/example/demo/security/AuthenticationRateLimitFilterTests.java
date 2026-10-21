package com.example.demo.security;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationRateLimitFilterTests {
    private final AuthenticationRateLimitFilter filter = new AuthenticationRateLimitFilter(new AuthenticationRateLimiter());

    @Test void blocksRepeatedPostsBeforeCallingTheHandlerAndDoesNotEchoPersonalData() throws Exception {
        var handled = new AtomicInteger();
        MockHttpServletResponse response = null;
        for (int i = 0; i < 6; i++) {
            var request = request("POST", "/forgot-password");
            request.addParameter("email", "private@example.invalid");
            response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> handled.incrementAndGet());
        }
        assertThat(handled.get()).isEqualTo(5);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(899L, 900L);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("操作が続いています")
                .doesNotContain("private@example.invalid");
    }

    @Test void spoofedForwardingHeadersDoNotBypassTheClientBudget() throws Exception {
        var handled = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            var request = request("POST", "/signup");
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            request.addHeader("Forwarded", "for=198.51.100." + i);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> handled.incrementAndGet());
        }
        assertThat(handled.get()).isEqualTo(5);
    }

    @Test void readsAndOrdinaryUpdatesAreNotLimited() throws Exception {
        var handled = new AtomicInteger();
        for (int i = 0; i < 30; i++) {
            filter.doFilter(request("GET", "/login"), new MockHttpServletResponse(), (req, res) -> handled.incrementAndGet());
            filter.doFilter(request("POST", "/mypage/password"), new MockHttpServletResponse(), (req, res) -> handled.incrementAndGet());
        }
        assertThat(handled.get()).isEqualTo(60);
    }

    @Test void contextPathDoesNotBypassTheRestriction() throws Exception {
        var handled = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            var request = request("POST", "/app/signup");
            request.setContextPath("/app");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> handled.incrementAndGet());
        }
        assertThat(handled.get()).isEqualTo(5);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.1");
        return request;
    }
}
