package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.ReadinessProbe.ProbeResult;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadinessQueryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

    private static ReadinessProbe probe(String name, boolean required, ProbeStatus status) {
        return new ReadinessProbe() {
            @Override public String name() { return name; }
            @Override public boolean required() { return required; }
            @Override public ProbeResult probe(Instant checkedAt) {
                return new ProbeResult(status, checkedAt, null);
            }
        };
    }

    @Test
    void allReadyIsReady() {
        ReadinessQuery query = new ReadinessQuery(List.of(probe("database", true, ProbeStatus.READY)), CLOCK);
        assertThat(query.readiness().state()).isEqualTo(ReadinessState.READY);
        assertThat(query.readiness().checks()).singleElement()
                .satisfies(check -> assertThat(check.result().checkedAt()).isEqualTo(CLOCK.instant()));
    }

    @Test
    void requiredUnavailableIsNotReady() {
        ReadinessQuery query = new ReadinessQuery(List.of(
                probe("database", true, ProbeStatus.UNAVAILABLE),
                probe("kto", false, ProbeStatus.READY)), CLOCK);
        assertThat(query.readiness().state()).isEqualTo(ReadinessState.NOT_READY);
    }

    @Test
    void optionalFailureOnlyDegrades() {
        ReadinessQuery query = new ReadinessQuery(List.of(
                probe("database", true, ProbeStatus.READY),
                probe("kto", false, ProbeStatus.UNAVAILABLE)), CLOCK);
        assertThat(query.readiness().state()).isEqualTo(ReadinessState.DEGRADED);
    }
}
