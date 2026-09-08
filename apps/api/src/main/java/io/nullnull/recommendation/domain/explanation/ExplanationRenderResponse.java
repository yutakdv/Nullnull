package io.nullnull.recommendation.domain.explanation;

import java.util.Objects;
import java.util.Set;

/**
 * Mirrors {@code ExplanationRenderResponse}: one sentence and the writer that produced it.
 * {@code TEMPLATE} is the deterministic KO/EN sentence and {@code LLM} a rewrite that passed the
 * service's output validator; there is no third answer, so a model outage is visible as TEMPLATE
 * rather than as a missing explanation. {@code summary} is text - no caller turns it into a command,
 * renders it as HTML, or reads a fact out of it that the request did not already carry (§9.1).
 *
 * <p>{@code source} is carried as a String on purpose. An unknown writer is a contract break, which
 * the gateway reports as non-retryable; an enum here would fail during deserialization instead and
 * the same answer would be filed as a transient outage and retried against the fallback path.
 */
public record ExplanationRenderResponse(String policyVersion, String policyHash, String pipelineVersion,
        String summary, String source) {

    /** The two published writers; the gateway rejects an answer that names any other. */
    public static final Set<String> SOURCES = Set.of("TEMPLATE", "LLM");

    /**
     * The contract's {@code maxLength} for the one line the FE renders (§9.1 template MAX_LENGTH). The
     * gateway enforces it as a response post-condition; the internal-contract parity test pins this
     * number against {@code apps/ai}, so tightening it here fails instead of silently discarding an
     * explanation the service is allowed to return.
     */
    public static final int MAX_SUMMARY_LENGTH = 500;

    public ExplanationRenderResponse {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(source, "source");
    }
}
