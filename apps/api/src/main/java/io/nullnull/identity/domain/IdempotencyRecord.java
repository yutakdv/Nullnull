package io.nullnull.identity.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One replay record (docs/architecture/ERD.md §1 IDEMPOTENCY_RECORDS, docs/api/README.md §5). Scope is
 * {@code (ownerId, routeKey, idempotencyKey)}; {@code routeKey} is the route template, never a resolved
 * path, because the resolved path parameters live in {@code requestHash}.
 *
 * <p>A row starts as a reservation with no response and becomes replayable when the command finishes:
 * {@code responseStatus} and {@code responseBody} are always both absent or both present. The stored
 * body is the projection the command chose to persist, which may hold less than the response returned
 * to the caller.
 */
public record IdempotencyRecord(UUID id, UUID ownerId, String routeKey, String idempotencyKey,
        String requestHash, Integer responseStatus, String responseBody, Instant createdAt,
        Instant expiresAt) {

    public static final int ROUTE_KEY_MAX_LENGTH = 200;
    public static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

    /**
     * Application-facing upper bound of the stored projection, in UTF-8 bytes of the compact JSON
     * text the guard serialises. This is the only figure a caller ever meets: a projection above it
     * is refused with a named error before anything is written. The number is an engineering
     * proposal, not a figure from the API contract (docs/architecture/ERD.md §4
     * "Idempotency/Analytics"): the largest response shape documented in docs/api/README.md §14 is a
     * 100-item trip, which is far below it.
     */
    public static final int RESPONSE_BODY_MAX_BYTES = 65_536;

    /**
     * Bound of the {@code response_body} column in V003__idempotency_records.sql, in UTF-8 bytes of
     * {@code response_body::text}. It measures something different from {@link
     * #RESPONSE_BODY_MAX_BYTES}: PostgreSQL re-serialises jsonb and writes a space after every
     * {@code :} and every {@code ,}, so one value is longer in the column than in the compact text
     * the application measured. Structural re-serialisation cannot exceed 1.5x the compact text (the
     * migration states the derivation), so this 2x figure clears the structural case with margin.
     * Number rendering is not bounded that way: an ordinary finite double is written by Jackson as
     * {@code 1.0E18} and returned by PostgreSQL as nineteen digits, so a projection inside the
     * application bound can still exceed this one. That is why a violation of the column constraint
     * is translated back into the same named error rather than surfacing as a driver failure.
     *
     * <p>It is also the bound this record enforces, because one instance carries the compact text on
     * the way in and the longer {@code response_body::text} on the way back out.
     */
    public static final int RESPONSE_BODY_COLUMN_MAX_BYTES = 131_072;

    private static final Pattern REQUEST_HASH = Pattern.compile("[0-9a-f]{64}");

    public IdempotencyRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        requireLength("routeKey", routeKey, 1, ROUTE_KEY_MAX_LENGTH);
        requireLength("idempotencyKey", idempotencyKey, IDEMPOTENCY_KEY_MIN_LENGTH,
                IDEMPOTENCY_KEY_MAX_LENGTH);
        if (!REQUEST_HASH.matcher(Objects.requireNonNull(requestHash, "requestHash")).matches()) {
            throw new IllegalArgumentException("requestHash must be 64 lowercase hex characters");
        }
        if ((responseStatus == null) != (responseBody == null)) {
            throw new IllegalArgumentException("responseStatus and responseBody are stored together");
        }
        if (responseStatus != null && (responseStatus < 100 || responseStatus > 599)) {
            throw new IllegalArgumentException("responseStatus out of range: " + responseStatus);
        }
        // The column bound, not the application bound: a record read back from the database carries
        // response_body::text, which jsonb re-serialisation makes longer than the compact projection
        // the guard checked on the way in.
        if (responseBody != null && responseBody.getBytes(StandardCharsets.UTF_8).length
                > RESPONSE_BODY_COLUMN_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "responseBody must not exceed " + RESPONSE_BODY_COLUMN_MAX_BYTES + " bytes");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    /** A reservation: the slot is taken, the command has not produced a response yet. */
    public static IdempotencyRecord reservation(UUID id, UUID ownerId, String routeKey,
            String idempotencyKey, String requestHash, Instant createdAt, Instant expiresAt) {
        return new IdempotencyRecord(id, ownerId, routeKey, idempotencyKey, requestHash, null, null,
                createdAt, expiresAt);
    }

    /** True once a response is stored and the record replays instead of running the command again. */
    public boolean completed() {
        return responseStatus != null;
    }

    private static void requireLength(String field, String value, int min, int max) {
        Objects.requireNonNull(value, field);
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw new IllegalArgumentException(field + " length must be " + min + ".." + max);
        }
    }
}
