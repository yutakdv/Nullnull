package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.OperationsContext;
import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Explicit operator-only command for one approved KTO {@code detailCommon2} call.
 *
 * <p>It is deliberately a separate Gradle task rather than a web route or startup hook. The command requires
 * an approval flag and stable provider identifiers, accepts only local/staging environments, and prints only
 * redacted evidence identifiers after the gateway's transactional snapshot/audit write succeeds.</p>
 *
 * <p>It always calls. Its line is what the staging operator turns into the release's CMP-KTO-003 evidence
 * ({@code actual_call=verified}), so a stored snapshot handed back without a call would be recorded as a call
 * this release never made - and a place loaded within P7D is exactly that. So it asks for a snapshot that must
 * stay fresh far beyond any stored one's life, which only a new call can answer, and then checks that on the
 * clock the gateway stamps {@code fetchedAt} with: {@code called=true} is printed only for a snapshot fetched
 * after this run began, and anything else ends as {@code KTO_SMOKE_CACHED} and a failed process.</p>
 */
public final class KtoSmokeMain {

    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");

    /** Longer than any snapshot's life (the registry's stale_after, P7D for KorService2), so a stored one never answers. */
    public static final Duration FORCE_HORIZON = Duration.ofDays(365);

    private KtoSmokeMain() {}

    public static void main(String[] args) {
        SmokeRequest request = SmokeRequest.from(System.getenv());
        Map<String, String> settings = KtoSmokeEnvironment.load(System.getenv(), java.nio.file.Path.of(".env.local"));
        String requestedEnvironment = KtoSmokeEnvironment.environment(settings);
        KtoSmokeEnvironment.sources(System.getenv(), java.nio.file.Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_SMOKE_SETTINGS " + line));
        request.requirePermittedEnvironment(requestedEnvironment);
        Call call;
        try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE,
                KtoSmokeEnvironment.applying(settings))) {
            String environment = context.getEnvironment().getProperty("nullnull.env", requestedEnvironment);
            request.requirePermittedEnvironment(environment);
            KtoKorServiceProperties properties = context.getBean(KtoKorServiceProperties.class);
            if ("staging".equals(environment) && !properties.isContestProfile()) {
                throw new IllegalStateException("KTO smoke requires APP_CONTEST_PROFILE=2026_KTO_WEBAPP in staging");
            }

            call = call(context.getBean(KtoPlaceDetailGateway.class), context.getBean(Clock.class),
                    new KtoPlaceRequest(request.contentId(), request.contentTypeId()));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("KTO smoke failed: " + KtoSmokeEnvironment.failureCode(failure));
        }
        // Printed and judged after the context is closed, so this refusal keeps its own code rather than
        // becoming the catch-all above.
        System.out.println(redactedEvidence(call.snapshot(), call.startedAt()));
        refuseUnlessCalled(call);
    }

    /** A run that did not produce its snapshot ends the process with its own code, after its line is printed. */
    static void refuseUnlessCalled(Call call) {
        if (!calledByThisRun(call.snapshot(), call.startedAt())) {
            throw new IllegalStateException("KTO smoke failed: CACHED_SNAPSHOT");
        }
    }

    /** The snapshot the run got and the instant it asked, read from the clock the gateway stamps with. */
    public record Call(KtoPlaceSnapshot snapshot, Instant startedAt) {
    }

    /** The smoke's one provider request: a snapshot no stored one can satisfy, so the gateway has to call. */
    public static Call call(KtoPlaceDetailGateway gateway, Clock clock, KtoPlaceRequest request) {
        Instant startedAt = clock.instant();
        return new Call(gateway.detailFreshAt(request, startedAt.plus(FORCE_HORIZON)).join(), startedAt);
    }

    /**
     * Whether this run's own call produced the snapshot. The gateway stamps {@code fetchedAt} with the instant
     * its call started, from the same Clock bean {@code callStartedAt} was read from, so no two clocks meet here.
     *
     * <p>The second half is what the gateway's own choice rests on: a stored snapshot is handed back only when it
     * stays fresh past the asked instant ({@code stale_at > freshAt}), and a new call's {@code staleAt} is its
     * start plus the registry's life, which is never past that instant while the life is shorter than
     * {@link #FORCE_HORIZON}. So a snapshot another process wrote after this run began is still refused should
     * that life ever reach the horizon.
     */
    public static boolean calledByThisRun(KtoPlaceSnapshot snapshot, Instant callStartedAt) {
        return !snapshot.fetchedAt().isBefore(callStartedAt)
                && !snapshot.staleAt().isAfter(callStartedAt.plus(FORCE_HORIZON));
    }

    static String redactedEvidence(KtoPlaceSnapshot snapshot, Instant callStartedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(callStartedAt, "callStartedAt");
        String fields = " source=" + KtoPlaceSnapshot.SOURCE_CODE
                + " contentId=" + snapshot.contentId()
                + " contentTypeId=" + snapshot.contentTypeId()
                + " snapshotId=" + snapshot.id()
                + " collectorRunId=" + snapshot.collectorRunId()
                + " sourceRegistryVersion=" + snapshot.sourceRegistryVersion()
                + " payloadHash=" + snapshot.payloadHash()
                + " fetchedAt=" + snapshot.fetchedAt();
        return calledByThisRun(snapshot, callStartedAt)
                ? "KTO_SMOKE_OK" + fields + " called=true"
                : "KTO_SMOKE_CACHED" + fields + " called=false";
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
