package io.nullnull.recommendation.infrastructure;

import io.nullnull.operations.application.ReadinessProbe;
import java.time.Instant;
import org.springframework.web.client.RestClient;

/** Optional probe: the API stays READY for trips/edits when the recommendation service is down (DEGRADED). */
public class RecommendationServiceProbe implements ReadinessProbe {

    private final RestClient client;

    /** Pass a builder with its own 1-second connect/read timeouts; never the gateway client. */
    public RecommendationServiceProbe(RestClient probeClient) {
        this.client = probeClient;
    }

    @Override
    public String name() {
        return "recommendation";
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public ProbeResult probe(Instant checkedAt) {
        try {
            client.get().uri("/internal/v1/health/ready").retrieve().toBodilessEntity();
            return new ProbeResult(ProbeStatus.READY, checkedAt, null);
        } catch (RuntimeException exception) {
            return new ProbeResult(ProbeStatus.UNAVAILABLE, checkedAt, "recommendation service unreachable");
        }
    }
}
