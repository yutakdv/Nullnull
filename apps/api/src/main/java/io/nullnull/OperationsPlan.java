package io.nullnull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The plan file an operations tool imports, from a path or delivered inline, and the sha256 of its exact bytes.
 *
 * <p>A path is how a tool runs on the operator's own machine. Inline is how it runs as a staging ops task: the task
 * runs the release's image with a read-only root and a fixed entry point, so there is no file to point at, and
 * baking the plan into the image would tie every plan edit (the hours re-observation during judging included) to a
 * release. The staging operator therefore sends the bytes the owner approved, gzip then base64, in one environment
 * variable, together with their sha256; this reads them back and refuses unless they are exactly those bytes.
 *
 * <p>Exactly one source may be set, and a source that is set but unreadable fails: there is no fallback from one to
 * the other, because a fallback is how an import of something nobody approved would look like a success.
 */
public final class OperationsPlan {

    /** Far above any plan this repository holds (the five-place hours plan is 35 KB); bounds what inline inflates to. */
    static final int MAX_BYTES = 1 << 20;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** The plan's text, the sha256 of its exact bytes, their count, and where they came from ("file" or "inline"). */
    public record Text(String json, String sha256, int bytes, String origin) {
    }

    /** The variables one tool reads its plan from, and what the plan is called in a refusal. */
    public record Source(String pathVariable, String inlineVariable, String sha256Variable, String name) {
        public Source {
            Objects.requireNonNull(pathVariable, "pathVariable");
            Objects.requireNonNull(inlineVariable, "inlineVariable");
            Objects.requireNonNull(sha256Variable, "sha256Variable");
            Objects.requireNonNull(name, "name");
        }
    }

    private OperationsPlan() {
    }

    public static Text read(Map<String, String> environment, String[] args, Source source) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(source, "source");
        String path = args != null && args.length > 0 && !args[0].isBlank() ? args[0] : blankToNull(environment.get(source.pathVariable()));
        String inline = blankToNull(environment.get(source.inlineVariable()));
        String approved = blankToNull(environment.get(source.sha256Variable()));
        if ((path == null) == (inline == null)) {
            throw new IllegalStateException("exactly one of " + source.pathVariable() + " or " + source.inlineVariable()
                    + " must give the " + source.name());
        }
        if (approved != null && !SHA256.matcher(approved).matches()) {
            throw new IllegalStateException(source.sha256Variable() + " must be a lowercase hex sha256");
        }
        if (inline != null && approved == null) {
            // Inline bytes arrive from the staging operator, which sends the sha the owner approved with them.
            throw new IllegalStateException(source.inlineVariable() + " needs " + source.sha256Variable());
        }
        byte[] bytes = path != null ? file(Path.of(path), source) : inflate(inline, source);
        String sha256 = sha256(bytes);
        if (approved != null && !approved.equals(sha256)) {
            throw new IllegalStateException("the " + source.name() + " is not the approved one: sha256 " + sha256
                    + " but " + source.sha256Variable() + " is " + approved);
        }
        return new Text(utf8(bytes, source), sha256, bytes.length, path != null ? "file" : "inline");
    }

    /**
     * What a failed tool prints after "reason=": the refusal's own code when {@link OperationsContext} refused, so the
     * operator sees which check stopped it, and the exception's simple name otherwise. Never the message, which can
     * carry a path or a provider's words.
     */
    public static String failureReason(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OperationsContext.Refused refused) {
                return refused.code().name();
            }
        }
        return failure.getClass().getSimpleName();
    }

    private static byte[] file(Path path, Source source) {
        if (!Files.isRegularFile(path)) {
            // The path is echoed because it is the operator's own argument, not user data.
            throw new IllegalStateException("no " + source.name() + " at " + path);
        }
        try {
            if (Files.size(path) > MAX_BYTES) {
                throw new IllegalStateException("the " + source.name() + " is larger than " + MAX_BYTES + " bytes");
            }
            return Files.readAllBytes(path);
        } catch (IOException unreadable) {
            throw new IllegalStateException("the " + source.name() + " could not be read: " + path, unreadable);
        }
    }

    private static byte[] inflate(String encoded, Source source) {
        byte[] compressed;
        try {
            // The strict decoder: the MIME one skips characters outside the alphabet, so a damaged value could
            // decode to something else instead of failing.
            compressed = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(source.inlineVariable() + " is not base64", malformed);
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            // Read one byte past the cap rather than everything: the cap is what stops a small value inflating
            // into an unbounded one.
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IllegalStateException("the inline " + source.name() + " inflates past " + MAX_BYTES + " bytes");
            }
            return bytes;
        } catch (IOException notGzip) {
            throw new IllegalStateException(source.inlineVariable() + " is not gzip", notGzip);
        }
    }

    private static String utf8(byte[] bytes, Source source) {
        try {
            // REPORT, not the default replacement: new String(bytes, UTF_8) would turn a damaged byte into U+FFFD
            // and the importer would read text the owner never approved.
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalStateException("the " + source.name() + " is not UTF-8", malformed);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
