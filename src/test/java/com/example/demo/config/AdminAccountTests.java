package com.example.demo.config;

import com.example.demo.security.CustomUserDetails;

import com.example.demo.config.AdminAccountInitializer;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 管理者の初期作成と権限を、DBの代わりのオブジェクトで確認するテスト。
 * mockはDB操作の代役、whenは代役の返答、verifyは呼び出された操作の確認を表す。
 */
class AdminAccountTests {
    // 初期管理者のパスワードがハッシュ化され、再起動時には重複して作られないことを確認する。
    @Test void createsHashedAdministratorOnlyOnce() throws Exception {
        var users = mock(UserRepository.class);
        var encoder = new BCryptPasswordEncoder();
        when(users.findByEmail("admin")).thenReturn(Optional.empty());
        var runner = new AdminAccountInitializer().createAdmin(users, encoder);
        runner.run();
        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User admin = captor.getValue();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(admin.getEmail()).isEqualTo("admin");
        assertThat(admin.getPasswordHash()).isNotEqualTo("admin");
        assertThat(encoder.matches("admin", admin.getPasswordHash())).isTrue();
        when(users.findByEmail("admin")).thenReturn(Optional.of(admin));
        runner.run();
        verify(users, times(1)).save(any());
    }

    // 一般ユーザーに管理者権限がなく、管理者だけが追加の権限を持つことを確認する。
    @Test void ordinaryUsersNeverReceiveAdminAuthority() {
        User user = new User();
        assertThat(new CustomUserDetails(user).getAuthorities())
                .extracting(a -> a.getAuthority()).containsExactly("ROLE_USER");
        user.setAdmin(true);
        assertThat(new CustomUserDetails(user).getAuthorities())
                .extracting(a -> a.getAuthority()).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }
}
