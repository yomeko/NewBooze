package com.example.demo.controller;

import com.example.demo.dto.SignupForm;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.validation.BeanPropertyBindingResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionTests {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void signupRotatesAnonymousSessionBeforeSavingAuthentication() {
        var users = mock(UserRepository.class);
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(42L);
            return user;
        });
        var form = form("test-only-password-2026");
        var request = new MockHttpServletRequest();
        String anonymousId = request.getSession().getId();
        assertThat(new AuthController(users, new BCryptPasswordEncoder(4)).signup(form,
                new BeanPropertyBindingResult(form, "signupForm"), request, new MockHttpServletResponse()))
                .isEqualTo("redirect:/diagnosis");
        assertThat(request.getSession().getId()).isNotEqualTo(anonymousId);
        var context = (SecurityContext) request.getSession().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(context.getAuthentication().getName()).isEqualTo("new@example.test");
    }

    @Test void rejectsPasswordThatExceedsBcryptByteLimitWithoutSavingUser() {
        var users = mock(UserRepository.class);
        var form = form("あ".repeat(25));
        var errors = new BeanPropertyBindingResult(form, "signupForm");
        assertThat(new AuthController(users, new BCryptPasswordEncoder(4)).signup(form, errors,
                new MockHttpServletRequest(), new MockHttpServletResponse())).isEqualTo("auth/signup");
        assertThat(errors.hasFieldErrors("password")).isTrue();
        verifyNoInteractions(users);
    }

    private SignupForm form(String password) {
        var form = new SignupForm();
        form.setName("新規ユーザー");
        form.setEmail("new@example.test");
        form.setPassword(password);
        return form;
    }
}
