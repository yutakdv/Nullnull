package io.nullnull.social.application;

import io.nullnull.social.domain.ImageFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * Turns the bytes a visitor uploaded into the bytes we are willing to publish.
 *
 * <p>A-058 replaced the owner's approval with this. Nothing reads the image after it, and nobody
 * looks at it before it reaches a reader, so every rejection here is a rejection of the post.
 *
 * <p>WHAT THIS REMOVES AND WHAT IT DOES NOT. It removes metadata - all of it, including the EXIF
 * GPS tags a phone writes when the picture was taken with location on. That is invariant 10's
 * concern arriving by a path the invariant did not name: the rule forbids sending precise location
 * to the server, and a photograph taken by the camera A-057 lets the OS offer carries coordinates
 * inside the file. It does NOT look at what the picture shows. A lawful-looking JPEG of anything at
 * all passes, which is the residual risk A-058 records rather than one this class closes.
 *
 * <p>WHY DECODE AND RE-ENCODE. Metadata is dropped by never copying it: the decoder produces
 * pixels, the encoder writes a new file, and no segment of the original survives. The alternative -
 * walking the original's segments and removing the metadata ones - keeps the exact pixels but puts
 * a hand-written parser of hostile input on the one boundary that has nothing behind it.
 *
 * <p>ImageIO is told not to use a disk cache: the default writes scratch files under the temp
 * directory, and an image service that fills a disk is a way to take the task down.
 */
public final class ImageSanitiser {

    static {
        ImageIO.setUseCache(false);
    }

    private final int maxLongEdgePixels;

    public ImageSanitiser(int maxLongEdgePixels) {
        if (maxLongEdgePixels < 1) {
            throw new IllegalArgumentException("maxLongEdgePixels must be positive");
        }
        this.maxLongEdgePixels = maxLongEdgePixels;
    }

    /**
     * @param declaredMediaType what the caller said the bytes are
     * @param bytes the object as uploaded
     * @return the sanitised file and the format it is in
     * @throws ImageRejectedException with a stable reason, for every way this can refuse
     */
    public SanitisedImage sanitise(String declaredMediaType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ImageRejectedException(ImageRejection.EMPTY);
        }
        ImageFormat actual = ImageFormat.ofBytes(bytes);
        if (actual == null) {
            throw new ImageRejectedException(ImageRejection.UNSUPPORTED_FORMAT);
        }
        // The declared type is compared, not trusted: BA-082-T5 asks for a spoofed format to be
        // refused, and a caller who declares PNG while uploading JPEG has either a broken client or
        // an intent this boundary exists for. Either way the two must agree before anything decodes.
        if (ImageFormat.ofMediaType(declaredMediaType) != actual) {
            throw new ImageRejectedException(ImageRejection.FORMAT_MISMATCH);
        }

        BufferedImage decoded = decode(bytes);
        // A null return is ImageIO saying "no reader claimed these bytes" WITHOUT throwing. Treating
        // it as anything but a rejection would publish the original.
        if (decoded == null) {
            throw new ImageRejectedException(ImageRejection.UNREADABLE);
        }
        int longEdge = Math.max(decoded.getWidth(), decoded.getHeight());
        if (longEdge > maxLongEdgePixels) {
            throw new ImageRejectedException(ImageRejection.TOO_LARGE_DIMENSIONS);
        }

        byte[] sanitised = encode(decoded, actual);
        // An encoder that writes nothing is not a small file, it is a failure that produced no
        // exception. Publishing zero bytes would be a broken cover with a 201 in front of it.
        if (sanitised.length == 0) {
            throw new ImageRejectedException(ImageRejection.UNREADABLE);
        }
        return new SanitisedImage(actual, sanitised, decoded.getWidth(), decoded.getHeight());
    }

    private BufferedImage decode(byte[] bytes) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            // A decoder failing on hostile input is expected, not exceptional. It is the refusal.
            throw new ImageRejectedException(ImageRejection.UNREADABLE);
        }
    }

    private byte[] encode(BufferedImage image, ImageFormat format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // JPEG has no alpha channel. An image decoded WITH one (a PNG uploaded under a spoofed
            // type never gets here, but a JPEG can still decode to a type ImageIO refuses to write)
            // makes write() return false rather than throw, so the return value is checked.
            BufferedImage writable = format == ImageFormat.JPEG ? withoutAlpha(image) : image;
            if (!ImageIO.write(writable, format.extension(), out)) {
                throw new ImageRejectedException(ImageRejection.UNREADABLE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private BufferedImage withoutAlpha(BufferedImage image) {
        if (image.getTransparency() == java.awt.Transparency.OPAQUE) {
            return image;
        }
        BufferedImage opaque = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = opaque.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return opaque;
    }

    /** The sanitised bytes and the shape they turned out to be. */
    public record SanitisedImage(ImageFormat format, byte[] bytes, int width, int height) {}

    /** Every way sanitising refuses, as a stable vocabulary the API layer maps to a Problem code. */
    public enum ImageRejection {
        EMPTY,
        UNSUPPORTED_FORMAT,
        FORMAT_MISMATCH,
        UNREADABLE,
        TOO_LARGE_DIMENSIONS
    }

    /** Thrown for every refusal, never for a programming error. */
    public static final class ImageRejectedException extends RuntimeException {
        private final transient ImageRejection rejection;

        public ImageRejectedException(ImageRejection rejection) {
            super(rejection.name());
            this.rejection = rejection;
        }

        public ImageRejection rejection() {
            return rejection;
        }
    }
}
