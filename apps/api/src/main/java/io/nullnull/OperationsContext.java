package io.nullnull;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * How an operations tool - a Gradle {@code JavaExec} main that runs against a database - starts the
 * application. Every such tool goes through here; {@code ArchitectureRulesTest} refuses any other class
 * that starts one.
 *
 * <p>Three things the application does when it starts as a service are wrong for a tool pointed at a
 * deployed database, and each is decided here rather than left to the operator's environment:
 * <ul>
 *   <li><b>The job worker.</b> {@code nullnull.jobs.enabled} defaults to true, so a tool would claim that
 *       environment's jobs alongside the real workers. It is disabled on the command line, which outranks
 *       the environment, so a deployed {@code NULLNULL_JOBS_ENABLED=true} cannot turn it back on.</li>
 *   <li><b>Migrating.</b> A checkout newer than the deployed release would migrate the database ahead of
 *       the deploy. When Flyway runs, its strategy is replaced: in {@code local}/{@code test} it migrates,
 *       as the application does there (ktoCallInventory and the demo refreshes used to run with Flyway off
 *       and now migrate a local database too); anywhere else it migrates nothing and validates, refusing a
 *       database missing a migration this checkout has, or holding one that failed or was changed. A
 *       database AHEAD of the checkout passes, as Flyway's default has it: migrations here are kept
 *       rollback-compatible, and the staging operator's supported rollback runs an older image on a newer
 *       schema. When the environment turns Flyway off - the deployed services and the staging ops task do,
 *       because a separate task migrates and the application role may not read flyway_schema_history -
 *       nothing is migrated or validated, only Hibernate's {@code ddl-auto: validate} compares the mapped
 *       tables, and the target line says {@code schema=unchecked} instead of leaving it to be inferred.</li>
 *   <li><b>Choosing the database silently</b> (#183). Before the context connects anywhere it prints the
 *       database, environment, access and schema check. A writing tool in a deployed environment also needs
 *       {@code NULLNULL_OPERATIONS_TARGET} set to that exact target, read from the shell like the smoke
 *       approval flags - so the run is bound to the database the person named, and a
 *       {@code spring.datasource.url} that differs from it is refused before a connection is opened. Settings
 *       that would connect the pool or Flyway somewhere else ({@code spring.datasource.hikari.jdbc-url},
 *       {@code spring.flyway.url}) leave no target to confirm.</li>
 * </ul>
 *
 * <p>What this cannot see: the environment is the {@code nullnull.env} label, which defaults to
 * {@code local}. A deployed database reached with the label left at {@code local} is treated as local -
 * migrated and written without a target named - so the deployed-environment steps set
 * {@code NULLNULL_ENV}, and the staging ops task sets it itself. A label outside the application's four
 * ({@code local}, {@code test}, {@code staging}, {@code production}, exact case, as AccessLogFilter reads
 * them) is refused here rather than guessed.
 */
public final class OperationsContext {

    /** What a tool does to the database. */
    public enum Access { READ, WRITE }

    /** On the command line because it outranks the environment. */
    static final List<String> ARGUMENTS = List.of("--nullnull.jobs.enabled=false");

    /** Where a tool may migrate, as the application does when it starts there (AccessLogFilter's vocabulary). */
    static final Set<String> MIGRATING_ENVIRONMENTS = Set.of("local", "test");

    /** Where a writing tool must be told its database. */
    static final Set<String> DEPLOYED_ENVIRONMENTS = Set.of("staging", "production");

    static final String TARGET_CONFIRMATION = "NULLNULL_OPERATIONS_TARGET";

    /**
     * A refusal from here. The code is what a tool's one-line failure may carry - the staging operator log
     * passes only a code without digits after "failed: " - and the message, which names the database, is for
     * the person at the shell.
     */
    public static final class Refused extends IllegalStateException {

        public enum Code {
            ENVIRONMENT_UNKNOWN, OPERATIONS_TARGET_NOT_CONFIRMED, OPERATIONS_TARGET_UNREADABLE, SCHEMA_NOT_THIS_CHECKOUT,
            SCHEMA_UNCHECKABLE
        }

        private final Code code;

        public Refused(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private OperationsContext() {
    }

    @SafeVarargs
    public static ConfigurableApplicationContext start(Access access,
            ApplicationContextInitializer<ConfigurableApplicationContext>... settings) {
        return new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(guarded(access, System.getenv(TARGET_CONFIRMATION), List.of(settings)))
                .registerShutdownHook(false)
                .run(ARGUMENTS.toArray(String[]::new));
    }

    /**
     * One initializer, so the order is written here and not left to how Spring sorts unordered ones: the
     * tool's own settings first (they can carry the datasource), then the label, the target line and its
     * confirmation, then the schema guard. Initializers run before the context refreshes, so a refusal comes
     * before any bean exists - no connection, no migration, and none of the startup work a service does
     * against its own database (the tombstone reapplication writes).
     */
    static ApplicationContextInitializer<ConfigurableApplicationContext> guarded(Access access, String confirmation,
            List<ApplicationContextInitializer<ConfigurableApplicationContext>> settings) {
        return context -> {
            settings.forEach(setting -> setting.initialize(context));
            String environment = context.getEnvironment().getProperty("nullnull.env", "");
            if (!MIGRATING_ENVIRONMENTS.contains(environment) && !DEPLOYED_ENVIRONMENTS.contains(environment)) {
                throw new Refused(Refused.Code.ENVIRONMENT_UNKNOWN, "nullnull.env must be one of local, test,"
                        + " staging, production (exact case); it is '" + environment + "'");
            }
            boolean elsewhere = context.getEnvironment().containsProperty("spring.datasource.hikari.jdbc-url")
                    || context.getEnvironment().containsProperty("spring.flyway.url");
            String target = elsewhere ? "unknown"
                    : target(context.getEnvironment().getProperty("spring.datasource.url", ""));
            boolean flywayOn = context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class, true);
            String schema = !flywayOn ? "unchecked"
                    : MIGRATING_ENVIRONMENTS.contains(environment) ? "migrate" : "validate";
            System.out.println("operations target=" + target + " environment=" + environment
                    + " access=" + access.name().toLowerCase(Locale.ROOT) + " schema=" + schema);
            if (access == Access.WRITE) {
                requireConfirmedTarget(environment, target, confirmation);
            }
            if (!(context instanceof GenericApplicationContext generic)) {
                throw new IllegalStateException("cannot install the schema guard in " + context.getClass().getName());
            }
            generic.registerBean(FlywayMigrationStrategy.class, () -> flyway -> guardSchema(flyway, environment));
        };
    }

    /**
     * Migrates locally; anywhere else migrates nothing and validates with Flyway's own rules (pending, failed
     * or changed migrations refuse; newer ones the checkout does not know pass). The refusal names each
     * migration and Flyway's code for it, not Flyway's advice, which suggests running migrate or repair.
     */
    static void guardSchema(Flyway flyway, String environment) {
        if (MIGRATING_ENVIRONMENTS.contains(environment)) {
            flyway.migrate();
            return;
        }
        ValidateResult result;
        try {
            result = flyway.validateWithResult();
        } catch (FlywayException unreadable) {
            throw new Refused(Refused.Code.SCHEMA_UNCHECKABLE, "environment=" + environment + ": the schema check"
                    + " could not read the database's migration history (" + unreadable.getClass().getSimpleName()
                    + "). In staging the application role may not read flyway_schema_history; run with"
                    + " SPRING_FLYWAY_ENABLED=false as the deployed service does (schema=unchecked), or as a role"
                    + " that can read it.");
        }
        if (!result.validationSuccessful) {
            String problems = result.invalidMigrations.stream()
                    .map(invalid -> invalid.version + " " + invalid.errorDetails.errorCode)
                    .collect(Collectors.joining(", "));
            if (problems.isEmpty() && result.errorDetails != null) {
                problems = String.valueOf(result.errorDetails.errorCode);
            }
            throw new Refused(Refused.Code.SCHEMA_NOT_THIS_CHECKOUT, "environment=" + environment
                    + ": the database's migrations do not match this checkout (" + problems + "), so this tool"
                    + " will not run against it. Run the deployed release's code; this tool does not migrate or"
                    + " repair a deployed database.");
        }
    }

    /** A writing tool in a deployed environment runs only against the database the shell named. */
    static void requireConfirmedTarget(String environment, String target, String confirmed) {
        if (!DEPLOYED_ENVIRONMENTS.contains(environment)) {
            return;
        }
        if ("unknown".equals(target)) {
            throw new Refused(Refused.Code.OPERATIONS_TARGET_UNREADABLE, "environment=" + environment
                    + ": the datasource URL could not be read,"
                    + " so there is no target to confirm");
        }
        String stated = confirmed == null ? "" : confirmed.strip();
        if (!stated.equals(target)) {
            throw new Refused(Refused.Code.OPERATIONS_TARGET_NOT_CONFIRMED, "environment=" + environment
                    + ": a writing tool needs "
                    + TARGET_CONFIRMATION + "=" + target + " (the database printed above); it is "
                    + (stated.isEmpty() ? "unset" : "set to another value, which is not repeated here"));
        }
    }

    /**
     * The database as {@code postgresql://host:port/name}: no user, no password, no query string, so the
     * line can go into a log or the evidence ledger as it is. It is "unknown" rather than named after a
     * database the driver would not connect to when the PostgreSQL driver would read the URL differently:
     * query parameters can replace the host, port and database ({@code host=}, {@code port=},
     * {@code dbname=} in any case, or exactly {@code PGHOST}/{@code PGPORT}/{@code PGDBNAME} -
     * PGPropertyUtil.translatePGServiceToPGProperty; lower-case {@code pg*} keys, which the driver ignores,
     * count too, to fail closed); the driver reads everything after the first '?' as the query, so a '#'
     * could hide one from this parse; and a URL naming no database takes it from elsewhere (the user name, a
     * {@code service=} entry). {@code service=} otherwise ranks below the URL and cannot replace what it names.
     */
    public static String target(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:") || jdbcUrl.indexOf('#') >= 0) {
            return "unknown";
        }
        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() <= 1
                    || replacesTheAddress(uri.getRawQuery())) {
                return "unknown";
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + port + uri.getPath();
        } catch (IllegalArgumentException malformed) {
            return "unknown";
        }
    }

    private static final Set<String> ADDRESS_KEYS = Set.of("host", "port", "dbname", "pghost", "pgport", "pgdbname");

    private static boolean replacesTheAddress(String query) {
        return query != null && Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2)[0].toLowerCase(Locale.ROOT))
                .anyMatch(ADDRESS_KEYS::contains);
    }
}
