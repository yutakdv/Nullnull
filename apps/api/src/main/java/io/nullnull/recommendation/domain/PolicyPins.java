package io.nullnull.recommendation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/**
 * The policy values Spring enforces on its own, pinned from
 * {@code apps/ai/src/nullnull_ai/policy/policy-v1.yaml}. The recommendation service owns the policy
 * and computes with it; Spring only needs the caps it re-checks, the metric minimums it re-validates
 * a returned proposal against, and the identity ({@code policyVersion}, {@code policyHash},
 * {@code pipelineVersion}) it records with every run. {@code policyHash} is the SHA-256 of that YAML
 * file's bytes, the same value {@code apps/ai/tests/recommendation/manifest.json} records;
 * {@code PolicyPinsParityTest} fails the build if either side drifts.
 *
 * <p>Nothing here is computed: an unpinned metric is refused, never scored with another metric's scale
 * (§5.5).
 */
public record PolicyPins(String policyVersion, String policyHash, String pipelineVersion, int numericScale,
        RoundingMode roundingMode, Caps caps, BigDecimal reliefWeight, BigDecimal changeCostWeight,
        int changeCostSaturationMinutes, Map<String, MetricPin> metrics, int feedCursorTtlMinutes) {

    /** §4.1 caps Spring re-checks on a service answer. */
    public record Caps(int feedSnapshot, int relatedMerged, int slotDates, int itemProposals) {
    }

    /** Per-metric comparability inputs; reusing one metric's numbers for another is forbidden (§5.5). */
    public record MetricPin(int metricScale, int minimumImprovement) {
    }

    /** The only crowd metric P0 compares (docs/data/SOURCE_CATALOG.md). */
    public static final String KTO_RELATIVE_CONCENTRATION_INDEX = "KTO_RELATIVE_CONCENTRATION_INDEX";

    public static final PolicyPins V1 = new PolicyPins(
            "policy-v1",
            "fb3ac4babfe068886d9afcecb2cae3a430a04c6d06c8cca7a724f9dc584933db",
            "nullnull-ai-pipeline-v1",
            6,
            RoundingMode.HALF_EVEN,
            new Caps(300, 300, 30, 3),
            new BigDecimal("0.80"),
            new BigDecimal("0.20"),
            240,
            Map.of(KTO_RELATIVE_CONCENTRATION_INDEX, new MetricPin(100, 5)),
            15);

    public PolicyPins {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(roundingMode, "roundingMode");
        Objects.requireNonNull(caps, "caps");
        Objects.requireNonNull(reliefWeight, "reliefWeight");
        Objects.requireNonNull(changeCostWeight, "changeCostWeight");
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
        if (!policyHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("policyHash must be a SHA-256 hex digest");
        }
    }

    /** The pinned metric, or null when the policy does not cover it: an unknown metric is never scored. */
    public MetricPin metric(String metricCode) {
        return metrics.get(metricCode);
    }
}
