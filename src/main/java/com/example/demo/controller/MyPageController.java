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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.util.Iterator;
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
    private static final long MAX_UPLOAD_IMAGE_SIZE = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 1600;
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
        if (file.getSize() > MAX_UPLOAD_IMAGE_SIZE) return imageError(redirect, "画像は20MB以下にしてください");
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType))
            return imageError(redirect, "JPEG・PNG・GIF形式の画像を選択してください");

        byte[] uploadedBytes = file.getBytes();
        StoredImage storedImage;
        if (uploadedBytes.length > MAX_PROFILE_IMAGE_SIZE) {
            storedImage = compressProfileImage(uploadedBytes);
            if (storedImage == null) return imageError(redirect, "画像を圧縮できませんでした。別の画像を選択してください");
        } else {
            // 拡張子やContent-Typeだけでなく、実際に画像として読み込めることも確認する。
            if (ImageIO.read(new ByteArrayInputStream(uploadedBytes)) == null)
                return imageError(redirect, "正しい画像ファイルを選択してください");
            storedImage = new StoredImage(uploadedBytes, contentType);
        }

        User user = current(principal);
        UserProfileImage image = profileImages.findById(user.getId()).orElseGet(UserProfileImage::new);
        image.setUser(user);
        image.setImageData(storedImage.data());
        image.setContentType(storedImage.contentType());
        profileImages.save(image);
        redirect.addFlashAttribute("success", uploadedBytes.length > MAX_PROFILE_IMAGE_SIZE
                ? "プロフィール画像を5MB以下に圧縮して変更しました" : "プロフィール画像を変更しました");
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

    /** 画像を長辺1600px以下に縮小し、5MB以下のJPEGへ変換する。 */
    private StoredImage compressProfileImage(byte[] source) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
        if (original == null) return null;

        double initialScale = Math.min(1.0,
                (double) MAX_IMAGE_DIMENSION / Math.max(original.getWidth(), original.getHeight()));
        int width = Math.max(1, (int) Math.round(original.getWidth() * initialScale));
        int height = Math.max(1, (int) Math.round(original.getHeight() * initialScale));

        for (int resizeAttempt = 0; resizeAttempt < 6; resizeAttempt++) {
            BufferedImage resized = resizeForJpeg(original, width, height);
            for (float quality = 0.9f; quality >= 0.4f; quality -= 0.1f) {
                byte[] compressed = writeJpeg(resized, quality);
                if (compressed.length <= MAX_PROFILE_IMAGE_SIZE)
                    return new StoredImage(compressed, "image/jpeg");
            }
            width = Math.max(1, (int) Math.round(width * 0.8));
            height = Math.max(1, (int) Math.round(height * 0.8));
        }
        return null;
    }

    private BufferedImage resizeForJpeg(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return result;
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer is unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private record StoredImage(byte[] data, String contentType) {}
    private void refreshPrincipal(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
