package com.example.demo.service;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final EmailVerificationService service = new EmailVerificationService(
            users, encoder, mail, "sender@example.com", "https://example.com/");

    @Test void registrationSendsLinkAndStoresOnlyHashWithExpiry() {
        SignupForm form = new SignupForm();
        form.setName("太郎"); form.setEmail("taro@example.com"); form.setPassword("password123");
        service.register(form);
        var captor = ArgumentCaptor.forClass(User.class);
        verify(users, times(2)).saveAndFlush(captor.capture());
        User user = captor.getValue();
        assertThat(new CustomUserDetails(user).isEnabled()).isFalse();
        assertThat(encoder.matches("password123", user.getPasswordHash())).isTrue();
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        String body = message.getValue().getText();
        String token = body.split("token=")[1].split("\n")[0];
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(user.getVerificationTokenHash()).isEqualTo(EmailVerificationService.hash(token));
        assertThat(user.getVerificationExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(body).contains("https://example.com/verify-email?token=");
    }

    @Test void resendReplacesExpiredTokenButHonorsCooldownAndVerifiedUsers() {
        User pending = new User(); pending.setEmail("taro@example.com"); pending.setEmailVerified(false);
        pending.setVerificationTokenHash("old");
        pending.setVerificationSentAt(LocalDateTime.now().minusMinutes(2));
        when(users.findForVerificationByEmail(anyString())).thenReturn(Optional.of(pending));
        service.resend("taro@example.com");
        assertThat(pending.getVerificationTokenHash()).isNotEqualTo("old");
        service.resend("taro@example.com");
        pending.setEmailVerified(true);
        service.resend("taro@example.com");
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test void verificationHandlesUsedExpiredAndMalformedTokens() {
        String token = "a".repeat(43);
        when(users.verifyEmail(eq(EmailVerificationService.hash(token)), any())).thenReturn(1, 0);
        assertThat(service.verify(token)).isTrue();
        assertThat(service.verify(token)).isFalse();
        assertThat(service.verify(null)).isFalse();
        assertThat(service.verify("bad")).isFalse();
        verify(users, times(2)).verifyEmail(anyString(), any());
    }

    @Test void smtpFailurePropagatesForTransactionRollback() {
        SignupForm form = new SignupForm();
        form.setName("太郎"); form.setEmail("taro@example.com"); form.setPassword("password123");
        doThrow(new MailSendException("unavailable")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> service.register(form)).isInstanceOf(MailSendException.class);
    }
}
