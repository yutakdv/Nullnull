package io.nullnull.live.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.SeoulCityDataValidator;
import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.application.SeoulLiveAreaGateway;
import io.nullnull.shared.provider.ProviderResponseValidator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
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

        boolean live = SeoulLiveCollectMain.collect("광화문·덕수궁", area -> {
            assertThat(area).isEqualTo("광화문·덕수궁");
            return CompletableFuture.completedFuture(
                    SeoulLiveAreaGateway.Collection.observed(UUID.randomUUID(), observation, SourceState.LIVE));
        }, new PrintStream(output));

        assertThat(live).isTrue();
        assertThat(output.toString()).isEqualTo("seoul_live_collect live=true\n");
    }

    @Test
    @DisplayName("BA-091-T16 거절된 관측은 수집 작업의 성공이 될 수 없다")
    void refusedObservationCannotLookLikeASuccessfulTask() {
        var output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> SeoulLiveCollectMain.collect("광화문·덕수궁", area ->
                CompletableFuture.completedFuture(SeoulLiveAreaGateway.Collection.refused(UUID.randomUUID(),
                        new SeoulLiveAreaGateway.Refusal(ProviderResponseValidator.Outcome.PROVIDER_ERROR,
                                SeoulCityDataValidator.Rule.RESULT_CODE))), new PrintStream(output)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output.toString()).doesNotContain("live=true");
    }

    @Test
    @DisplayName("BA-091-T23 이미 만료된 관측은 Live 수집 성공으로 보고되지 않는다")
    void alreadyStaleObservationIsDistinctFromLiveAndFailure() {
        var observation = new SeoulLiveAreaObservation("POI009", "광화문·덕수궁", "보통",
                Instant.parse("2026-09-20T06:15:00Z"), "issue", List.of());
        var output = new ByteArrayOutputStream();

        boolean live = SeoulLiveCollectMain.collect("광화문·덕수궁", area ->
                CompletableFuture.completedFuture(SeoulLiveAreaGateway.Collection.observed(UUID.randomUUID(),
                        observation, SourceState.STALE)), new PrintStream(output));
        assertThat(live).isFalse();
        assertThat(output.toString()).isEqualTo("seoul_live_collect stale=true\n");
    }

    @Test
    @DisplayName("BA-091-T27 거절된 서울 수집은 결과와 규칙을 한 줄로 남긴다")
    void aRefusalIsLoggedWithItsOutcomeAndRule() {
        var output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> SeoulLiveCollectMain.collect("광화문·덕수궁", area ->
                CompletableFuture.completedFuture(SeoulLiveAreaGateway.Collection.refused(UUID.randomUUID(),
                        new SeoulLiveAreaGateway.Refusal(ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                                SeoulCityDataValidator.Rule.AREA_MISMATCH))), new PrintStream(output)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output.toString()).isEqualTo("seoul_live_validation outcome=SCHEMA_DRIFT rule=area-mismatch\n");
    }
}
