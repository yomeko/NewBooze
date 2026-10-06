package com.example.demo.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 商品画像を検証し、公開用の画像として保存する。 */
@Service
public class SakeImageService {
    private final Path directory;

    public SakeImageService(@Value("${app.sake-image-directory:./uploads/sake}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) throws IOException {
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("画像は5MB以下にしてください");
        }
        try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("JPEG・PNG形式の正しい画像を選択してください");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("jpeg", "png").contains(format)) {
                    throw new IllegalArgumentException("JPEG・PNG形式の画像を選択してください");
                }
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000) {
                    throw new IllegalArgumentException("画像は2000万画素以下にしてください");
                }
                var image = reader.read(0);
                var output = new ByteArrayOutputStream();
                // 再生成して、ファイル名や添付メタデータを保存しない。
                if (!ImageIO.write(image, format, output)) throw new IOException("画像を保存できません");
                Files.createDirectories(directory);
                String filename = UUID.randomUUID() + (format.equals("jpeg") ? ".jpg" : ".png");
                Files.write(directory.resolve(filename), output.toByteArray());
                return "/sake/images/" + filename;
            } finally {
                reader.dispose();
            }
        }
    }

    public byte[] read(String filename) throws IOException {
        if (!filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")) {
            return null;
        }
        Path path = directory.resolve(filename);
        return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
    }

    public void delete(String url) throws IOException {
        Files.deleteIfExists(directory.resolve(url.substring("/sake/images/".length())));
    }
}
