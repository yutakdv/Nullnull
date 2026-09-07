package io.nullnull.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.recommendation.domain.PolicyPins;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The few policy numbers Spring enforces on its own (caps, the metric minimums, the cursor TTL) are
 * pinned in {@link PolicyPins}. They must equal the values in the file {@code apps/ai} hashes into
 * every run fingerprint; a policy edit that forgets this side fails here instead of silently letting
 * Spring accept what the service would refuse.
 */
@DisplayName("policy-v1 pin parity with apps/ai")
class PolicyPinsParityTest {

    static Map<String, Object> policy;
    static byte[] bytes;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void loadPolicy() throws IOException {
        String property = System.getProperty("nullnull.ai.policy.path");
        assertThat(property).as("system property nullnull.ai.policy.path").isNotBlank();
        bytes = Files.readAllBytes(Path.of(property));
        policy = new Yaml().loadAs(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Map.class);
        assertThat(policy).as("policy-v1.yaml").isNotNull();
    }

    @Test
    void thePolicyHashIsTheSha256OfTheFileTheServiceReads() throws NoSuchAlgorithmException {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(PolicyPins.V1.policyHash()).isEqualTo(digest);
        assertThat(PolicyPins.V1.policyVersion()).isEqualTo(policy.get("policyVersion"));
    }

    @Test
    void thePipelineVersionMatchesTheFile() {
        // The pin is what ProposalRevalidator fails closed against, so a renamed pipeline must not be
        // accepted just because the worker happened to cache the new name.
        assertThat(PolicyPins.V1.pipelineVersion()).isEqualTo(policy.get("pipelineVersion"));
    }

    @Test
    void numericAndCapsMatchTheFile() {
        Map<?, ?> numeric = (Map<?, ?>) policy.get("numeric");
        assertThat(PolicyPins.V1.numericScale()).isEqualTo(numeric.get("scale"));
        assertThat(PolicyPins.V1.roundingMode()).isEqualTo(RoundingMode.valueOf((String) numeric.get("roundingMode")));
        Map<?, ?> caps = (Map<?, ?>) policy.get("candidateCaps");
        assertThat(PolicyPins.V1.caps().feedSnapshot()).isEqualTo(caps.get("feedSnapshot"));
        assertThat(PolicyPins.V1.caps().relatedMerged()).isEqualTo(caps.get("relatedMerged"));
        assertThat(PolicyPins.V1.caps().slotDates()).isEqualTo(caps.get("slotDates"));
        assertThat(PolicyPins.V1.caps().itemProposals()).isEqualTo(caps.get("itemProposals"));
    }

    @Test
    void theItemObjectiveWeightsMatchTheFile() {
        Map<?, ?> objective = (Map<?, ?>) policy.get("itemObjective");
        assertThat(PolicyPins.V1.reliefWeight()).isEqualByComparingTo(new java.math.BigDecimal(
                (String) objective.get("reliefWeight")));
        assertThat(PolicyPins.V1.reliefWeight().toPlainString()).isEqualTo(objective.get("reliefWeight"));
        assertThat(PolicyPins.V1.changeCostWeight().toPlainString()).isEqualTo(objective.get("changeCostWeight"));
        assertThat(PolicyPins.V1.changeCostSaturationMinutes())
                .isEqualTo(objective.get("changeCostSaturationMinutes"));
    }

    @Test
    void everyPinnedMetricMatchesTheFileAndNoneIsInvented() {
        Map<?, ?> metrics = (Map<?, ?>) policy.get("metrics");
        assertThat(PolicyPins.V1.metrics().keySet())
                .as("a metric scale is never reused for another metric (§5.5)")
                .containsExactlyInAnyOrderElementsOf(metrics.keySet().stream().map(String::valueOf).toList());
        PolicyPins.V1.metrics().forEach((code, pin) -> {
            Map<?, ?> declared = (Map<?, ?>) metrics.get(code);
            assertThat(pin.metricScale()).as("%s metricScale", code).isEqualTo(declared.get("metricScale"));
            assertThat(pin.minimumImprovement()).as("%s minimumImprovement", code)
                    .isEqualTo(declared.get("minimumImprovement"));
        });
    }

    @Test
    void theFeedCursorTtlMatchesTheFile() {
        assertThat(PolicyPins.V1.feedCursorTtlMinutes()).isEqualTo(policy.get("feedCursorTtlMinutes"));
    }
}
