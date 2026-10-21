package com.example.demo.config;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.CustomUserDetails;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 初期管理者は明示的な設定からのみ作り、既存会員の権限やパスワードを変更しない。 */
class AdminAccountTests {
    private static final String EMAIL = "owner@example.com";
    private static final String STRONG_PASSWORD = "m7-Kp!9x4Lz2";

    @Test void disabledBootstrapDoesNotReadOrWriteAccounts() throws Exception {
        var users = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        new AdminAccountInitializer().createAdmin(users, encoder, false, "", "", "").run();
        verifyNoInteractions(users, encoder);
    }

    @Test void enabledBootstrapCreatesHashedVerifiedAdministratorOnlyOnce() throws Exception {
        var users = mock(UserRepository.class);
        var encoder = new BCryptPasswordEncoder();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
        var runner = new AdminAccountInitializer().createAdmin(users, encoder, true,
                EMAIL, STRONG_PASSWORD, "運営管理者");
        runner.run();
        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User admin = captor.getValue();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.isEmailVerified()).isTrue();
        assertThat(admin.getName()).isEqualTo("運営管理者");
        assertThat(admin.getEmail()).isEqualTo(EMAIL);
        assertThat(admin.getPasswordHash()).isNotEqualTo(STRONG_PASSWORD);
        assertThat(encoder.matches(STRONG_PASSWORD, admin.getPasswordHash())).isTrue();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(admin));
        runner.run();
        verify(users, times(1)).save(any());
    }

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void invalidConfigurationFailsBeforeTouchingAccounts(String email, String password, String name) {
        var users = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        assertThatThrownBy(() -> new AdminAccountInitializer()
                .createAdmin(users, encoder, true, email, password, name).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(STRONG_PASSWORD);
        verifyNoInteractions(users, encoder);
    }

    static Stream<Arguments> invalidSettings() {
        return Stream.of(
                Arguments.of("", STRONG_PASSWORD, "管理者"),
                Arguments.of("admin", STRONG_PASSWORD, "管理者"),
                Arguments.of("a b@example.com", STRONG_PASSWORD, "管理者"),
                Arguments.of("owner@example", STRONG_PASSWORD, "管理者"),
                Arguments.of("x".repeat(244) + "@example.com", STRONG_PASSWORD, "管理者"),
                Arguments.of(EMAIL, "", "管理者"),
                Arguments.of(EMAIL, "admin", "管理者"),
                Arguments.of(EMAIL, "Admin12345678", "管理者"),
                Arguments.of(EMAIL, "Password123!", "管理者"),
                Arguments.of(EMAIL, "Ab1!Ab1!Ab1!", "管理者"),
                Arguments.of(EMAIL, "abcdefghijkl", "管理者"),
                Arguments.of(EMAIL, "aB9!" + "あ".repeat(23), "管理者"),
                Arguments.of(EMAIL, STRONG_PASSWORD, ""),
                Arguments.of(EMAIL, STRONG_PASSWORD, "名前".repeat(26)));
    }

    @Test void acceptsPasswordAtBcryptByteLimit() throws Exception {
        var users = mock(UserRepository.class);
        var encoder = new BCryptPasswordEncoder();
        String password = "Ab9!" + "x".repeat(68);
        new AdminAccountInitializer().createAdmin(users, encoder, true, EMAIL, password, "管理者").run();
        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(encoder.matches(password, captor.getValue().getPasswordHash())).isTrue();
    }

    @Test void existingOrdinaryUserIsNeverElevatedOrOverwritten() throws Exception {
        assertExistingAccountIsUntouched(false);
    }

    @Test void existingAdministratorIsNeverOverwritten() throws Exception {
        assertExistingAccountIsUntouched(true);
    }

    private void assertExistingAccountIsUntouched(boolean admin) throws Exception {
        var users = mock(UserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        User existing = new User();
        existing.setName("既存会員");
        existing.setEmail(EMAIL);
        existing.setPasswordHash("existing-hash");
        existing.setAdmin(admin);
        existing.setEmailVerified(false);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        new AdminAccountInitializer().createAdmin(users, encoder, true,
                EMAIL, STRONG_PASSWORD, "運営管理者").run();
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
        assertThat(existing.getName()).isEqualTo("既存会員");
        assertThat(existing.getPasswordHash()).isEqualTo("existing-hash");
        assertThat(existing.isAdmin()).isEqualTo(admin);
        assertThat(existing.isEmailVerified()).isFalse();
    }

    @Test void ordinaryUsersNeverReceiveAdminAuthority() {
        User user = new User();
        assertThat(new CustomUserDetails(user).getAuthorities())
                .extracting(a -> a.getAuthority()).containsExactly("ROLE_USER");
        user.setAdmin(true);
        assertThat(new CustomUserDetails(user).getAuthorities())
                .extracting(a -> a.getAuthority()).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }
}
