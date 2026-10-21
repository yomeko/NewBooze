package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.entity.UserProfileImage;
import com.example.demo.repository.*;
import com.example.demo.security.CustomUserDetails;
import com.example.demo.service.EmailVerificationService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MyPageImageTests {
    private final UserRepository users = mock(UserRepository.class);
    private final UserProfileImageRepository images = mock(UserProfileImageRepository.class);
    private final User user = new User();
    private MyPageController controller;

    @BeforeEach void setup() {
        user.setId(42L);
        user.setName("画像テスト");
        user.setEmail("image@example.com");
        user.setPasswordHash("unused");
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(images.findById(42L)).thenReturn(Optional.empty());
        controller = new MyPageController(users, mock(UserPreferenceRepository.class), mock(PasswordEncoder.class),
                images, mock(FavoriteRepository.class), mock(SakeRepository.class), mock(EmailVerificationService.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLargeDecodedDimensionsForBothOriginalAndCompressionPaths(boolean needsCompression) throws Exception {
        byte[] bytes = imageBytes(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png");
        ByteBuffer.wrap(bytes).putInt(16, 5001).putInt(20, 4000);
        var crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        if (needsCompression) bytes = Arrays.copyOf(bytes, 900 * 1024 + 1);

        var redirect = upload(bytes, "image/png");
        assertThat(redirect.getFlashAttributes().get("error").toString()).contains("2000万画素");
        verify(images, never()).save(any());
        verify(users, never()).findById(any());
    }

    @Test void derivesStoredMimeFromImageBytesAndResetsDisplayPosition() throws Exception {
        var previous = new UserProfileImage();
        previous.setUser(user);
        previous.setPositionX(10);
        previous.setPositionY(90);
        previous.setZoom(200);
        when(images.findById(42L)).thenReturn(Optional.of(previous));
        byte[] png = imageBytes(new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB), "png");

        var redirect = upload(png, "image/jpeg");
        assertThat(redirect.getFlashAttributes()).containsKey("success").doesNotContainKey("error");
        verify(images).save(previous);
        assertThat(previous.getContentType()).isEqualTo("image/png");
        assertThat(previous.getImageData()).isEqualTo(png);
        assertThat(previous.getPositionX()).isEqualTo(50);
        assertThat(previous.getPositionY()).isEqualTo(50);
        assertThat(previous.getZoom()).isEqualTo(100);
    }

    @Test void preservesSmallAnimatedGifBytes() throws Exception {
        byte[] gif = animatedGif();
        var redirect = upload(gif, "image/gif");
        assertThat(redirect.getFlashAttributes()).containsKey("success").doesNotContainKey("error");
        var saved = org.mockito.ArgumentCaptor.forClass(UserProfileImage.class);
        verify(images).save(saved.capture());
        assertThat(saved.getValue().getContentType()).isEqualTo("image/gif");
        assertThat(saved.getValue().getImageData()).isEqualTo(gif);
    }

    @Test void compressesAndResizesValidLargeImageWhileKeepingAspectRatio() throws Exception {
        var original = new BufferedImage(1800, 900, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) original.setRGB(x, y, random.nextInt());
        }
        byte[] png = imageBytes(original, "png");
        assertThat(png.length).isGreaterThan(900 * 1024);
        var redirect = upload(png, "image/png");
        assertThat(redirect.getFlashAttributes().get("success").toString()).contains("圧縮");
        var saved = org.mockito.ArgumentCaptor.forClass(UserProfileImage.class);
        verify(images).save(saved.capture());
        UserProfileImage stored = saved.getValue();
        assertThat(stored.getContentType()).isEqualTo("image/jpeg");
        assertThat(stored.getImageData().length).isLessThanOrEqualTo(900 * 1024);
        var resized = ImageIO.read(new ByteArrayInputStream(stored.getImageData()));
        assertThat(resized.getWidth()).isLessThanOrEqualTo(1600);
        assertThat(resized.getWidth()).isEqualTo(resized.getHeight() * 2);
    }

    @Test void rejectsMalformedAndUnsupportedActualImagesWithoutSaving() throws Exception {
        byte[] png = imageBytes(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png");
        assertThat(upload(Arrays.copyOf(png, 33), "image/png").getFlashAttributes()).containsKey("error");
        assertThat(upload(new byte[] {1, 2, 3}, "image/jpeg").getFlashAttributes()).containsKey("error");
        byte[] bmp = imageBytes(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "bmp");
        assertThat(upload(bmp, "image/png").getFlashAttributes()).containsKey("error");
        verify(images, never()).save(any());
    }

    private RedirectAttributesModelMap upload(byte[] bytes, String contentType) throws Exception {
        var redirect = new RedirectAttributesModelMap();
        assertThat(controller.updateProfileImage(new MockMultipartFile("profileImage", "photo", contentType, bytes),
                new CustomUserDetails(user), redirect)).isEqualTo("redirect:/mypage/account#settings");
        return redirect;
    }

    private static byte[] imageBytes(BufferedImage image, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private static byte[] animatedGif() throws Exception {
        var output = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            for (int frame = 0; frame < 2; frame++) {
                writer.writeToSequence(new IIOImage(new BufferedImage(4, 3,
                        BufferedImage.TYPE_INT_RGB), null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
