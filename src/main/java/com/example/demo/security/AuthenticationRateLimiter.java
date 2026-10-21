package com.example.demo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 認証操作の回数を制限する。IP・ログインIDの原文は保持せず、記録数にも上限を設ける。 */
@Component
public class AuthenticationRateLimiter {
    private static final int MAX_BUCKETS = 20_000;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    private final int maximumBuckets;

    public AuthenticationRateLimiter() {
        this(Clock.systemUTC(), MAX_BUCKETS);
    }

    AuthenticationRateLimiter(Clock clock, int maximumBuckets) {
        this.clock = clock;
        this.maximumBuckets = maximumBuckets;
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        static Decision accept() { return new Decision(true, 0); }
    }

    private record Rule(String key, int limit, Duration duration) {}
    private record Bucket(int count, Instant expiresAt) {}

    /** 複数の上限を一緒に判定し、許可した要求だけを数える。待機しても制限時間は延長しない。 */
    public synchronized Decision check(String action, String address, String account) {
        List<Rule> rules = rules(action, address, account);
        if (rules.isEmpty()) return Decision.accept();
        Instant now = clock.instant();
        long retry = 0;
        int needed = 0;
        for (Rule rule : rules) {
            Bucket bucket = buckets.get(rule.key());
            if (bucket == null || !bucket.expiresAt().isAfter(now)) {
                needed++;
            } else if (bucket.count() >= rule.limit()) {
                retry = Math.max(retry, remaining(now, bucket.expiresAt()));
            }
        }
        if (retry > 0) return new Decision(false, retry);

        // 有効な記録を追い出すと攻撃者が制限を回避できるため、満杯時は新しい要求を待たせる。
        if (buckets.size() + needed > maximumBuckets) {
            buckets.values().removeIf(bucket -> !bucket.expiresAt().isAfter(now));
            if (buckets.size() + needed > maximumBuckets) {
                long earliest = buckets.values().stream()
                        .mapToLong(bucket -> remaining(now, bucket.expiresAt())).min().orElse(1);
                return new Decision(false, earliest);
            }
        }
        for (Rule rule : rules) {
            Bucket bucket = buckets.get(rule.key());
            if (bucket == null || !bucket.expiresAt().isAfter(now)) {
                buckets.put(rule.key(), new Bucket(1, now.plus(rule.duration())));
            } else {
                buckets.put(rule.key(), new Bucket(bucket.count() + 1, bucket.expiresAt()));
            }
        }
        return Decision.accept();
    }

    private List<Rule> rules(String action, String address, String account) {
        var rules = new ArrayList<Rule>();
        String ip = address == null ? "unknown" : address;
        if ("/login".equals(action)) {
            rules.add(new Rule(hash(action + ":ip:" + ip), 20, Duration.ofMinutes(5)));
            String login = account == null ? "" : account.strip().toLowerCase(Locale.ROOT);
            rules.add(new Rule(hash(action + ":account:" + login), 10, Duration.ofMinutes(5)));
        } else {
            int limit = switch (action) {
                case "/signup", "/forgot-password" -> 5;
                case "/reset-password", "/verify-email/resend" -> 10;
                case "/verify-email" -> 20;
                default -> 0;
            };
            if (limit != 0) rules.add(new Rule(hash(action + ":ip:" + ip), limit, Duration.ofMinutes(15)));
        }
        return rules;
    }

    private static long remaining(Instant now, Instant end) {
        return Math.max(1, (Duration.between(now, end).toMillis() + 999) / 1000);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
