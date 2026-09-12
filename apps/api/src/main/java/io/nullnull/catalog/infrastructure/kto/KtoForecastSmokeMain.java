package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.NullnullApplication;
import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import io.nullnull.crowd.application.KtoForecastSnapshotStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Explicit local/staging proof for one C4 forecast call from a fresh, verified canonical KTO mapping.
 * It never starts a web listener and prints only redacted snapshot/audit IDs.
 */
public final class KtoForecastSmokeMain {

    private static final String APPROVAL = "NULLNULL_KTO_FORECAST_SMOKE_APPROVED";

    private KtoForecastSmokeMain() {}

    public static void main(String[] args) {
        SmokeRequest request = SmokeRequest.from(System.getenv());
        Map<String, String> settings = KtoSmokeEnvironment.load(System.getenv(), java.nio.file.Path.of(".env.local"));
        String requestedEnvironment = KtoSmokeEnvironment.environment(settings);
        KtoSmokeEnvironment.sources(System.getenv(), java.nio.file.Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_FORECAST_SMOKE_SETTINGS " + line));
        requirePermittedEnvironment(requestedEnvironment);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .properties(KtoSmokeEnvironment.runtimeProperties(settings))
                .registerShutdownHook(false)
                .run()) {
            String environment = context.getEnvironment().getProperty("nullnull.env", requestedEnvironment);
            requirePermittedEnvironment(environment);
            KtoKorServiceProperties properties = context.getBean(KtoKorServiceProperties.class);
            if ("staging".equals(environment) && !properties.isContestProfile()) {
                throw new IllegalStateException("KTO forecast smoke requires APP_CONTEST_PROFILE=2026_KTO_WEBAPP in staging");
            }
            properties.requireForecastConfigured(false);
            KtoForecastSnapshotStore snapshots = context.getBean(KtoForecastSnapshotStore.class);
            var mapping = snapshots.findFreshRequest(request.placeId(), Instant.now())
                    .orElseThrow(NoVerifiedKtoMappingException::new);
            KtoCrowdForecastGateway.RefreshResult result = context.getBean(KtoCrowdForecastGateway.class)
                    .refresh(mapping).join();
            System.out.println(redactedEvidence(request.placeId(), result));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("KTO forecast smoke failed: " + safeFailureCode(failure));
        }
    }

    static void requireApproval(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        if (!"true".equals(environment.get(APPROVAL))) {
            throw new IllegalArgumentException(APPROVAL + " must be true for an actual KTO forecast request");
        }
    }

    static String redactedEvidence(UUID placeId, KtoCrowdForecastGateway.RefreshResult result) {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(result, "result");
        return result.snapshotSet().map(set -> coveredEvidence(placeId, set))
                .orElse("KTO_FORECAST_SMOKE_OK source=" + KtoForecastSnapshotSet.SOURCE_CODE
                        + " placeId=" + placeId + " coverage=0");
    }

    private static String coveredEvidence(UUID placeId, KtoForecastSnapshotSet set) {
        return "KTO_FORECAST_SMOKE_OK source=" + KtoForecastSnapshotSet.SOURCE_CODE
                + " placeId=" + placeId
                + " coverage=" + set.points().size()
                + " snapshotSetId=" + set.id()
                + " collectorRunId=" + set.collectorRunId()
                + " sourceRegistryVersion=" + set.sourceRegistryVersion()
                + " forecastIssueId=" + set.forecastIssueId()
                + " payloadHash=" + set.payloadHash()
                + " fetchedAt=" + set.fetchedAt();
    }

    private static String safeFailureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof NoVerifiedKtoMappingException) {
                return "NO_VERIFIED_KTO_MAPPING";
            }
            if (current instanceof io.nullnull.catalog.application.KtoGatewayException gateway) {
                return gateway.code().name();
            }
            current = current.getCause();
        }
        return "UNEXPECTED_FAILURE";
    }

    private static void requirePermittedEnvironment(String environment) {
        if (!"local".equals(environment) && !"staging".equals(environment)) {
            throw new IllegalStateException("KTO forecast smoke is permitted only in local or staging");
        }
    }

    static final class SmokeRequest {
        private static final String PLACE_ID = "NULLNULL_KTO_FORECAST_SMOKE_PLACE_ID";

        private final UUID placeId;

        private SmokeRequest(UUID placeId) {
            this.placeId = placeId;
        }

        static SmokeRequest from(Map<String, String> environment) {
            requireApproval(environment);
            try {
                return new SmokeRequest(UUID.fromString(environment.get(PLACE_ID)));
            } catch (IllegalArgumentException | NullPointerException failure) {
                throw new IllegalArgumentException(PLACE_ID + " must be a canonical UUID");
            }
        }

        UUID placeId() {
            return placeId;
        }
    }

    private static final class NoVerifiedKtoMappingException extends RuntimeException {
    }
}
