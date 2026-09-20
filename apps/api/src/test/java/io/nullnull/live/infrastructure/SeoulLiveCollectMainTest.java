package io.nullnull.live.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.application.SeoulLiveAreaGateway;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class SeoulLiveCollectMainTest {

    @Test
    void acceptedObservationIsReportedWithoutProviderText() {
        var observation = new SeoulLiveAreaObservation("POI009", "광화문·덕수궁", "보통",
                Instant.parse("2026-09-20T06:15:00Z"), "issue", List.of());
        var output = new ByteArrayOutputStream();

        SeoulLiveCollectMain.collect("광화문·덕수궁", area -> {
            assertThat(area).isEqualTo("광화문·덕수궁");
            return CompletableFuture.completedFuture(
                    new SeoulLiveAreaGateway.Collection(UUID.randomUUID(), Optional.of(observation), SourceState.LIVE));
        }, new PrintStream(output));

        assertThat(output.toString()).isEqualTo("seoul_live_collect live=true\n");
    }

    @Test
    @DisplayName("BA-091-T16 거절된 관측은 수집 작업의 성공이 될 수 없다")
    void refusedObservationCannotLookLikeASuccessfulTask() {
        var output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> SeoulLiveCollectMain.collect("광화문·덕수궁", area ->
                CompletableFuture.completedFuture(new SeoulLiveAreaGateway.Collection(UUID.randomUUID(),
                        Optional.empty(), null)), new PrintStream(output)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output.toString()).doesNotContain("live=true");
    }

    @Test
    @DisplayName("BA-091-T23 이미 만료된 관측은 Live 수집 성공으로 보고되지 않는다")
    void alreadyStaleObservationCannotLookLive() {
        var observation = new SeoulLiveAreaObservation("POI009", "광화문·덕수궁", "보통",
                Instant.parse("2026-09-20T06:15:00Z"), "issue", List.of());
        var output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> SeoulLiveCollectMain.collect("광화문·덕수궁", area ->
                CompletableFuture.completedFuture(new SeoulLiveAreaGateway.Collection(UUID.randomUUID(),
                        Optional.of(observation), SourceState.STALE)), new PrintStream(output)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output.toString()).doesNotContain("live=true");
    }
}
