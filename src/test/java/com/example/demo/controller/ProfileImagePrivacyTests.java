package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.entity.UserProfileImage;
import com.example.demo.repository.*;
import com.example.demo.security.CustomUserDetails;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileImagePrivacyTests {
    @Test void stripsMetadataEvenFromSmallUploadsAndUsesDecodedContentType() throws Exception {
        byte[] uploaded = pngWithPrivateMetadata();
        assertThat(new String(uploaded, StandardCharsets.ISO_8859_1)).contains("PRIVATE_LOCATION_METADATA");
        var users = mock(UserRepository.class);
        var images = mock(UserProfileImageRepository.class);
        User owner = new User();
        owner.setId(7L);
        when(users.findById(7L)).thenReturn(Optional.of(owner));
        var controller = controller(users, images);
        controller.updateProfileImage(new MockMultipartFile("profileImage", "photo.jpg", "image/jpeg", uploaded),
                new CustomUserDetails(owner), new RedirectAttributesModelMap());
        var saved = ArgumentCaptor.forClass(UserProfileImage.class);
        verify(images).save(saved.capture());
        assertThat(saved.getValue().getUser().getId()).isEqualTo(7L);
        assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
        assertThat(new String(saved.getValue().getImageData(), StandardCharsets.ISO_8859_1))
                .doesNotContain("PRIVATE_LOCATION_METADATA");
        assertThat(ImageIO.read(new ByteArrayInputStream(saved.getValue().getImageData())).getWidth()).isEqualTo(8);
    }

    @Test void rejectsOversizedDimensionsBeforeDecodingPixelData() throws Exception {
        byte[] uploaded = pngWithPrivateMetadata();
        ByteBuffer.wrap(uploaded).putInt(16, 6000).putInt(20, 6000);
        CRC32 crc = new CRC32();
        crc.update(uploaded, 12, 17);
        ByteBuffer.wrap(uploaded).putInt(29, (int) crc.getValue());
        var users = mock(UserRepository.class);
        var images = mock(UserProfileImageRepository.class);
        var redirect = new RedirectAttributesModelMap();
        controller(users, images).updateProfileImage(
                new MockMultipartFile("profileImage", "photo.png", "image/png", uploaded),
                new CustomUserDetails(new User()), redirect);
        assertThat(redirect.getFlashAttributes().get("error")).isEqualTo("画像は2000万画素以下にしてください");
        verifyNoInteractions(users, images);
    }

    private MyPageController controller(UserRepository users, UserProfileImageRepository images) {
        return new MyPageController(users, mock(UserPreferenceRepository.class), mock(PasswordEncoder.class),
                images, mock(FavoriteRepository.class), mock(SakeRepository.class));
    }

    private byte[] pngWithPrivateMetadata() throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        try (var output = new ByteArrayOutputStream(); var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
            var root = new IIOMetadataNode(metadata.getNativeMetadataFormatName());
            var text = new IIOMetadataNode("tEXt");
            var entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Location");
            entry.setAttribute("value", "PRIVATE_LOCATION_METADATA");
            text.appendChild(entry);
            root.appendChild(text);
            metadata.mergeTree(metadata.getNativeMetadataFormatName(), root);
            writer.write(null, new IIOImage(image, null, metadata), null);
            stream.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
