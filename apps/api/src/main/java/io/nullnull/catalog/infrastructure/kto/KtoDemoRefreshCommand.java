package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.NullnullApplication;
import io.nullnull.catalog.application.CanonicalCatalogStore;
import io.nullnull.catalog.application.CatalogIngest;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.application.KtoPlaceSnapshotStore;
import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastSnapshotStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What the two ktoDemoRefresh mains share: read the place list, check the approval and environment, run
 * one mode, and fail the process if any place failed.
 *
 * <p>The job worker and Flyway are turned off on the command line, which outranks the environment, as
 * KtoCallInventoryMain does: pointed at staging, a second worker would claim that environment's jobs, and
 * a checkout newer than the deployed release would migrate its database. The ops task definition sets both
 * off as well; this does not rely on it.
 *
 * <p>Every failure ends in an exception whose message is "KTO demo refresh failed: CODE", and a failed
 * place is one: the others still run, and the process then exits 1 so the schedule's alarm sees it. The
 * code carries no digits and no provider text, because the operator log allowlist
 * (scripts/aws/staging_operator.py OPS_LOG_LINE) passes only that shape; the counts are on the
 * KTO_DEMO_REFRESH_DONE line before it.
 */
final class KtoDemoRefreshCommand {

    static final String PLACES = "NULLNULL_DEMO_PLACES";
    static final String DETAIL_APPROVAL = "NULLNULL_KTO_SMOKE_APPROVED";
    static final String FORECAST_APPROVAL = "NULLNULL_KTO_FORECAST_SMOKE_APPROVED";

    private KtoDemoRefreshCommand() {
    }

    static void run(KtoDemoRefresh.Mode mode, Map<String, String> environment) {
        List<KtoPlaceRequest> places = before(mode, environment);
        Map<String, String> settings = KtoSmokeEnvironment.load(environment, java.nio.file.Path.of(".env.local"));
        String requestedEnvironment = KtoSmokeEnvironment.environment(settings);
        KtoSmokeEnvironment.sources(environment, java.nio.file.Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_DEMO_REFRESH_SETTINGS " + line));
        requirePermittedEnvironment(requestedEnvironment);
        KtoDemoRefresh.Report report;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(KtoSmokeEnvironment.applying(settings))
                .registerShutdownHook(false)
                .run("--nullnull.jobs.enabled=false", "--spring.flyway.enabled=false")) {
            String effective = context.getEnvironment().getProperty("nullnull.env", requestedEnvironment);
            requirePermittedEnvironment(effective);
            KtoKorServiceProperties properties = context.getBean(KtoKorServiceProperties.class);
            if ("staging".equals(effective) && !properties.isContestProfile()) {
                throw failure("CONTEST_PROFILE_REQUIRED");
            }
            if (mode == KtoDemoRefresh.Mode.FORECAST) {
                properties.requireForecastConfigured(false);
            }
            report = refresh(context).run(mode, places, System.out::println);
        } catch (RuntimeException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith(PREFIX)) {
                throw failure;
            }
            throw failure(code(failure));
        }
        finish(report);
    }

    /** The gateway's code when there is one, reduced to what the log allowlist passes after "failed: ". */
    static String code(Throwable failure) {
        String code = KtoSmokeEnvironment.failureCode(failure).replaceAll("[^A-Za-z_ ()]", "").strip();
        return code.isEmpty() ? "UNEXPECTED_FAILURE" : code;
    }

    /**
     * Everything checked before a context starts or a call is made: the approval for this mode and the
     * place list. A refusal here has made no call.
     */
    static List<KtoPlaceRequest> before(KtoDemoRefresh.Mode mode, Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String approval = mode == KtoDemoRefresh.Mode.FORECAST ? FORECAST_APPROVAL : DETAIL_APPROVAL;
        if (!"true".equals(environment.get(approval))) {
            throw failure("APPROVAL_NOT_SET");
        }
        try {
            return KtoDemoRefresh.places(environment.get(PLACES));
        } catch (IllegalArgumentException invalid) {
            System.out.println("KTO_DEMO_REFRESH_REFUSED reason=" + invalid.getMessage().replace(' ', '_'));
            throw failure("INVALID_PLACE_LIST");
        }
    }

    /** A run in which any place failed fails the process, after every place had its turn. */
    static void finish(KtoDemoRefresh.Report report) {
        if (report.failed()) {
            throw failure("PLACE_FAILED");
        }
    }

    private static KtoDemoRefresh refresh(ConfigurableApplicationContext context) {
        return new KtoDemoRefresh(context.getBean(KtoPlaceDetailGateway.class),
                context.getBean(KtoPlaceSnapshotStore.class), context.getBean(CatalogIngest.class),
                context.getBean(CanonicalCatalogStore.class), context.getBean(KtoCrowdForecastGateway.class),
                context.getBean(KtoForecastSnapshotStore.class), context.getBean(CrowdForecastQuery.class),
                context.getBean(SourceRegistryQuery.class), context.getBean(Clock.class));
    }

    private static void requirePermittedEnvironment(String environment) {
        if (!"local".equals(environment) && !"staging".equals(environment)) {
            throw failure("ENVIRONMENT_NOT_PERMITTED");
        }
    }

    private static final String PREFIX = "KTO demo refresh failed: ";

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(PREFIX + code);
    }
}
