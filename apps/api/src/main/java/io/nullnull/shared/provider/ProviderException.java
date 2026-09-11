package io.nullnull.shared.provider;

/** Sanitized transport failure. It never retains the provider exception, URI or response body. */
public final class ProviderException extends RuntimeException {

    public enum Category {
        HOST_NOT_ALLOWED, CAPACITY, CIRCUIT_OPEN, TIMEOUT, IO, HTTP_STATUS, RESPONSE_TOO_LARGE, INTERRUPTED
    }

    public enum StatusClass { NONE, INFORMATIONAL, SUCCESS, REDIRECTION, CLIENT_ERROR, RATE_LIMIT, SERVER_ERROR }

    private final Category category;
    private final StatusClass statusClass;
    private final Integer httpStatus;

    public ProviderException(Category category, StatusClass statusClass) {
        this(category, statusClass, null);
    }

    public ProviderException(Category category, StatusClass statusClass, Integer httpStatus) {
        super(category.name(), null, false, false);
        this.category = category;
        this.statusClass = statusClass;
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be a valid HTTP status");
        }
        this.httpStatus = httpStatus;
    }

    public Category category() {
        return category;
    }

    public StatusClass statusClass() {
        return statusClass;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public static StatusClass classify(int status) {
        if (status == 429) {
            return StatusClass.RATE_LIMIT;
        }
        return switch (status / 100) {
            case 1 -> StatusClass.INFORMATIONAL;
            case 2 -> StatusClass.SUCCESS;
            case 3 -> StatusClass.REDIRECTION;
            case 4 -> StatusClass.CLIENT_ERROR;
            case 5 -> StatusClass.SERVER_ERROR;
            default -> StatusClass.NONE;
        };
    }
}
