package io.nullnull.crowd.application;

/** Safe refusal when an audit run is reused to consume a different source's quota. */
public final class CollectorRunSourceMismatchException extends RuntimeException {

    public CollectorRunSourceMismatchException() {
        super("COLLECTOR_RUN_SOURCE_MISMATCH");
    }
}
