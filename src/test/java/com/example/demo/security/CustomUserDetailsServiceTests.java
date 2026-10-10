package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTests {
    @Test void rejectsLegacyDefaultAdministratorEvenWhenAlreadyStored() {
        var users = mock(UserRepository.class);
        var encoder = new BCryptPasswordEncoder(4);
        User legacy = new User();
        legacy.setAdmin(true);
        legacy.setPasswordHash(encoder.encode("admin"));
        when(users.findByEmail("admin")).thenReturn(Optional.of(legacy));
        assertThatThrownBy(() -> new CustomUserDetailsService(users, encoder).loadUserByUsername("admin"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test void acceptsAdministratorAfterPasswordRotation() {
        var users = mock(UserRepository.class);
        var encoder = new BCryptPasswordEncoder(4);
        User admin = new User();
        admin.setAdmin(true);
        admin.setPasswordHash(encoder.encode("test-only-password-2026"));
        when(users.findByEmail("admin")).thenReturn(Optional.of(admin));
        assertThat(new CustomUserDetailsService(users, encoder).loadUserByUsername("admin").getAuthorities())
                .extracting(a -> a.getAuthority()).contains("ROLE_ADMIN");
    }

    @Test void unknownAccountErrorDoesNotIncludeEmail() {
        var users = mock(UserRepository.class);
        when(users.findByEmail("private@example.test")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new CustomUserDetailsService(users, new BCryptPasswordEncoder(4))
                .loadUserByUsername("private@example.test"))
                .isInstanceOf(UsernameNotFoundException.class).hasMessage("ログイン情報を確認してください");
    }
}
