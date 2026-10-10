package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.entity.UserProfileImage;
import com.example.demo.entity.Favorite;
import com.example.demo.entity.FavoriteId;
import com.example.demo.repository.UserPreferenceRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.UserProfileImageRepository;
import com.example.demo.repository.FavoriteRepository;
import com.example.demo.repository.SakeRepository;
import com.example.demo.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
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
import java.nio.charset.StandardCharsets;

/**
 * ログイン中の本人の情報を表示・変更するマイページの処理。
 * プロフィール、画像、お気に入り、パスワード、アカウント削除を担当する。
 * principalはログイン中のユーザー情報であり、更新対象のユーザーIDはここから取得する。
 */
@Controller
@RequestMapping("/mypage")
public class MyPageController {
    private final UserRepository users;
    private final UserPreferenceRepository preferences;
    private final PasswordEncoder passwordEncoder;
    private final UserProfileImageRepository profileImages;
    private final FavoriteRepository favorites;
    private final SakeRepository sake;
    // MariaDBのmax_allowed_packetが1MBの環境でも、SQLの付加情報を含めて
    // 安全に保存できるよう画像本体は900KB以下にする。
    private static final long MAX_PROFILE_IMAGE_SIZE = 900 * 1024;
    private static final long MAX_UPLOAD_IMAGE_SIZE = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 1600;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif");

    public MyPageController(UserRepository users, UserPreferenceRepository preferences,
            PasswordEncoder passwordEncoder, UserProfileImageRepository profileImages,
            FavoriteRepository favorites, SakeRepository sake) {
        this.users = users;
        this.preferences = preferences;
        this.passwordEncoder = passwordEncoder;
        this.profileImages = profileImages;
        this.favorites = favorites;
        this.sake = sake;
    }

    /**
     * 本人のプロフィール、好みの点数、お気に入り、登録に使う銘柄一覧を画面へ渡す。
     */
    @GetMapping
    public String show(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        User user = current(principal);
        model.addAttribute("user", user);
        model.addAttribute("preferences", preferences.findByIdUserIdOrderByScoreDesc(user.getId()));
        model.addAttribute("favorites", favorites.findByIdUserIdOrderByCreatedAtDesc(user.getId()));
        model.addAttribute("sakeCatalog", sake.findAll());
        addProfileImageAttributes(user, model);
        return "mypage";
    }

    /**
     * プロフィールやパスワードを変更する設定画面を表示する。
     */
    @GetMapping("/account")
    public String account(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        User user = current(principal);
        model.addAttribute("user", user);
        addProfileImageAttributes(user, model);
        return "account-settings";
    }

    /**
     * 画像の登録状況と、表示位置・拡大率をHTMLへ渡す。
     * 画像がない場合は中央（50・50）と等倍（100%）を初期値にする。
     */
    private void addProfileImageAttributes(User user, Model model) {
        profileImages.findById(user.getId()).ifPresentOrElse(image -> {
            model.addAttribute("hasProfileImage", true);
            model.addAttribute("profileImagePositionX", image.getPositionX());
            model.addAttribute("profileImagePositionY", image.getPositionY());
            model.addAttribute("profileImageZoom", image.getZoom());
        }, () -> {
            model.addAttribute("hasProfileImage", false);
            model.addAttribute("profileImagePositionX", 50);
            model.addAttribute("profileImagePositionY", 50);
            model.addAttribute("profileImageZoom", 100);
        });
    }

    /**
     * ログイン中の本人の画像を、保存済みの画像形式で返す。
     * 未登録なら404（見つからない）を返す。個人画像はブラウザや共有キャッシュに保存させない。
     */
    @GetMapping("/profile-image")
    @ResponseBody
    public ResponseEntity<byte[]> profileImage(@AuthenticationPrincipal CustomUserDetails principal) {
        return profileImages.findById(principal.getUserId())
                .map(image -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(image.getContentType()))
                        .cacheControl(CacheControl.noStore())
                        .body(image.getImageData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 画像の有無・サイズ・形式を確認して保存する。
     * 小さい画像も再生成して付加情報を除去し、サイズや寸法が上限を超える画像は圧縮する。
     * 保存後は位置と拡大率を中央・等倍に戻し、設定画面へ移動する。
     */
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
        try {
            storedImage = prepareProfileImage(uploadedBytes);
            if (storedImage == null) return imageError(redirect, "画像を圧縮できませんでした。別の画像を選択してください");
        } catch (IllegalArgumentException exception) {
            return imageError(redirect, exception.getMessage());
        } catch (IOException exception) {
            return imageError(redirect, "正しい画像ファイルを選択してください");
        }

        User user = current(principal);
        UserProfileImage image = profileImages.findById(user.getId()).orElseGet(UserProfileImage::new);
        image.setUser(user);
        image.setImageData(storedImage.data());
        image.setContentType(storedImage.contentType());
        image.setPositionX(50);
        image.setPositionY(50);
        image.setZoom(100);
        profileImages.save(image);
        redirect.addFlashAttribute("success", "プロフィール画像を変更しました");
        return "redirect:/mypage/account#settings";
    }

    /**
     * 本人の画像を削除し、設定画面へ完了メッセージを渡す。
     */
    @PostMapping("/profile-image/delete")
    public String deleteProfileImage(@AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        profileImages.deleteById(principal.getUserId());
        redirect.addFlashAttribute("success", "プロフィール画像を削除しました");
        return "redirect:/mypage/account#settings";
    }

    /**
     * 画像の表示位置と拡大率だけを変更する。
     * 位置は0〜100%、拡大率は100〜300%に限り、画像がある場合だけ更新する。
     */
    @PostMapping("/profile-image/position")
    public String updateProfileImagePosition(@RequestParam double positionX, @RequestParam double positionY,
            @RequestParam int zoom,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        if (!Double.isFinite(positionX) || !Double.isFinite(positionY)
                || positionX < 0 || positionX > 100 || positionY < 0 || positionY > 100)
            return imageError(redirect, "画像位置は0〜100の範囲で指定してください");
        if (zoom < 100 || zoom > 300)
            return imageError(redirect, "画像の拡大率は100〜300%の範囲で指定してください");
        UserProfileImage image = profileImages.findById(principal.getUserId()).orElse(null);
        if (image == null) return imageError(redirect, "先にプロフィール画像を登録してください");
        image.setPositionX((int) Math.round(positionX));
        image.setPositionY((int) Math.round(positionY));
        image.setZoom(zoom);
        profileImages.save(image);
        redirect.addFlashAttribute("success", "プロフィール画像の位置を保存しました");
        return "redirect:/mypage/account#settings";
    }

    /**
     * 本人と銘柄の組み合わせを保存する。登録済みなら重複して追加せず案内を出す。
     */
    @PostMapping("/favorites")
    public String addFavorite(@RequestParam Long sakeId, @AuthenticationPrincipal CustomUserDetails principal,
            RedirectAttributes redirect) {
        User user = current(principal);
        if (favorites.existsByIdUserIdAndIdSakeId(user.getId(), sakeId)) {
            redirect.addFlashAttribute("error", "そのお酒はすでに登録済みです"); return "redirect:/mypage#favorites";
        }
        var selected = sake.findById(sakeId).orElseThrow();
        Favorite favorite = new Favorite(); favorite.setId(new FavoriteId(user.getId(), sakeId));
        favorite.setUser(user); favorite.setSake(selected); favorites.save(favorite);
        redirect.addFlashAttribute("success", "お気に入りに登録しました"); return "redirect:/mypage#favorites";
    }

    /**
     * 本人の指定銘柄のお気に入りを削除し、お気に入り欄へ戻る。
     */
    @PostMapping("/favorites/{sakeId}/delete")
    public String removeFavorite(@PathVariable Long sakeId, @AuthenticationPrincipal CustomUserDetails principal,
            RedirectAttributes redirect) {
        FavoriteId id = new FavoriteId(principal.getUserId(), sakeId);
        if (favorites.existsById(id)) favorites.deleteById(id);
        redirect.addFlashAttribute("success", "お気に入りから削除しました"); return "redirect:/mypage#favorites";
    }

    /**
     * 表示名とメールアドレスの形式・長さ・重複を確認してから保存する。
     * ログイン中の情報も更新し、次の画面から変更後の表示名を使えるようにする。
     */
    @PostMapping("/profile")
    public String updateProfile(@RequestParam String name, @RequestParam String email,
            @RequestParam(required = false) String currentPassword,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        User user = current(principal);
        name = name.trim(); email = email.trim();
        if (name.isEmpty() || name.length() > 50) return error(redirect, "表示名は1〜50文字で入力してください");
        if (email.isEmpty() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 255)
            return error(redirect, "正しいメールアドレスを入力してください");
        // 再設定メールの送信先を変える操作は、盗まれたセッションだけでは実行させない。
        if (!email.equals(user.getEmail()) && (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())))
            return error(redirect, "メールアドレスの変更には正しい現在のパスワードが必要です");
        if (users.existsByEmailAndIdNot(email, user.getId())) return error(redirect, "このメールアドレスは既に登録されています");
        user.setName(name); user.setEmail(email); users.save(user);
        refreshPrincipal(user);
        redirect.addFlashAttribute("success", "プロフィールを変更しました");
        return "redirect:/mypage/account#settings";
    }

    /**
     * 現在のパスワードが正しいか確認し、新しいパスワードをハッシュ化して保存する。
     * 仮パスワード利用中の印も解除し、ログイン情報へ反映する。
     */
    @PostMapping("/password")
    public String updatePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
            @AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes redirect) {
        User user = current(principal);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) return error(redirect, "現在のパスワードが違います");
        if (newPassword.length() < 8 || newPassword.length() > 72) return error(redirect, "新しいパスワードは8〜72文字で入力してください");
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72)
            return error(redirect, "パスワードが長すぎます。短くしてください");
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTemporaryPassword(false);
        users.save(user);
        refreshPrincipal(user);
        redirect.addFlashAttribute("success", "パスワードを変更しました");
        return "redirect:/mypage/account#settings";
    }

    /**
     * 現在のパスワードで本人確認をしてからアカウントを削除する。
     * ログイン情報とセッションも破棄し、ログイン画面へ移動する。
     */
    @PostMapping("/account/delete")
    public String deleteAccount(@RequestParam String currentPassword,
            @AuthenticationPrincipal CustomUserDetails principal, HttpServletRequest request,
            RedirectAttributes redirect) {
        User user = current(principal);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            redirect.addFlashAttribute("error", "現在のパスワードが違います");
            return "redirect:/mypage/account";
        }
        users.delete(user);
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return "redirect:/login?deleted";
    }

    /**
     * ログイン情報のユーザーIDから、DBに保存されている最新の本人情報を取得する。
     */
    private User current(CustomUserDetails principal) { return users.findById(principal.getUserId()).orElseThrow(); }
    /**
     * 設定画面へ戻り、次の表示で1回だけ入力エラーの説明を出す。
     */
    private String error(RedirectAttributes redirect, String message) { redirect.addFlashAttribute("error", message); return "redirect:/mypage/account#settings"; }
    /**
     * 画像の入力エラーを次の画面へ渡し、画像設定欄へ戻す。
     */
    private String imageError(RedirectAttributes redirect, String message) { redirect.addFlashAttribute("error", message); return "redirect:/mypage/account#settings"; }

    /**
     * 画像の長辺を1600px以下にし、保存可能なサイズのJPEGを作る。
     * まず画質を下げて試し、それでも大きければ縦横を80%に縮めて再試行する。
     * 最大6段階で試し、900KB以下にできない場合はnullを返す。
     */
    private StoredImage compressProfileImage(BufferedImage original) throws IOException {

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

    /**
     * 元画像の縦横比に従ったサイズへ縮小し、JPEG用の画像を作る。
     * JPEGは透明を扱えないため白い背景を先に塗り、その上に写真を描く。
     */
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

    /**
     * 指定した画質でJPEGのバイト列を作る。使い終わった書き込み用の道具は必ず解放する。
     */
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

    // 保存する画像のバイト列と、画像形式名を一緒に返すためのデータ。
    private record StoredImage(byte[] data, String contentType) {}

    /** 小さい写真も再生成してEXIFの位置情報やコメントを除去し、形式を実データから決める。 */
    private StoredImage prepareProfileImage(byte[] source) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("正しい画像ファイルを選択してください");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("jpeg", "png", "gif").contains(format))
                    throw new IllegalArgumentException("JPEG・PNG・GIF形式の画像を選択してください");
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000)
                    throw new IllegalArgumentException("画像は2000万画素以下にしてください");
                BufferedImage image = reader.read(0);
                try (var output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(image, format, output)) throw new IOException("画像を保存できません");
                    if (output.size() <= MAX_PROFILE_IMAGE_SIZE
                            && Math.max(image.getWidth(), image.getHeight()) <= MAX_IMAGE_DIMENSION)
                        return new StoredImage(output.toByteArray(), "image/" + format);
                }
                return compressProfileImage(image);
            } finally {
                reader.dispose();
            }
        }
    }
    /**
     * DBの変更後のユーザー情報で、現在のログイン情報を作り直す。
     */
    private void refreshPrincipal(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }
}
