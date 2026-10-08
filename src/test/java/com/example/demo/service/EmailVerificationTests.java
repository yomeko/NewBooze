package com.example.demo.service;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
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
    private UserRepository users;
    private JavaMailSender mail;
    private EmailVerificationService service;
    private User pending;

    @BeforeEach void setup() {
        users = mock(UserRepository.class);
        mail = mock(JavaMailSender.class);
        service = new EmailVerificationService(users, new BCryptPasswordEncoder(), mail,
                "sender@example.com", "https://example.com");
        pending = new User();
        pending.setEmail("sotsugyoukenkyuyou2026@gmail.com");
        pending.setName("テスト");
        pending.setEmailVerified(false);
    }

    @Test void signupBlocksLoginAndSendsHashedToken() throws Exception {
        SignupForm form = new SignupForm();
        form.setName("テスト"); form.setEmail(pending.getEmail()); form.setPassword("test-password");
        service.register(form);
        var user = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(user.capture());
        User registered = user.getValue();
        assertThat(new CustomUserDetails(registered).isEnabled()).isFalse();
        assertThat(new BCryptPasswordEncoder().matches("test-password", registered.getPasswordHash())).isTrue();
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly(pending.getEmail());
        String token = message.getValue().getText().split("token=")[1].split("\n")[0];
        assertThat(token).hasSize(43);
        assertThat(registered.getEmailVerificationHash()).isEqualTo(hash(token));
        assertThat(registered.getEmailVerificationExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
    }

    @Test void confirmationIsSingleUseAndEnablesLogin() throws Exception {
        String token = "A".repeat(43);
        pending.setEmailVerificationHash(hash(token));
        pending.setEmailVerificationExpiresAt(LocalDateTime.now().plusHours(1));
        when(users.findByEmailVerificationHash(hash(token))).thenReturn(Optional.of(pending));
        assertThat(service.verify(token)).isTrue();
        assertThat(new CustomUserDetails(pending).isEnabled()).isTrue();
        assertThat(pending.getEmailVerificationHash()).isNull();
        assertThat(service.verify(token)).isFalse();
    }

    @Test void expiredOrMalformedTokensNeverEnableLogin() throws Exception {
        String token = "B".repeat(43);
        pending.setEmailVerificationExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(users.findByEmailVerificationHash(hash(token))).thenReturn(Optional.of(pending));
        assertThat(service.verify(token)).isFalse();
        assertThat(service.verify("bad")).isFalse();
        assertThat(service.verify(null)).isFalse();
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

    @Test void verifiedAndUnknownAccountsReceiveNoResend() {
        pending.setEmailVerified(true);
        when(users.findForVerificationByEmail(pending.getEmail())).thenReturn(Optional.of(pending));
        service.resend(pending.getEmail());
        service.resend("unknown@example.com");
        verifyNoInteractions(mail);
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
