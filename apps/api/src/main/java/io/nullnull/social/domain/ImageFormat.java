package io.nullnull.social.domain;

import java.util.Arrays;

/**
 * The image formats a post cover may be uploaded in, identified by what the bytes actually start
 * with rather than by what the request declared.
 *
 * <p>THE LIST IS SHORT FOR A MEASURED REASON. A-058 removed the human approval step, so automatic
 * validation is the only boundary left, and the way this repository strips EXIF is to decode the
 * image and encode it again - the original's metadata cannot survive a round trip that never copies
 * it. That needs both a reader and a writer, and on Temurin 21 {@code ImageIO.getReaderMIMETypes()}
 * answers {@code [image/vnd.wap.wbmp, image/png, image/x-png, image/jpeg, image/tiff, image/bmp,
 * image/gif]} - no WebP. Adding WebP means adding a codec dependency, not relaxing this enum.
 *
 * <p>The alternative - parsing the original's segments and dropping the metadata ones in place - is
 * lossless and was rejected: a hand-written JPEG segment parser is attack surface on the one
 * boundary that has nothing behind it.
 *
 * <p>TIFF, BMP and GIF are readable here too and are still not offered: the contract's three-way
 * vocabulary is what FE renders and what V045's CHECK holds, and widening it is a contract change.
 */
public enum ImageFormat {
    /** {@code FF D8 FF} - SOI followed by the first marker. */
    JPEG("image/jpeg", "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    /** The 8-byte PNG signature (RFC 2083 section 3.1), including the CR/LF/EOF transfer checks. */
    PNG("image/png", "png", new byte[] {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    });

    private final String mediaType;
    private final String extension;
    private final byte[] signature;

    ImageFormat(String mediaType, String extension, byte[] signature) {
        this.mediaType = mediaType;
        this.extension = extension;
        this.signature = signature;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    /**
     * The format these bytes actually are, or null when they are none of them.
     *
     * <p>This is the only question worth asking of an upload: a declared content type is a claim by
     * the caller, and BA-082-T1 requires a spoofed one to be refused. Callers compare this answer
     * with what was declared rather than trusting either alone.
     */
    public static ImageFormat ofBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(format -> format.matches(bytes))
                .findFirst()
                .orElse(null);
    }

    /** The format a declared media type names, or null when the vocabulary does not carry it. */
    public static ImageFormat ofMediaType(String mediaType) {
        return Arrays.stream(values())
                .filter(format -> format.mediaType.equals(mediaType))
                .findFirst()
                .orElse(null);
    }

    private boolean matches(byte[] bytes) {
        if (bytes.length < signature.length) {
            return false;
        }
        return Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length);
    }
}
