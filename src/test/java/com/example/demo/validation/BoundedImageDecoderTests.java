package com.example.demo.validation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class BoundedImageDecoderTests {
    private static final Set<String> ALLOWED = Set.of("jpeg", "png", "gif");

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "gif"})
    void acceptsActualFormatAndReturnsDecodedDimensions(String format) throws Exception {
        var decoded = BoundedImageDecoder.decode(imageBytes(format), ALLOWED, 20_000_000);
        assertThat(decoded.contentType()).isEqualTo("image/" + format);
        assertThat(decoded.image().getWidth()).isEqualTo(4);
        assertThat(decoded.image().getHeight()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"5001, 4000", "65536, 65536"})
    void rejectsOverLimitHeaderBeforeTryingToDecodeMissingPixelData(int width, int height) throws Exception {
        byte[] bytes = imageBytes("png");
        ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height);
        var crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        // IHDRの後で切り、展開を先に行った場合は画素数エラーにならない入力にする。
        byte[] headerOnly = Arrays.copyOf(bytes, 33);
        assertThatThrownBy(() -> BoundedImageDecoder.decode(headerOnly, ALLOWED, 20_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000万画素");
    }

    @Test void rejectsOversizeGifCanvasEvenWhenFirstFrameIsSmall() throws Exception {
        byte[] bytes = imageBytes("gif");
        // GIFの論理画面は最初のフレームとは別の寸法を持つ。
        bytes[6] = (byte) (6000 & 0xff);
        bytes[7] = (byte) (6000 >>> 8);
        bytes[8] = (byte) (4000 & 0xff);
        bytes[9] = (byte) (4000 >>> 8);
        assertThatThrownBy(() -> BoundedImageDecoder.decode(bytes, ALLOWED, 20_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000万画素");
    }

    @Test void rejectsOversizeLaterGifFrameBeforeDecodingIt() throws Exception {
        byte[] bytes = animatedGif();
        int imageDescriptorCount = 0;
        for (int index = 13; index < bytes.length - 9; index++) {
            if (bytes[index] != 0x2c) continue;
            if (++imageDescriptorCount == 2) {
                // 後続フレームの幅・高さは論理画面と最初のフレームが小さくても検証する。
                bytes[index + 5] = (byte) (6000 & 0xff);
                bytes[index + 6] = (byte) (6000 >>> 8);
                bytes[index + 7] = (byte) (4000 & 0xff);
                bytes[index + 8] = (byte) (4000 >>> 8);
                break;
            }
        }
        assertThat(imageDescriptorCount).isEqualTo(2);
        assertThatThrownBy(() -> BoundedImageDecoder.decode(bytes, ALLOWED, 20_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000万画素");
    }

    @Test void acceptsPixelBoundary() throws Exception {
        var decoded = BoundedImageDecoder.decode(imageBytes("png"), ALLOWED, 12);
        assertThat(decoded.image().getWidth() * decoded.image().getHeight()).isEqualTo(12);
    }

    @Test void rejectsUnsupportedAndUnreadableImages() throws Exception {
        assertThatThrownBy(() -> BoundedImageDecoder.decode(imageBytes("bmp"), ALLOWED, 20_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JPEG・PNG・GIF");
        assertThatThrownBy(() -> BoundedImageDecoder.decode(new byte[] {1, 2, 3}, ALLOWED, 20_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正しい画像ファイル");
        byte[] truncated = Arrays.copyOf(imageBytes("png"), 33);
        assertThatThrownBy(() -> BoundedImageDecoder.decode(truncated, ALLOWED, 20_000_000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正しい画像ファイル");
    }

    private static byte[] imageBytes(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
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
