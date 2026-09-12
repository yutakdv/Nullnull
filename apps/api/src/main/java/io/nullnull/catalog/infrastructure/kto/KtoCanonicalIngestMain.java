package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.NullnullApplication;
import io.nullnull.catalog.application.CatalogIngest;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.application.KtoPlaceSnapshotStore;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The explicit call that {@link io.nullnull.catalog.application.KtoSnapshotCatalogIngest} was written
 * to require and that nothing provided.
 *
 * <p>The C2 gateway deliberately does not map its own snapshots into the canonical catalog - the
 * ingest is meant to be invoked on purpose, not as a side effect of fetching. But there was no way to
 * invoke it at all outside a test, and the C4 forecast smoke needs what it writes:
 * {@code KtoForecastSnapshotStore.findFreshRequest} joins {@code place_external_refs}, which only this
 * ingest creates. So {@code ktoSmoke} followed by {@code ktoForecastSmoke} could only ever end in
 * NoVerifiedKtoMappingException, and the C4 half of invariant 12 had no runnable path.
 *
 * <p>This makes no external request. It reads a snapshot an approved {@code ktoSmoke} already stored
 * and maps it, which is why it carries no provider approval flag: the approval belonged to the call
 * that produced the snapshot. It prints the canonical place ID, because that is the value
 * {@code ktoForecastSmoke} needs next.
 *
 * <p>Writing canonical rows is not publishing them. The public projection stays gated by
 * {@code nullnull.catalog.public-enabled} (CatalogPublicationProperties), which this does not touch.
 */
public final class KtoCanonicalIngestMain {

    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");
    private static final String CONTENT_ID = "NULLNULL_KTO_INGEST_CONTENT_ID";
    private static final String CONTENT_TYPE_ID = "NULLNULL_KTO_INGEST_CONTENT_TYPE_ID";

    private KtoCanonicalIngestMain() {}

    public static void main(String[] args) {
        KtoPlaceRequest request = request(System.getenv());
        Map<String, String> settings = KtoSmokeEnvironment.load(System.getenv(), java.nio.file.Path.of(".env.local"));
        String requestedEnvironment = KtoSmokeEnvironment.environment(settings);
        requirePermittedEnvironment(requestedEnvironment);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .properties(KtoSmokeEnvironment.runtimeProperties(settings))
                .registerShutdownHook(false)
                .run()) {
            requirePermittedEnvironment(context.getEnvironment().getProperty("nullnull.env", requestedEnvironment));
            KtoPlaceSnapshot snapshot = context.getBean(KtoPlaceSnapshotStore.class)
                    .findFresh(request, Instant.now())
                    .orElseThrow(NoStoredKtoSnapshotException::new);
            System.out.println(redactedEvidence(context.getBean(CatalogIngest.class).ingest(snapshot), snapshot));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("KTO canonical ingest failed: " + safeFailureCode(failure));
        }
    }

    static KtoPlaceRequest request(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new KtoPlaceRequest(identifier(environment.get(CONTENT_ID), CONTENT_ID),
                identifier(environment.get(CONTENT_TYPE_ID), CONTENT_TYPE_ID));
    }

    static void requirePermittedEnvironment(String environment) {
        if (!"local".equals(environment) && !"staging".equals(environment)) {
            throw new IllegalStateException("KTO canonical ingest is permitted only in local or staging");
        }
    }

    /** IDs only. The title and address the ingest mapped are provider text and stay out of stdout. */
    static String redactedEvidence(CatalogPlace place, KtoPlaceSnapshot snapshot) {
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(snapshot, "snapshot");
        return "KTO_CANONICAL_INGEST_OK placeId=" + place.id()
                + " contentId=" + snapshot.contentId()
                + " contentTypeId=" + snapshot.contentTypeId()
                + " sourceRegistryVersion=" + snapshot.sourceRegistryVersion()
                + " snapshotId=" + snapshot.id();
    }

    private static String identifier(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a stable numeric KTO identifier");
        }
        return normalized;
    }

    private static String safeFailureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof NoStoredKtoSnapshotException) {
                return "NO_FRESH_KTO_SNAPSHOT_RUN_KTO_SMOKE_FIRST";
            }
            current = current.getCause();
        }
        return "UNEXPECTED_FAILURE";
    }

    static final class NoStoredKtoSnapshotException extends RuntimeException {
        NoStoredKtoSnapshotException() {
            super("no fresh KTO snapshot for that content ID");
        }
    }
}
