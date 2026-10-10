package com.example.demo.config;

import com.example.demo.controller.MyPageController;
import com.example.demo.entity.User;
import com.example.demo.entity.UserProfileImage;
import com.example.demo.repository.*;
import com.example.demo.security.CustomUserDetails;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {SecurityBoundaryTests.ProbeController.class, MyPageController.class})
@Import({SecurityConfig.class, SecurityBoundaryTests.ProbeController.class})
class SecurityBoundaryTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserDetailsService userDetailsService;
    @MockitoBean UserRepository users;
    @MockitoBean UserPreferenceRepository preferences;
    @MockitoBean UserProfileImageRepository images;
    @MockitoBean FavoriteRepository favorites;
    @MockitoBean SakeRepository sake;

    @Test void anonymousAndOrdinaryUsersCannotReadAdminData() throws Exception {
        mvc.perform(get("/admin/users")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/users").with(user("ordinary").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(user("administrator").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test void privatePagesRequireLoginAndWritesRequireCsrf() throws Exception {
        mvc.perform(get("/mypage")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/mypage/test-write").with(user("ordinary"))).andExpect(status().isForbidden());
        mvc.perform(post("/mypage/test-write").with(user("ordinary")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test void headersPreventStorageReferrerDisclosureAndInjectedScripts() throws Exception {
        mvc.perform(get("/").secure(true)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
    }

    @Test void profileImageAlwaysUsesAuthenticatedOwnerAndCannotBeStored() throws Exception {
        User owner = new User();
        owner.setId(7L);
        owner.setEmail("owner@example.test");
        UserProfileImage image = new UserProfileImage();
        image.setContentType("image/png");
        image.setImageData(new byte[] {1, 2, 3});
        when(images.findById(7L)).thenReturn(Optional.of(image));
        mvc.perform(get("/mypage/profile-image").with(user(new CustomUserDetails(owner))).param("userId", "8"))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(images).findById(7L);
        verify(images, never()).findById(8L);
    }

    @Test void emailChangeRequiresCurrentPasswordWhileDisplayNameChangeDoesNot() throws Exception {
        User owner = new User();
        owner.setId(7L);
        owner.setEmail("owner@example.test");
        owner.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4)
                .encode("test-only-password-2026"));
        when(users.findById(7L)).thenReturn(Optional.of(owner));
        var principal = user(new CustomUserDetails(owner));
        mvc.perform(post("/mypage/profile").with(principal).with(csrf())
                .param("name", "新しい名前").param("email", "attacker@example.test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "メールアドレスの変更には正しい現在のパスワードが必要です"));
        mvc.perform(post("/mypage/profile").with(principal).with(csrf())
                .param("name", "新しい名前").param("email", "attacker@example.test").param("currentPassword", "wrong"))
                .andExpect(flash().attribute("error", "メールアドレスの変更には正しい現在のパスワードが必要です"));
        verify(users, never()).save(any());
        mvc.perform(post("/mypage/profile").with(principal).with(csrf())
                .param("name", "新しい名前").param("email", "owner@example.test"))
                .andExpect(flash().attribute("success", "プロフィールを変更しました"));
        verify(users).save(owner);
        mvc.perform(post("/mypage/profile").with(principal).with(csrf())
                .param("name", "新しい名前").param("email", "new@example.test")
                .param("currentPassword", "test-only-password-2026"))
                .andExpect(flash().attribute("success", "プロフィールを変更しました"));
        verify(users, times(2)).save(owner);
    }

    @RestController
    static class ProbeController {
        @GetMapping("/") String home() { return "public"; }
        @GetMapping("/admin/users") String admin() { return "admin-only"; }
        @GetMapping("/mypage/test-page") String account() { return "private"; }
        @PostMapping("/mypage/test-write") String write() { return "saved"; }
    }
}
