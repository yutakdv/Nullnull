package io.nullnull.social.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.ImageSanitiser.ImageRejectedException;
import io.nullnull.social.application.ImageSanitiser.ImageRejection;
import io.nullnull.social.application.ImageSanitiser.SanitisedImage;
import io.nullnull.social.domain.ImageFormat;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-082: the automatic validation A-058 left as the only boundary in front of a published image.
 *
 * <p>The GPS case is the one that matters most and is the easiest to write vacuously. Asserting
 * that some sanitised output "has no EXIF" proves nothing if the input had none either, so these
 * tests put a canary into the input and assert on the SAME bytes twice - present before, absent
 * after. A sanitiser that returned its input unchanged passes the second assertion only if the
 * first one was never true.
 */
@DisplayName("BA-082 uploaded image sanitising")
class ImageSanitiserTest {

    private static final String GPS_CANARY = "GPSLatitude=37.5665,GPSLongitude=126.9780";

    private final ImageSanitiser sanitiser = new ImageSanitiser(2048);

    @Test
    @DisplayName("BA-082-T4 metadata carrying camera coordinates does not survive sanitising")
    void locationMetadataIsNotCarriedIntoThePublishedFile() {
        byte[] withGps = jpegCarryingExif(GPS_CANARY, 64, 48);

        // Without this the test would pass against a sanitiser that does nothing at all: the claim
        // is that the canary was there and is gone, not merely that it is absent at the end.
        assertThat(contains(withGps, GPS_CANARY))
                .as("the fixture must actually carry the canary, or the next assertion is vacuous")
                .isTrue();

        SanitisedImage sanitised = sanitiser.sanitise("image/jpeg", withGps);

        assertThat(contains(sanitised.bytes(), GPS_CANARY)).isFalse();
        // And it is still the picture: dropping the metadata by dropping the file would also pass
        // the assertion above.
        assertThat(sanitised.width()).isEqualTo(64);
        assertThat(sanitised.height()).isEqualTo(48);
        assertThat(ImageFormat.ofBytes(sanitised.bytes())).isEqualTo(ImageFormat.JPEG);
    }

    @Test
    @DisplayName("BA-082-T5 bytes whose real format is not the declared one are refused")
    void aSpoofedContentTypeIsRefused() {
        byte[] png = image(ImageFormat.PNG, 16, 16);

        assertThatThrownBy(() -> sanitiser.sanitise("image/jpeg", png))
                .isInstanceOf(ImageRejectedException.class)
                .extracting(e -> ((ImageRejectedException) e).rejection())
                .isEqualTo(ImageRejection.FORMAT_MISMATCH);
    }

    @Test
    @DisplayName("BA-082-T6 bytes that are not an offered image format are refused")
    void anUnsupportedFormatIsRefused() {
        // A GIF: ImageIO can read it here, which is the point - the refusal is the vocabulary's,
        // not the decoder's, so widening the enum is the only way to accept one.
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0, 0};

        assertThatThrownBy(() -> sanitiser.sanitise("image/png", gif))
                .isInstanceOf(ImageRejectedException.class)
                .extracting(e -> ((ImageRejectedException) e).rejection())
                .isEqualTo(ImageRejection.UNSUPPORTED_FORMAT);
    }

    @Test
    @DisplayName("BA-082-T7 an image longer than the ceiling on either edge is refused")
    void anImageOverTheDimensionCeilingIsRefused() {
        ImageSanitiser small = new ImageSanitiser(32);

        assertThatThrownBy(() -> small.sanitise("image/png", image(ImageFormat.PNG, 33, 8)))
                .isInstanceOf(ImageRejectedException.class)
                .extracting(e -> ((ImageRejectedException) e).rejection())
                .isEqualTo(ImageRejection.TOO_LARGE_DIMENSIONS);
        // The other edge, because max() over one dimension would pass the first case alone.
        assertThatThrownBy(() -> small.sanitise("image/png", image(ImageFormat.PNG, 8, 33)))
                .isInstanceOf(ImageRejectedException.class)
                .extracting(e -> ((ImageRejectedException) e).rejection())
                .isEqualTo(ImageRejection.TOO_LARGE_DIMENSIONS);
        // And the ceiling itself is allowed: a test that only refuses would pass against a
        // sanitiser that refuses everything.
        assertThat(small.sanitise("image/png", image(ImageFormat.PNG, 32, 32)).width()).isEqualTo(32);
    }

    @Test
    @DisplayName("BA-082-T8 an image header with no image behind it is refused")
    void aTruncatedFileIsRefused() {
        // The PNG signature and nothing else. ofBytes() says PNG, the decoder cannot produce an
        // image, and the refusal has to come from the decode rather than from the signature check.
        byte[] headerOnly = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

        assertThatThrownBy(() -> sanitiser.sanitise("image/png", headerOnly))
                .isInstanceOf(ImageRejectedException.class)
                .extracting(e -> ((ImageRejectedException) e).rejection())
                .isEqualTo(ImageRejection.UNREADABLE);
    }

    @Test
    @DisplayName("BA-082-T9 an empty upload is refused")
    void anEmptyUploadIsRefused() {
        assertThatThrownBy(() -> sanitiser.sanitise("image/png", new byte[0]))
                .isInstanceOf(ImageRejectedException.class)
                .extracting(e -> ((ImageRejectedException) e).rejection())
                .isEqualTo(ImageRejection.EMPTY);
    }

    /** A real encoded image of the given format, so the decoder has something to decode. */
    private static byte[] image(ImageFormat format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format.extension(), out)) {
                throw new IllegalStateException("no writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A JPEG with an APP1 segment holding the canary, which is where a camera writes EXIF.
     *
     * <p>The payload is not a valid TIFF header and does not need to be: the assertion is that the
     * bytes do not reach the published file, and a decoder skips an APP1 segment whatever it holds.
     * Building real EXIF would test the fixture, not the sanitiser.
     */
    private static byte[] jpegCarryingExif(String canary, int width, int height) {
        byte[] jpeg = image(ImageFormat.JPEG, width, height);
        byte[] payload = ("Exif\0\0" + canary).getBytes(StandardCharsets.US_ASCII);
        int segmentLength = payload.length + 2;
        if (segmentLength > 0xFFFF) {
            throw new IllegalArgumentException("canary too long for one APP1 segment");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // SOI, then the segment, then the rest of the original file.
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segmentLength >> 8) & 0xFF);
        out.write(segmentLength & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] bytes = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + bytes.length <= haystack.length; i++) {
            for (int j = 0; j < bytes.length; j++) {
                if (haystack[i + j] != bytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
