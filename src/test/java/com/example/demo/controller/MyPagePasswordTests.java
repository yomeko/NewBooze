package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.*;
import com.example.demo.security.CustomUserDetails;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 設定からの変更も登録と同じUTF-8バイト境界を使い、失敗時に保存しないことを確認する。 */
class MyPagePasswordTests {
    private final UserRepository users = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = spy(new BCryptPasswordEncoder());
    private final User user = new User();
    private MyPageController controller;

    @BeforeEach void setup() {
        user.setId(42L);
        user.setName("変更テスト");
        user.setEmail("password@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("current123"));
        user.setTemporaryPassword(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        controller = new MyPageController(users, mock(UserPreferenceRepository.class), encoder,
                mock(UserProfileImageRepository.class), mock(FavoriteRepository.class), mock(SakeRepository.class));
    }

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overLimitPasswords")
    void refusesOversizePasswordWithoutEncodingOrSaving(String label, String password) {
        String previousHash = user.getPasswordHash();
        var redirect = new RedirectAttributesModelMap();
        assertThat(controller.updatePassword("current123", password, new CustomUserDetails(user), redirect))
                .isEqualTo("redirect:/mypage/account#settings");
        assertThat(redirect.getFlashAttributes().get("error").toString()).contains("UTF-8で72バイト以内");
        assertThat(user.getPasswordHash()).isEqualTo(previousHash);
        assertThat(user.isTemporaryPassword()).isTrue();
        verify(encoder, never()).encode(any());
        verify(users, never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedPasswords")
    void acceptsBoundaryPasswordAndClearsTemporaryPassword(String label, String password) {
        var redirect = new RedirectAttributesModelMap();
        assertThat(controller.updatePassword("current123", password, new CustomUserDetails(user), redirect))
                .isEqualTo("redirect:/mypage/account#settings");
        assertThat(redirect.getFlashAttributes()).containsKey("success").doesNotContainKey("error");
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(user.isTemporaryPassword()).isFalse();
        verify(users).save(user);
        assertThat(((CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isTemporaryPassword()).isFalse();
    }

    @Test void wrongCurrentPasswordCannotChangeStoredPassword() {
        var redirect = new RedirectAttributesModelMap();
        controller.updatePassword("wrong123", "newvalid123", new CustomUserDetails(user), redirect);
        assertThat(redirect.getFlashAttributes().get("error")).isEqualTo("現在のパスワードが違います");
        verify(encoder, never()).encode(any());
        verify(users, never()).save(any());
    }

    static Stream<Arguments> overLimitPasswords() { return AuthControllerTests.overLimitPasswords(); }
    static Stream<Arguments> allowedPasswords() { return AuthControllerTests.allowedPasswords(); }
}
