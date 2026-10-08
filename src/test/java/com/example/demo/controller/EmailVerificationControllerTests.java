package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmailVerificationControllerTests {
    @Test void openingMailLinkDoesNotConsumeToken() {
        var service = mock(EmailVerificationService.class);
        var controller = new EmailVerificationController(service);
        var model = new ExtendedModelMap();
        String token = "A".repeat(43);
        assertThat(controller.page(token, model)).isEqualTo("auth/verify-email");
        assertThat(model.get("token")).isEqualTo(token);
        verifyNoInteractions(service);
        when(service.verify(token)).thenReturn(true);
        controller.verify(token, model);
        assertThat(model.get("verified")).isEqualTo(true);
    }

    @Test void resendReportsFailureAndRejectsInvalidAddress() {
        var service = mock(EmailVerificationService.class);
        var controller = new EmailVerificationController(service);
        var model = new ExtendedModelMap();
        controller.resend("bad", model);
        assertThat(model).containsKey("error");
        verifyNoInteractions(service);
        doThrow(new MailSendException("unavailable")).when(service).resend("test@example.com");
        model.clear();
        controller.resend("test@example.com", model);
        assertThat(model).containsKey("error").doesNotContainKey("sent");
    }

    @Test void signupOnlyRedirectsAfterSuccessfulMailAndReportsFailure() {
        var service = mock(EmailVerificationService.class);
        var controller = new AuthController(mock(UserRepository.class), service);
        var form = new SignupForm();
        form.setName("テスト"); form.setEmail("test@example.com"); form.setPassword("test-password");
        var errors = new BeanPropertyBindingResult(form, "signupForm");
        assertThat(controller.signup(form, errors)).isEqualTo("redirect:/verify-email");
        doThrow(new MailSendException("unavailable")).when(service).register(form);
        assertThat(controller.signup(form, errors)).isEqualTo("auth/signup");
        assertThat(errors.hasGlobalErrors()).isTrue();
    }
}
