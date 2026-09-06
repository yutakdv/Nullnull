package io.nullnull.recommendation.domain;

import java.util.Objects;

/** Mirrors {@code PolicyDescriptor} of the internal contract; policyHash is part of every run fingerprint. */
public record PolicyDescriptor(String policyVersion, String policyHash, String pipelineVersion, String serviceVersion) {

    public PolicyDescriptor {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(serviceVersion, "serviceVersion");
        if (!policyHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("policyHash must be a SHA-256 hex digest");
        }
    }
}
