package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 再設定の有効期限・送信間隔・パスワード境界を実時間の待機や外部送信なしで確認する。 */
class PasswordResetTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private UserRepository users;
    private JavaMailSender mail;
    private PasswordResetService service;
    private User user;

    @BeforeEach void setup() {
        users = mock(UserRepository.class);
        mail = mock(JavaMailSender.class);
        service = service(clock);
        user = new User();
        user.setEmail("verified@example.test");
        user.setName("再設定テスト");
        user.setPasswordHash("original-password-hash");
        when(users.findForPasswordResetByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test void requestKeepsTheCurrentPasswordAndSendsOnlyAHashedThirtyMinuteToken() throws Exception {
        service.requestFor("  " + user.getEmail() + "  ");
        String token = sentToken();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(user.getPasswordResetHash()).isEqualTo(hash(token)).isNotEqualTo(token);
        assertThat(user.getPasswordResetExpiresAt()).isEqualTo(LocalDateTime.now(clock).plusMinutes(30));
        assertThat(user.getPasswordResetSentAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(user.getPasswordHash()).isEqualTo("original-password-hash");
        assertThat(user.isTemporaryPassword()).isFalse();
        verify(users).saveAndFlush(user);
    }

    @Test void unknownOrUnverifiedAccountsAreNotSentAResetLink() {
        user.setEmailVerified(false);
        service.requestFor(user.getEmail());
        service.requestFor("unknown@example.test");
        verifyNoInteractions(mail);
        verify(users, never()).saveAndFlush(any());
        assertThat(user.getPasswordHash()).isEqualTo("original-password-hash");
    }

    @Test void sendsAgainAtSixtySecondsAndInvalidatesThePreviousToken() throws Exception {
        user.setPasswordResetHash(hash("A".repeat(43)));
        user.setPasswordResetSentAt(LocalDateTime.now(clock).minusSeconds(59));
        service.requestFor(user.getEmail());
        verifyNoInteractions(mail);
        assertThat(user.getPasswordResetHash()).isEqualTo(hash("A".repeat(43)));

        user.setPasswordResetSentAt(LocalDateTime.now(clock).minusSeconds(60));
        service.requestFor(user.getEmail());
        String newToken = sentToken();
        when(users.findByPasswordResetHash(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(user.getPasswordResetHash()) ? Optional.of(user) : Optional.empty());
        assertThat(service.reset("A".repeat(43), "new-valid-password")).isFalse();
        assertThat(service.reset(newToken, "new-valid-password")).isTrue();
    }

    @Test void resetChangesThePasswordOnlyOnceAndClearsTheLegacyTemporaryFlag() throws Exception {
        String token = prepareToken();
        user.setTemporaryPassword(true);
        assertThat(service.reset(token, "new-valid-password")).isTrue();
        assertThat(encoder.matches("new-valid-password", user.getPasswordHash())).isTrue();
        assertThat(user.isTemporaryPassword()).isFalse();
        assertThat(user.getPasswordResetHash()).isNull();
        assertThat(user.getPasswordResetExpiresAt()).isNull();
        assertThat(service.reset(token, "another-password")).isFalse();
        assertThat(encoder.matches("new-valid-password", user.getPasswordHash())).isTrue();
    }

    @Test void tokenExpiresAtExactlyThirtyMinutesAndCannotResetAnUnverifiedAccount() throws Exception {
        String token = prepareToken();
        assertThat(service(Clock.offset(clock, Duration.ofMinutes(30))).reset(token, "new-valid-password")).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("original-password-hash");
        user.setEmailVerified(false);
        assertThat(service.reset(token, "new-valid-password")).isFalse();
        verify(users, never()).saveAndFlush(any());
    }

    @Test void malformedTokensNeverQueryOrChangeAUser() {
        assertThat(service.reset(null, "new-valid-password")).isFalse();
        assertThat(service.reset("bad", "new-valid-password")).isFalse();
        verify(users, never()).findByPasswordResetHash(anyString());
    }

    @ParameterizedTest @MethodSource("invalidPasswords")
    void invalidPasswordsDoNotConsumeTheLink(String password) throws Exception {
        String token = prepareToken();
        assertThatThrownBy(() -> service.reset(token, password)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PasswordResetService.PASSWORD_REQUIREMENTS);
        assertThat(user.getPasswordResetHash()).isEqualTo(hash(token));
        assertThat(user.getPasswordHash()).isEqualTo("original-password-hash");
        verify(users, never()).saveAndFlush(any());
    }

    @ParameterizedTest @MethodSource("validPasswords")
    void passwordsAtTheCharacterAndUtf8BoundariesAreAccepted(String password) throws Exception {
        assertThat(service.reset(prepareToken(), password)).isTrue();
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
    }

    @Test void publicLinksMustUseHttpsAndCannotContainCredentialsQueryOrFragment() {
        for (String url : new String[] {"http://example.com", "https://user:pass@example.com", "https://example.com?q=1", "https://example.com/#fragment"}) {
            assertThatThrownBy(() -> new PasswordResetService(users, encoder, mail, "sender@example.com", url, clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> new PasswordResetService(users, encoder, mail, "sender@example.com", "http://localhost:8080", clock))
                .doesNotThrowAnyException();
    }

    private PasswordResetService service(Clock sourceClock) {
        return new PasswordResetService(users, encoder, mail, "sender@example.com", "https://example.com/", sourceClock);
    }

    private String prepareToken() throws Exception {
        String token = "B".repeat(43);
        user.setPasswordResetHash(hash(token));
        user.setPasswordResetExpiresAt(LocalDateTime.now(clock).plusMinutes(30));
        when(users.findByPasswordResetHash(hash(token))).thenReturn(Optional.of(user));
        return token;
    }

    private String sentToken() {
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly(user.getEmail());
        assertThat(message.getValue().getText()).contains("https://example.com/reset-password?token=")
                .doesNotContain("仮パスワード:");
        return message.getValue().getText().split("token=")[1].split("\n")[0];
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    static Stream<String> invalidPasswords() {
        return Stream.of("short12", "a".repeat(73), "あ".repeat(25), "🍶".repeat(19));
    }

    static Stream<String> validPasswords() {
        return Stream.of("valid123", "a".repeat(72), "あ".repeat(24), "🍶".repeat(18));
    }
}
