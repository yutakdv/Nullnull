package io.nullnull;

import java.util.Map;
import org.flywaydb.core.Flyway;

/** One-off container entrypoint: no Spring context, HTTP listener, jobs or provider clients. */
public final class MigrationRunner {
    private MigrationRunner() { }

    public static void run(Map<String, String> environment) {
        String url = required(environment, "SPRING_DATASOURCE_URL");
        String username = required(environment, "SPRING_DATASOURCE_USERNAME");
        String password = required(environment, "SPRING_DATASOURCE_PASSWORD");
        if (!"false".equals(environment.get("NULLNULL_JOBS_ENABLED"))) {
            throw new IllegalArgumentException("Migration requires jobs disabled");
        }
        if (!"true".equals(environment.get("SPRING_FLYWAY_ENABLED"))) {
            throw new IllegalArgumentException("Migration requires Flyway enabled");
        }
        // Flyway's PostgreSQL locking serializes schema changes; the operator also locks the release.
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").validateOnMigrate(true)
                .cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
        if (flyway.info().pending().length != 0) {
            throw new IllegalStateException("Unapplied migrations remain");
        }
        provisionApplicationRole(url, username, password, environment);
        System.out.println("migration_result=pass");
    }

    private static void provisionApplicationRole(String url, String username, String password,
            Map<String, String> environment) {
        String appUser = required(environment, "NULLNULL_APP_DB_USERNAME");
        String appPassword = required(environment, "NULLNULL_APP_DB_PASSWORD");
        if (!"nullnull_app".equals(appUser)) throw new IllegalArgumentException("Unexpected application role");
        try (var connection = java.sql.DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try (var lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(714071)");
            }
            boolean exists;
            try (var query = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)")) {
                query.setString(1, appUser);
                try (var rows = query.executeQuery()) { rows.next(); exists = rows.getBoolean(1); }
            }
            if (!exists) {
                // PostgreSQL formats identifiers/literals; no password string concatenation.
                try (var format = connection.prepareStatement("SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', CAST(? AS text), CAST(? AS text))")) {
                    format.setString(1, appUser); format.setString(2, appPassword);
                    try (var rows = format.executeQuery(); var ddl = connection.createStatement()) {
                        rows.next(); ddl.execute(rows.getString(1));
                    }
                }
            }
            try (var grants = connection.createStatement()) {
                try (var name = connection.createStatement(); var rows = name.executeQuery(
                        "SELECT format('GRANT CONNECT ON DATABASE %I TO nullnull_app', current_database())")) {
                    rows.next(); grants.execute(rows.getString(1));
                }
                grants.execute("GRANT USAGE ON SCHEMA public TO nullnull_app");
                grants.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nullnull_app");
                grants.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nullnull_app");
                grants.execute("REVOKE ALL ON TABLE flyway_schema_history FROM nullnull_app");
            }
            connection.commit();
            // An existing role must still match the retained secret; never silently rotate it.
            try (var application = java.sql.DriverManager.getConnection(url, appUser, appPassword)) {
                if (!application.isValid(5)) throw new java.sql.SQLException("Application connection unavailable");
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Application role provisioning failed");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
