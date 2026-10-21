package com.example.demo.security;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final CustomUserDetailsService service = new CustomUserDetailsService(users, encoder);

    @Test void legacyDefaultAdministratorCannotLogInEvenAfterEmailChange() {
        User admin = user("owner@example.com", true, "admin");
        when(users.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.loadUserByUsername(admin.getEmail()))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("ログインできません。");
        verify(users, never()).save(any());
        assertThat(encoder.matches("admin", admin.getPasswordHash())).isTrue();
    }

    @Test void administratorWithChangedPasswordCanLogIn() {
        User admin = user("admin", true, "m7-Kp!9x4Lz2");
        when(users.findByEmail("admin")).thenReturn(Optional.of(admin));
        assertThat(service.loadUserByUsername("admin").getAuthorities())
                .extracting(authority -> authority.getAuthority()).contains("ROLE_ADMIN");
    }

    @Test void ordinaryAccountIsNotMistakenForLegacyAdministrator() {
        User user = user("member@example.com", false, "admin");
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        assertThat(service.loadUserByUsername(user.getEmail()).getUsername()).isEqualTo(user.getEmail());
    }

    @Test void detailsRetainAuthenticationSnapshotWhenOriginalUserChanges() {
        User user = user("member@example.com", true, "m7-Kp!9x4Lz2");
        user.setId(7L);
        CustomUserDetails details = new CustomUserDetails(user);
        String originalHash = details.getPassword();
        user.setAdmin(false);
        user.setPasswordHash("updated-hash");
        user.setEmailVerified(false);
        assertThat(details.getPassword()).isEqualTo(originalHash);
        assertThat(details.getAuthorities()).extracting(authority -> authority.getAuthority()).contains("ROLE_ADMIN");
        assertThat(details.isEnabled()).isTrue();
    }

    private User user(String email, boolean admin, String password) {
        User user = new User();
        user.setEmail(email);
        user.setAdmin(admin);
        user.setPasswordHash(encoder.encode(password));
        return user;
    }
}
