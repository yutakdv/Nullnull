package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.NullnullApplication;
import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Explicit operator-only command for one approved KTO {@code detailCommon2} read-through.
 *
 * <p>It is deliberately a separate Gradle task rather than a web route or startup hook. The command requires
 * an approval flag and stable provider identifiers, accepts only local/staging environments, and prints only
 * redacted evidence identifiers after the gateway's transactional snapshot/audit write succeeds.</p>
 */
public final class KtoSmokeMain {

    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");

    private KtoSmokeMain() {}

    public static void main(String[] args) {
        SmokeRequest request = SmokeRequest.from(System.getenv());
        Map<String, String> settings = KtoSmokeEnvironment.load(System.getenv(), java.nio.file.Path.of(".env.local"));
        String requestedEnvironment = KtoSmokeEnvironment.environment(settings);
        request.requirePermittedEnvironment(requestedEnvironment);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .properties(KtoSmokeEnvironment.runtimeProperties(settings))
                .registerShutdownHook(false)
                .run()) {
            String environment = context.getEnvironment().getProperty("nullnull.env", requestedEnvironment);
            request.requirePermittedEnvironment(environment);
            KtoKorServiceProperties properties = context.getBean(KtoKorServiceProperties.class);
            if ("staging".equals(environment) && !properties.isContestProfile()) {
                throw new IllegalStateException("KTO smoke requires APP_CONTEST_PROFILE=2026_KTO_WEBAPP in staging");
            }

            KtoPlaceSnapshot snapshot = context.getBean(KtoPlaceDetailGateway.class)
                    .detail(request.contentId(), request.contentTypeId())
                    .join();
            System.out.println(redactedEvidence(snapshot));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("KTO smoke failed: " + safeFailureCode(failure));
        }
    }

    static String redactedEvidence(KtoPlaceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "KTO_SMOKE_OK source=" + KtoPlaceSnapshot.SOURCE_CODE
                + " contentId=" + snapshot.contentId()
                + " contentTypeId=" + snapshot.contentTypeId()
                + " snapshotId=" + snapshot.id()
                + " collectorRunId=" + snapshot.collectorRunId()
                + " sourceRegistryVersion=" + snapshot.sourceRegistryVersion()
                + " payloadHash=" + snapshot.payloadHash()
                + " fetchedAt=" + snapshot.fetchedAt();
    }

    private static String safeFailureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof KtoGatewayException gateway) {
                return gateway.code().name();
            }
            current = current.getCause();
        }
        return "UNEXPECTED_FAILURE";
    }

    static final class SmokeRequest {
        private static final String APPROVAL = "NULLNULL_KTO_SMOKE_APPROVED";
        private static final String CONTENT_ID = "NULLNULL_KTO_SMOKE_CONTENT_ID";
        private static final String CONTENT_TYPE_ID = "NULLNULL_KTO_SMOKE_CONTENT_TYPE_ID";

        private final String contentId;
        private final String contentTypeId;

        private SmokeRequest(String contentId, String contentTypeId) {
            this.contentId = identifier(contentId, CONTENT_ID);
            this.contentTypeId = identifier(contentTypeId, CONTENT_TYPE_ID);
        }

        static SmokeRequest from(Map<String, String> environment) {
            Objects.requireNonNull(environment, "environment");
            if (!"true".equals(environment.get(APPROVAL))) {
                throw new IllegalArgumentException(APPROVAL + " must be true for an actual KTO request");
            }
            return new SmokeRequest(environment.get(CONTENT_ID), environment.get(CONTENT_TYPE_ID));
        }

        String contentId() {
            return contentId;
        }

        String contentTypeId() {
            return contentTypeId;
        }

        void requirePermittedEnvironment(String environment) {
            if (!"local".equals(environment) && !"staging".equals(environment)) {
                throw new IllegalStateException("KTO smoke is permitted only in local or staging");
            }
        }

        private static String identifier(String value, String name) {
            String normalized = value == null ? "" : value.trim();
            if (!IDENTIFIER.matcher(normalized).matches()) {
                throw new IllegalArgumentException(name + " must be a stable numeric KTO identifier");
            }
            return normalized;
        }
    }
}
