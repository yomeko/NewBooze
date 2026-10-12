package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.validation.BeanPropertyBindingResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailVerificationControllerTests {
    private final UserRepository users = mock(UserRepository.class);
    private final EmailVerificationService verification = mock(EmailVerificationService.class);
    private final AuthController controller = new AuthController(users, verification);

    @Test void registrationGoesToEmailInstructionsInsteadOfLoggingIn() {
        SignupForm form = form();
        assertThat(controller.signup(form, new BeanPropertyBindingResult(form, "signupForm")))
                .isEqualTo("redirect:/resend-verification?sent");
        verify(verification).register(form);
    }

    @Test void duplicateEmailNeverReplacesPendingCredentials() {
        SignupForm form = form();
        when(users.existsByEmail(form.getEmail())).thenReturn(true);
        var errors = new BeanPropertyBindingResult(form, "signupForm");
        assertThat(controller.signup(form, errors)).isEqualTo("auth/signup");
        assertThat(errors.hasFieldErrors("email")).isTrue();
        verifyNoInteractions(verification);
    }

    @Test void sendFailureShowsRetryableRegistrationError() {
        SignupForm form = form();
        doThrow(new MailSendException("unavailable")).when(verification).register(form);
        var errors = new BeanPropertyBindingResult(form, "signupForm");
        assertThat(controller.signup(form, errors)).isEqualTo("auth/signup");
        assertThat(errors.hasGlobalErrors()).isTrue();
    }

    @Test void usedOrExpiredLinksOfferResendAndValidLinksFinishRegistration() {
        when(verification.verify("valid")).thenReturn(true);
        assertThat(controller.verify("valid")).isEqualTo("redirect:/login?registered");
        assertThat(controller.verify("expired")).isEqualTo("redirect:/resend-verification?invalid");
    }

    private SignupForm form() {
        SignupForm form = new SignupForm();
        form.setName("太郎"); form.setEmail("taro@example.com"); form.setPassword("password123");
        return form;
    }
}
