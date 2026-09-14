package io.nullnull.shared.cursor;

import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * The last row a page actually returned, named the way the sort that produced it names rows: the
 * ordering value, and the id that breaks its ties.
 *
 * <p>This is what a cursor carries instead of an offset (BA-027). An offset says "skip 20 rows",
 * which is a claim about the size of the set rather than about the reader's place in it - so a row
 * inserted ahead of them re-serves one they have already seen, and a row removed ahead of them
 * skips one they have not. A key says "resume after THIS row", which stays true however the rows
 * ahead of it change.
 *
 * <p>The value always comes from the database that did the sorting and is never recomputed in Java.
 * {@code lower()} under the server's collation is not {@link String#toLowerCase} under a Java
 * locale, and a key that disagreed with its own ORDER BY would put back exactly the skip this
 * class removes.
 *
 * <p>{@code value} is base64url-encoded in the wire form so that a place name containing {@code ':'}
 * or the payload's own {@code '|'} separator cannot break the parse. That is framing, not
 * concealment: anyone holding the cursor can decode it. What keeps the cursor safe to hand out is
 * that it carries no owner id, no search term and no pasted text - only a value the same caller
 * just received in the same response, under a signature bound to their owner binding.
 */
public record CursorSortKey(String value, UUID id) {

    public CursorSortKey {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(id, "id");
    }

    public static CursorSortKey of(Instant value, UUID id) {
        return new CursorSortKey(value.toString(), id);
    }

    public static CursorSortKey of(LocalDate value, UUID id) {
        return new CursorSortKey(value.toString(), id);
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                + ":" + id;
    }

    /**
     * Refuses anything that is not this shape - which includes an offset-era cursor, whose second
     * claim is a bare number. Those cursors still carry an intact signature, so nothing else would
     * catch them, and reading "20" as a sort value would resume somewhere nobody asked for.
     */
    public static CursorSortKey decode(String encoded) {
        if (encoded == null) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        int separator = encoded.indexOf(':');
        if (separator < 0) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        try {
            return new CursorSortKey(
                    new String(Base64.getUrlDecoder().decode(encoded.substring(0, separator)),
                            StandardCharsets.UTF_8),
                    UUID.fromString(encoded.substring(separator + 1)));
        } catch (IllegalArgumentException exception) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
    }

    /** The value as the instant a {@code timestamptz} ordering produced. */
    public Instant instantValue() {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
    }

    /** The value as the day a {@code date} ordering produced. */
    public LocalDate dateValue() {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
    }
}
