package io.nullnull.crowd.infrastructure.seoul;

/** Fail-closed configuration and request-shape errors for the Seoul live-area source. */
public class SeoulGatewayException extends RuntimeException {

    public enum Code { SEOUL_NOT_CONFIGURED, SEOUL_BASE_URL_NOT_APPROVED, SEOUL_AREA_NOT_ACCEPTED }

    private final Code code;

    public SeoulGatewayException(Code code) {
        // The message is the code and nothing else: this type is thrown from the one place that holds
        // the proxy token, and a message that quoted the URL or the configuration would carry it.
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
