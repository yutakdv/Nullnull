package io.nullnull.recommendation.domain.explanation;

import java.util.Objects;

/**
 * Mirrors {@code ExplanationRenderResponse}: one sentence and the writer that produced it.
 * {@code TEMPLATE} is the deterministic KO/EN sentence and {@code LLM} a rewrite that passed the
 * service's output validator; there is no third answer, so a model outage is visible as TEMPLATE
 * rather than as a missing explanation. {@code summary} is text - no caller turns it into a command,
 * renders it as HTML, or reads a fact out of it that the request did not already carry (§9.1).
 */
public record ExplanationRenderResponse(String policyVersion, String policyHash, String pipelineVersion,
        String summary, Source source) {

    /** Who wrote the sentence. Any other value is not a known writer and fails to deserialize. */
    public enum Source { TEMPLATE, LLM }

    public ExplanationRenderResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(source, "source");
    }
}
