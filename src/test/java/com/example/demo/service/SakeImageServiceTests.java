package com.example.demo.service;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class SakeImageServiceTests {
    @TempDir Path directory;

    @Test void rejectsOversizedFilesAndPathTraversal() throws Exception {
        var service = new SakeImageService(directory.toString());
        var oversized = new MockMultipartFile("image", "photo.png", "image/png", new byte[5 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.store(oversized))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5MB");
        assertThat(service.read("../secret.png")).isNull();
    }

    @Test void acceptsJpegDespiteUntrustedFilenameAndSupportsDeletion() throws Exception {
        var service = new SakeImageService(directory.toString());
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", output);
        String url = service.store(new MockMultipartFile("image", "../../photo.exe", "application/octet-stream", output.toByteArray()));
        assertThat(url).matches("/sake/images/[0-9a-f-]+\\.jpg");
        assertThat(service.read(url.substring("/sake/images/".length()))).isNotEmpty();
        service.delete(url);
        assertThat(service.read(url.substring("/sake/images/".length()))).isNull();
    }
}
