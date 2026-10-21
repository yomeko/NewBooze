package com.example.demo.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationRateLimiterTests {
    private final MutableClock clock = new MutableClock();
    private final AuthenticationRateLimiter limiter = new AuthenticationRateLimiter(clock, 20_000);

    @Test void distributedLoginAttemptsStillShareTheAccountLimit() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.check("/login", "client-" + i, i % 2 == 0 ? "User@Example.invalid" : " user@example.invalid ").allowed()).isTrue();
        }
        var blocked = limiter.check("/login", "another-client", "user@example.invalid");
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(300);
        assertThat(limiter.check("/login", "another-client", "different@example.invalid").allowed()).isTrue();
    }

    @Test void changingAccountsCannotBypassTheClientLimit() {
        for (int i = 0; i < 20; i++) assertThat(limiter.check("/login", "client", "user-" + i).allowed()).isTrue();
        assertThat(limiter.check("/login", "client", "new-user").allowed()).isFalse();
    }

    @Test void requestsAtTheExactEndOfTheWindowAreAllowedWithoutExtendingIt() {
        for (int i = 0; i < 5; i++) assertThat(limiter.check("/forgot-password", "client", null).allowed()).isTrue();
        clock.advance(Duration.ofSeconds(899));
        assertThat(limiter.check("/forgot-password", "client", null).retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.check("/forgot-password", "client", null).allowed()).isTrue();
    }

    @Test void actionsAndClientsHaveIndependentBudgets() {
        for (int i = 0; i < 5; i++) limiter.check("/signup", "client", null);
        assertThat(limiter.check("/signup", "client", null).allowed()).isFalse();
        assertThat(limiter.check("/forgot-password", "client", null).allowed()).isTrue();
        assertThat(limiter.check("/signup", "other-client", null).allowed()).isTrue();
        assertThat(limiter.check("/search", "client", null).allowed()).isTrue();
    }

    @Test void fullStorageDoesNotEvictAnActiveRestriction() {
        var small = new AuthenticationRateLimiter(clock, 1);
        for (int i = 0; i < 5; i++) assertThat(small.check("/signup", "first", null).allowed()).isTrue();
        assertThat(small.check("/signup", "attacker-changing-address", null).allowed()).isFalse();
        assertThat(small.check("/signup", "first", null).allowed()).isFalse();
        clock.advance(Duration.ofMinutes(15));
        assertThat(small.check("/signup", "next", null).allowed()).isTrue();
    }

    @Test void concurrentRequestsCannotSpendTheSameBudgetTwice() throws Exception {
        try (var executor = Executors.newFixedThreadPool(10)) {
            var requests = IntStream.range(0, 30).mapToObj(i -> (Callable<Boolean>) () ->
                    limiter.check("/forgot-password", "same-client", null).allowed()).toList();
            int accepted = 0;
            for (var result : executor.invokeAll(requests)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
