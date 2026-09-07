package io.nullnull.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** §8 fingerprint: the same run inputs always hash the same, and every input takes part. */
@DisplayName("§8 run fingerprint")
class RunFingerprintTest {

    static final UUID REV = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93e01");
    static final UUID S1 = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93f01");
    static final UUID S2 = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93f02");

    static RunFingerprint.Inputs inputs(List<UUID> snapshots, Map<String, Integer> registry) {
        return inputs(snapshots, registry, "nullnull-ai-pipeline-v1");
    }

    static RunFingerprint.Inputs inputs(List<UUID> snapshots, Map<String, Integer> registry, String pipelineVersion) {
        return new RunFingerprint.Inputs(REV, 7, new LinkedHashSet<>(snapshots), registry, "kto-forecast-v1",
                "policy-v1", "a".repeat(64), pipelineVersion, "catalog-1", Instant.parse("2026-09-06T01:00:00Z"));
    }

    @Test
    void orderOfSnapshotsAndRegistryKeysDoesNotMatter() {
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put("KTO_CONCENTRATION_FORECAST", 3);
        a.put("KTO_KOR_SERVICE_2", 1);
        Map<String, Integer> b = new LinkedHashMap<>();
        b.put("KTO_KOR_SERVICE_2", 1);
        b.put("KTO_CONCENTRATION_FORECAST", 3);
        assertThat(RunFingerprint.of(inputs(List.of(S1, S2), a)))
                .isEqualTo(RunFingerprint.of(inputs(List.of(S2, S1), b)));
    }

    @Test
    void anyChangedInputChangesTheFingerprint() {
        String base = RunFingerprint.of(inputs(List.of(S1), Map.of("KTO_CONCENTRATION_FORECAST", 3)));
        assertThat(RunFingerprint.of(inputs(List.of(S1, S2), Map.of("KTO_CONCENTRATION_FORECAST", 3))))
                .isNotEqualTo(base);
        assertThat(RunFingerprint.of(inputs(List.of(S1), Map.of("KTO_CONCENTRATION_FORECAST", 4))))
                .isNotEqualTo(base);
        assertThat(base).matches("^[0-9a-f]{64}$");
    }

    @Test
    void pipelineVersionIsPartOfTheFingerprint() {
        // optimization_runs.algorithm_version: the same policy computed by another pipeline is another run.
        String base = RunFingerprint.of(inputs(List.of(S1), Map.of("KTO_CONCENTRATION_FORECAST", 3)));
        assertThat(RunFingerprint.of(inputs(List.of(S1), Map.of("KTO_CONCENTRATION_FORECAST", 3),
                "nullnull-ai-pipeline-v2"))).isNotEqualTo(base);
    }

    @Test
    void aRunAlwaysPinsAtLeastOneSnapshot() {
        assertThatThrownBy(() -> inputs(List.of(), Map.of("KTO_CONCENTRATION_FORECAST", 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a run pins at least one snapshot");
    }
}
