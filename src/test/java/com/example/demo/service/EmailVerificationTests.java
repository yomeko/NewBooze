package com.example.demo.service;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmailVerificationTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
    private UserRepository users;
    private JavaMailSender mail;
    private EmailVerificationService service;
    private User pending;

    @BeforeEach void setup() {
        users = mock(UserRepository.class);
        mail = mock(JavaMailSender.class);
        service = new EmailVerificationService(users, new BCryptPasswordEncoder(), mail,
                "sender@example.com", "https://example.com", clock);
        pending = new User();
        pending.setEmail("pending@example.test");
        pending.setName("テスト");
        pending.setEmailVerified(false);
    }

    @Test void signupBlocksLoginAndSendsHashedToken() throws Exception {
        SignupForm form = new SignupForm();
        form.setEmail(pending.getEmail());
        service.register(form);
        var user = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(user.capture());
        User registered = user.getValue();
        assertThat(new CustomUserDetails(registered).isEnabled()).isFalse();
        assertThat(registered.getPasswordHash()).startsWith("$2");
        assertThat(registered.getName()).isEqualTo("登録手続き中");
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly(pending.getEmail());
        String token = message.getValue().getText().split("token=")[1].split("\n")[0];
        assertThat(token).startsWith("signup_").hasSize(50);
        assertThat(registered.getEmailVerificationHash()).isEqualTo(signupHash(token));
        assertThat(registered.getEmailVerificationExpiresAt()).isAfter(LocalDateTime.now(clock).plusHours(23));
    }

    @Test void confirmationIsSingleUseAndEnablesLogin() throws Exception {
        String token = "signup_" + "A".repeat(43);
        pending.setEmailVerificationHash(signupHash(token));
        pending.setEmailVerificationExpiresAt(LocalDateTime.now(clock).plusHours(1));
        when(users.findByEmailVerificationHash(signupHash(token))).thenReturn(Optional.of(pending));
        assertThat(service.verify(token, "テスト", "test-password", "test-password")).isTrue();
        assertThat(new CustomUserDetails(pending).isEnabled()).isTrue();
        assertThat(pending.getEmailVerificationHash()).isNull();
        assertThat(service.verify(token, "テスト", "test-password", "test-password")).isFalse();
    }

    @Test void expiredOrMalformedTokensNeverEnableLogin() throws Exception {
        String token = "signup_" + "B".repeat(43);
        pending.setEmailVerificationHash(signupHash(token));
        pending.setEmailVerificationExpiresAt(LocalDateTime.now(clock).minusSeconds(1));
        when(users.findByEmailVerificationHash(signupHash(token))).thenReturn(Optional.of(pending));
        assertThat(service.verify(token, "テスト", "test-password", "test-password")).isFalse();
        assertThat(service.verify("bad", "テスト", "test-password", "test-password")).isFalse();
        assertThat(service.verify(null, "テスト", "test-password", "test-password")).isFalse();
        assertThat(new CustomUserDetails(pending).isEnabled()).isFalse();
    }

    @Test void resendRotatesTokenAndLimitsRepeatedSending() {
        pending.setEmailVerificationHash("previous");
        when(users.findForVerificationByEmail(pending.getEmail())).thenReturn(Optional.of(pending));
        service.resend(pending.getEmail());
        assertThat(pending.getEmailVerificationHash()).isNotEqualTo("previous");
        service.resend(pending.getEmail());
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test void resendIsBlockedAt59SecondsAndAllowedAtExactly60Seconds() {
        LocalDateTime now = LocalDateTime.now(clock);
        pending.setEmailVerificationHash("previous");
        pending.setEmailVerificationSentAt(now.minusSeconds(59));
        when(users.findForVerificationByEmail(pending.getEmail())).thenReturn(Optional.of(pending));

        service.resend(pending.getEmail());

        verifyNoInteractions(mail);
        verify(users, never()).saveAndFlush(any(User.class));
        assertThat(pending.getEmailVerificationHash()).isEqualTo("previous");
        assertThat(pending.getEmailVerificationSentAt()).isEqualTo(now.minusSeconds(59));

        pending.setEmailVerificationSentAt(now.minusSeconds(60));
        service.resend(pending.getEmail());

        verify(mail).send(any(SimpleMailMessage.class));
        verify(users).saveAndFlush(pending);
        assertThat(pending.getEmailVerificationHash()).isNotEqualTo("previous");
        assertThat(pending.getEmailVerificationSentAt()).isEqualTo(now);
        assertThat(pending.getEmailVerificationExpiresAt()).isEqualTo(now.plusHours(24));
    }

    @Test void resendInvalidatesThePreviousTokenAndOnlyTheNewTokenEnablesLogin() throws Exception {
        String previousToken = "signup_" + "C".repeat(43);
        pending.setEmailVerificationHash(signupHash(previousToken));
        pending.setEmailVerificationExpiresAt(LocalDateTime.now(clock).plusHours(1));
        when(users.findForVerificationByEmail(pending.getEmail())).thenReturn(Optional.of(pending));
        when(users.findByEmailVerificationHash(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(pending.getEmailVerificationHash())
                        ? Optional.of(pending) : Optional.empty());

        service.resend(pending.getEmail());
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        String newToken = message.getValue().getText().split("token=")[1].split("\n")[0];

        assertThat(newToken).isNotEqualTo(previousToken);
        assertThat(service.verify(previousToken, "テスト", "test-password", "test-password")).isFalse();
        assertThat(new CustomUserDetails(pending).isEnabled()).isFalse();
        assertThat(service.verify(newToken, "テスト", "test-password", "test-password")).isTrue();
        assertThat(new CustomUserDetails(pending).isEnabled()).isTrue();
        assertThat(service.verify(newToken, "テスト", "test-password", "test-password")).isFalse();
    }

    @Test void aTokenExpiresAtExactly24HoursAfterSending() {
        SignupForm form = new SignupForm();
        form.setEmail(pending.getEmail());
        service.register(form);
        var user = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(user.capture());
        User registered = user.getValue();
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        String token = message.getValue().getText().split("token=")[1].split("\n")[0];
        when(users.findByEmailVerificationHash(registered.getEmailVerificationHash()))
                .thenReturn(Optional.of(registered));
        EmailVerificationService atExpiry = new EmailVerificationService(users, new BCryptPasswordEncoder(),
                mail, "sender@example.com", "https://example.com", Clock.offset(clock, Duration.ofHours(24)));

        assertThat(registered.getEmailVerificationExpiresAt())
                .isEqualTo(LocalDateTime.now(clock).plusHours(24));
        assertThat(atExpiry.verify(token, "テスト", "test-password", "test-password")).isFalse();
        assertThat(new CustomUserDetails(registered).isEnabled()).isFalse();
        assertThat(registered.getEmailVerificationHash()).isNotNull();
        verify(users, never()).save(any(User.class));
    }

    @Test void verifiedAndUnknownAccountsReceiveNoResend() {
        pending.setEmailVerified(true);
        when(users.findForVerificationByEmail(pending.getEmail())).thenReturn(Optional.of(pending));
        service.resend(pending.getEmail());
        service.resend("unknown@example.test");
        verifyNoInteractions(mail);
        verify(users, never()).saveAndFlush(any(User.class));
    }

    private String signupHash(String token) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        return "h" + hash.substring(1);
    }
}
