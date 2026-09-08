package io.nullnull.recommendation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * §8 fingerprint: input trip revision, every crowd/route snapshot reference, source registry and
 * normalization versions, policy version+hash, the pipeline that computed the run and its validity.
 * Canonical text with sorted keys, then SHA-256. Never reduced to one latest snapshot id.
 *
 * <p>{@code policyHash} is the value the recommendation service reported for the run and
 * {@code pipelineVersion} is stored as {@code optimization_runs.algorithm_version}: the same policy
 * computed by another pipeline is a different run. {@code evaluatedAt} stays out of the canonical
 * text; {@code validUntil} carries the validity window the §8 list requires.
 */
public final class RunFingerprint {

    public record Inputs(UUID inputRevisionId, long tripVersion, Set<UUID> snapshotIds,
            Map<String, Integer> sourceRegistryVersions, String normalizationVersion, String policyVersion,
            String policyHash, String pipelineVersion, String catalogVersion, Instant validUntil) {

        public Inputs {
            Objects.requireNonNull(inputRevisionId, "inputRevisionId");
            snapshotIds = Set.copyOf(Objects.requireNonNull(snapshotIds, "snapshotIds"));
            sourceRegistryVersions = Map.copyOf(Objects.requireNonNull(sourceRegistryVersions, "sourceRegistryVersions"));
            Objects.requireNonNull(normalizationVersion, "normalizationVersion");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(policyHash, "policyHash");
            Objects.requireNonNull(pipelineVersion, "pipelineVersion");
            Objects.requireNonNull(catalogVersion, "catalogVersion");
            Objects.requireNonNull(validUntil, "validUntil");
            if (snapshotIds.isEmpty()) {
                throw new IllegalArgumentException("a run pins at least one snapshot");
            }
        }
    }

    private RunFingerprint() {
    }

    public static String of(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        StringBuilder canonical = new StringBuilder();
        canonical.append("revision=").append(inputs.inputRevisionId()).append('\n');
        canonical.append("tripVersion=").append(inputs.tripVersion()).append('\n');
        canonical.append("snapshots=")
                .append(String.join(",", new TreeSet<>(inputs.snapshotIds().stream().map(UUID::toString).toList())))
                .append('\n');
        new TreeMap<>(inputs.sourceRegistryVersions()).forEach((code, version) ->
                canonical.append("registry.").append(code).append('=').append(version).append('\n'));
        canonical.append("normalization=").append(inputs.normalizationVersion()).append('\n');
        canonical.append("policy=").append(inputs.policyVersion()).append('#').append(inputs.policyHash()).append('\n');
        canonical.append("pipeline=").append(inputs.pipelineVersion()).append('\n');
        canonical.append("catalog=").append(inputs.catalogVersion()).append('\n');
        canonical.append("validUntil=").append(inputs.validUntil().getEpochSecond()).append('\n');
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
