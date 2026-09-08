package io.nullnull.operations.application;

import java.time.Instant;

/**
 * One readiness capability check. Required probes (database) make the whole application
 * NOT_READY when unavailable; optional probes (external sources, later) only degrade.
 */
public interface ReadinessProbe {

    String name();

    boolean required();

    ProbeResult probe(Instant checkedAt);

    enum ProbeStatus { READY, DEGRADED, UNAVAILABLE }

    /** Detail is operator-safe text: no hostnames, credentials or exception messages. */
    record ProbeResult(ProbeStatus status, Instant checkedAt, String detail) {
    }
}
