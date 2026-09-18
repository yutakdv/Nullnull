package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.ReadinessProbe.ProbeResult;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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

    private static ReadinessProbe throwing(String name, boolean required, java.util.concurrent.atomic.AtomicInteger calls) {
        return new ReadinessProbe() {
            @Override public String name() { return name; }
            @Override public boolean required() { return required; }
            @Override public ProbeResult probe(Instant checkedAt) {
                calls.incrementAndGet();
                // What a registry read does when the pool cannot hand out a connection.
                throw new org.springframework.jdbc.CannotGetJdbcConnectionException("pool exhausted");
            }
        };
    }

    @Test
    @DisplayName("BA-003-T5 #258 a required probe that throws is NOT_READY, not an exception out of readiness")
    void aThrowingRequiredProbeIsNotReady() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ReadinessQuery query = new ReadinessQuery(List.of(throwing("database", true, calls)), CLOCK);

        var report = query.readiness();
        assertThat(report.state()).isEqualTo(ReadinessState.NOT_READY);
        assertThat(report.checks()).singleElement().satisfies(check -> {
            assertThat(check.result().status()).isEqualTo(ProbeStatus.UNAVAILABLE);
            // Operator-safe: the exception's message never becomes the detail.
            assertThat(check.result().detail()).isEqualTo("probe failed");
        });
    }

    @Test
    @DisplayName("BA-003-T5 #258 an optional probe that throws only degrades")
    void aThrowingOptionalProbeOnlyDegrades() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ReadinessQuery query = new ReadinessQuery(List.of(
                probe("database", true, ProbeStatus.READY), throwing("source:kto", false, calls)), CLOCK);

        var report = query.readiness();
        assertThat(report.state()).isEqualTo(ReadinessState.DEGRADED);
        assertThat(report.checks()).extracting(ReadinessQuery.CheckReport::name)
                .containsExactly("database", "source:kto");
    }

    @Test
    @DisplayName("BA-003-T6 #258 an unavailable required probe answers without running the optional ones")
    void optionalProbesDoNotRunOnceARequiredOneIsUnavailable() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        // Registered first, on purpose: the order of registration must not decide what runs.
        ReadinessQuery query = new ReadinessQuery(List.of(
                throwing("source:kto", false, calls), probe("database", true, ProbeStatus.UNAVAILABLE)), CLOCK);

        assertThat(query.readiness().state()).isEqualTo(ReadinessState.NOT_READY);
        assertThat(calls.get()).isZero();
    }

    @Test
    void optionalFailureOnlyDegrades() {
        ReadinessQuery query = new ReadinessQuery(List.of(
                probe("database", true, ProbeStatus.READY),
                probe("kto", false, ProbeStatus.UNAVAILABLE)), CLOCK);
        assertThat(query.readiness().state()).isEqualTo(ReadinessState.DEGRADED);
    }
}
