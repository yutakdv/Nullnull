package io.nullnull.shared.http;

import java.io.IOException;

/**
 * A request body passed {@code nullnull.http.max-request-body-bytes} while it was being streamed.
 *
 * <p>An {@link IOException} on purpose. It is raised from inside a {@code ServletInputStream} that a
 * message converter is reading, and the servlet contract lets that stream fail only with an
 * {@code IOException}; Spring's argument resolver then wraps it in
 * {@code HttpMessageNotReadableException} with this exception as the cause, which is what
 * {@code GlobalExceptionHandler} looks for. The message carries the configured limit and nothing
 * from the request.
 */
public class RequestBodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * The one English sentence both refusal paths return, so a caller sees the same body whether the
     * declared {@code Content-Length} was refused up front or the streamed body was cut off.
     */
    public static final String DETAIL = "The request body is larger than this API accepts.";

    public RequestBodyTooLargeException(long limitBytes) {
        super("request body exceeded the configured limit of " + limitBytes + " bytes");
    }
}
