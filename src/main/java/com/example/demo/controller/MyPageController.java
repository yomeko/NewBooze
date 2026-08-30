package com.example.demo.controller;

import com.example.demo.dto.DrinkPostForm;
import com.example.demo.entity.DirectMessage;
import com.example.demo.entity.DrinkPost;
import com.example.demo.entity.User;
import com.example.demo.entity.UserProfileImage;
import com.example.demo.repository.DirectMessageRepository;
import com.example.demo.repository.DrinkPostRepository;
import com.example.demo.repository.UserPreferenceRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.UserProfileImageRepository;
import com.example.demo.security.CustomUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Set;

@Controller
@RequestMapping("/mypage")
public class MyPageController {
    private final UserRepository users;
    private final UserPreferenceRepository preferences;
    private final DrinkPostRepository posts;
    private final DirectMessageRepository messages;
    private final PasswordEncoder passwordEncoder;
    private final UserProfileImageRepository profileImages;
    private static final long MAX_PROFILE_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif");

    public MyPageController(UserRepository users, UserPreferenceRepository preferences,
            DrinkPostRepository posts, DirectMessageRepository messages, PasswordEncoder passwordEncoder,
            UserProfileImageRepository profileImages) {
        this.users = users;
        this.preferences = preferences;
        this.posts = posts;
        this.messages = messages;
        this.passwordEncoder = passwordEncoder;
        this.profileImages = profileImages;
    }

    @GetMapping
    public String show(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        User user = current(principal);
        model.addAttribute("user", user);
        model.addAttribute("preferences", preferences.findByIdUserIdOrderByScoreDesc(user.getId()));
        model.addAttribute("posts", posts.findByUserIdOrderByCreatedAtDesc(user.getId()));
        model.addAttribute("otherUsers", users.findByIdNotOrderByNameAsc(user.getId()));
        model.addAttribute("hasProfileImage", profileImages.existsById(user.getId()));
        if (!model.containsAttribute("drinkPostForm")) model.addAttribute("drinkPostForm", new DrinkPostForm());
        return "mypage";
    }

    @GetMapping("/profile-image")
    @ResponseBody
    public ResponseEntity<byte[]> profileImage(@AuthenticationPrincipal CustomUserDetails principal) {
        return profileImages.findById(principal.getUserId())
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getContentType()))
                        .cacheControl(CacheControl.noCache())
                        .body(image.getImageData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/profile-image")
    public String updateProfileImage(@RequestParam("profileImage") MultipartFile file,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) throws IOException {
        String contentType = file.getContentType();
        if (file.isEmpty()) return imageError(redirect, "画像ファイルを選択してください");
        if (file.getSize() > MAX_PROFILE_IMAGE_SIZE) return imageError(redirect, "画像は5MB以下にしてください");
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType))
            return imageError(redirect, "JPEG・PNG・GIF形式の画像を選択してください");

        User user = current(principal);
        UserProfileImage image = profileImages.findById(user.getId()).orElseGet(UserProfileImage::new);
        image.setUser(user);
        image.setImageData(file.getBytes());
        image.setContentType(contentType);
        profileImages.save(image);
        redirect.addFlashAttribute("success", "プロフィール画像を変更しました");
        return "redirect:/mypage#settings";
    }

    @PostMapping("/profile-image/delete")
    public String deleteProfileImage(@AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        profileImages.deleteById(principal.getUserId());
        redirect.addFlashAttribute("success", "プロフィール画像を削除しました");
        return "redirect:/mypage#settings";
    }

    @PostMapping("/posts")
    public String post(@Valid @ModelAttribute DrinkPostForm drinkPostForm, BindingResult result,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("org.springframework.validation.BindingResult.drinkPostForm", result);
            redirect.addFlashAttribute("drinkPostForm", drinkPostForm);
            return "redirect:/mypage#posts";
        }
        DrinkPost post = new DrinkPost();
        post.setUser(current(principal));
        post.setSakeName(drinkPostForm.getSakeName().trim());
        post.setComment(drinkPostForm.getComment() == null ? "" : drinkPostForm.getComment().trim());
        posts.save(post);
        redirect.addFlashAttribute("success", "飲んだお酒を投稿しました");
        return "redirect:/mypage#posts";
    }

    @PostMapping("/profile")
    public String updateProfile(@RequestParam String name, @RequestParam String email,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        User user = current(principal);
        name = name.trim(); email = email.trim();
        if (name.isEmpty() || name.length() > 50) return error(redirect, "表示名は1〜50文字で入力してください");
        if (email.isEmpty() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 255)
            return error(redirect, "正しいメールアドレスを入力してください");
        if (users.existsByEmailAndIdNot(email, user.getId())) return error(redirect, "このメールアドレスは既に登録されています");
        user.setName(name); user.setEmail(email); users.save(user);
        refreshPrincipal(user);
        redirect.addFlashAttribute("success", "プロフィールを変更しました");
        return "redirect:/mypage#settings";
    }

    @PostMapping("/password")
    public String updatePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        User user = current(principal);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) return error(redirect, "現在のパスワードが違います");
        if (newPassword.length() < 8 || newPassword.length() > 72) return error(redirect, "新しいパスワードは8〜72文字で入力してください");
        user.setPasswordHash(passwordEncoder.encode(newPassword)); users.save(user);
        refreshPrincipal(user);
        redirect.addFlashAttribute("success", "パスワードを変更しました");
        return "redirect:/mypage#settings";
    }

    @GetMapping("/messages/{otherId}")
    public String conversation(@PathVariable Long otherId, @AuthenticationPrincipal CustomUserDetails principal, Model model) {
        User me = current(principal);
        User other = users.findById(otherId).filter(u -> !u.getId().equals(me.getId())).orElseThrow();
        model.addAttribute("me", me); model.addAttribute("other", other);
        model.addAttribute("messages", messages.conversation(me.getId(), otherId));
        return "messages";
    }

    @PostMapping("/messages/{otherId}")
    public String send(@PathVariable Long otherId, @RequestParam String body,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        User me = current(principal);
        User other = users.findById(otherId).filter(u -> !u.getId().equals(me.getId())).orElseThrow();
        body = body.trim();
        if (body.isEmpty() || body.length() > 1000) {
            redirect.addFlashAttribute("error", "メッセージは1〜1000文字で入力してください");
        } else {
            DirectMessage message = new DirectMessage();
            message.setSender(me); message.setRecipient(other); message.setBody(body); messages.save(message);
        }
        return "redirect:/mypage/messages/" + otherId;
    }

    private User current(CustomUserDetails principal) { return users.findById(principal.getUserId()).orElseThrow(); }
    private String error(RedirectAttributes redirect, String message) { redirect.addFlashAttribute("error", message); return "redirect:/mypage#settings"; }
    private String imageError(RedirectAttributes redirect, String message) { redirect.addFlashAttribute("error", message); return "redirect:/mypage#settings"; }
    private void refreshPrincipal(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
