package io.nullnull.live.domain;

/** Why a coarse viewport was refused. The value itself never appears in the message. */
public class LiveViewportException extends RuntimeException {

    public enum Code { VIEWPORT_OUT_OF_RANGE, VIEWPORT_TOO_PRECISE, VIEWPORT_TOO_SMALL }

    private final Code code;

    public LiveViewportException(Code code) {
        // The code and nothing else. A message carrying the rejected bounds would put the very
        // coordinates this type exists to keep coarse into a log line.
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
