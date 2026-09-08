package io.nullnull.shared.problem;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes from docs/api/openapi.yaml {@code Problem.code} and the UI mapping table
 * in docs/api/README.md §9. The default status is the table's primary status; operations
 * that document another status (LOCK_CONFLICT 422, APPLY_FAILED 503) pass it explicitly.
 * {@code retryable} defaults are conservative: only codes whose table row allows an automatic
 * or Retry-After driven retry are true.
 */
public enum ProblemCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request", false),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized", false),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden", false),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found", false),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed", false),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "CSRF token invalid", false),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "Cursor invalid", false),
    CURSOR_EXPIRED(HttpStatus.GONE, "Cursor expired", false),
    TRIP_CHANGED(HttpStatus.CONFLICT, "Trip changed", false),
    DATA_CHANGED(HttpStatus.CONFLICT, "Data changed", false),
    LOCK_CONFLICT(HttpStatus.CONFLICT, "Lock conflict", false),
    ROUTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Route unavailable", true),
    NO_IMPROVEMENT(HttpStatus.UNPROCESSABLE_CONTENT, "No improvement", false),
    APPLY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Apply failed", false),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused", false),
    IMPORT_DRAFT_EXPIRED(HttpStatus.GONE, "Import draft expired", false),
    IMPORT_DRAFT_CHANGED(HttpStatus.CONFLICT, "Import draft changed", false),
    PREVIEW_EXPIRED(HttpStatus.GONE, "Preview expired", false),
    REVERT_WINDOW_EXPIRED(HttpStatus.GONE, "Revert window expired", false),
    DELETION_STATUS_EXPIRED(HttpStatus.GONE, "Deletion status expired", false),
    SOURCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Source unavailable", true),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limited", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", false);

    private final HttpStatus defaultStatus;
    private final String title;
    private final boolean defaultRetryable;

    ProblemCode(HttpStatus defaultStatus, String title, boolean defaultRetryable) {
        this.defaultStatus = defaultStatus;
        this.title = title;
        this.defaultRetryable = defaultRetryable;
    }

    public HttpStatus defaultStatus() {
        return defaultStatus;
    }

    public String title() {
        return title;
    }

    public boolean defaultRetryable() {
        return defaultRetryable;
    }

    /** {@code /problems/trip-changed} style type reference (docs/api/README.md §9 example). */
    public String typeReference() {
        return "/problems/" + name().toLowerCase().replace('_', '-');
    }
}
