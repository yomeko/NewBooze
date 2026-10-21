package com.example.demo.validation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.w3c.dom.Node;

/** 画素数をヘッダーで確認し、巨大な画像をメモリーへ展開する前に拒否する。 */
public final class BoundedImageDecoder {
    private BoundedImageDecoder() {}

    public static DecodedImage decode(byte[] source, Set<String> allowedFormats, long maxPixels) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!allowedFormats.contains(format)) {
                    throw new InvalidImageException("JPEG・PNG・GIF形式の正しい画像を選択してください");
                }
                checkDimensions(reader.getWidth(0), reader.getHeight(0), maxPixels);
                if (format.equals("gif")) {
                    // GIFは最初のフレームより大きな描画領域や後続フレームを持てる。
                    // 小さなGIFを元のまま保存しても、アニメーションの各領域が上限内になるよう確認する。
                    checkGifDimensions(reader, maxPixels);
                }
                BufferedImage image = reader.read(0);
                if (image == null) throw invalidImage();
                return new DecodedImage(image, "image/" + format);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("正しい画像ファイルを選択してください", exception);
        }
    }

    private static void checkGifDimensions(ImageReader reader, long maxPixels) throws IOException {
        var metadata = reader.getStreamMetadata();
        if (metadata != null) {
            Node root = metadata.getAsTree("javax_imageio_gif_stream_1.0");
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeName().equals("LogicalScreenDescriptor")) {
                    var attributes = child.getAttributes();
                    checkDimensions(Integer.parseInt(attributes.getNamedItem("logicalScreenWidth").getNodeValue()),
                            Integer.parseInt(attributes.getNamedItem("logicalScreenHeight").getNodeValue()), maxPixels);
                }
            }
        }
        int frameCount = reader.getNumImages(true);
        if (frameCount < 1) throw invalidImage();
        for (int frame = 1; frame < frameCount; frame++) {
            checkDimensions(reader.getWidth(frame), reader.getHeight(frame), maxPixels);
        }
    }

    private static void checkDimensions(int width, int height, long maxPixels) {
        if (width < 1 || height < 1) throw invalidImage();
        if ((long) width * height > maxPixels) {
            throw new InvalidImageException("画像は2000万画素以下にしてください");
        }
    }

    private static InvalidImageException invalidImage() {
        return new InvalidImageException("正しい画像ファイルを選択してください");
    }

    public record DecodedImage(BufferedImage image, String contentType) {}

    private static final class InvalidImageException extends IllegalArgumentException {
        private InvalidImageException(String message) { super(message); }
    }
}
