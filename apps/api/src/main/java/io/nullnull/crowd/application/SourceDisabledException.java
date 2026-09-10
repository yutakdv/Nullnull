package io.nullnull.crowd.application;

public final class SourceDisabledException extends RuntimeException {

    public SourceDisabledException() {
        super("SOURCE_DISABLED");
    }
}
