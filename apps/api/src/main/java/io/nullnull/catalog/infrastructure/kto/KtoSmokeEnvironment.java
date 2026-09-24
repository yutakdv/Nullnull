package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.OperationsContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.nullnull.catalog.application.KtoGatewayException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Set;

/** Reads only the KTO smoke allowlist from a local dotenv file; unrelated blank settings never reach Spring. */
final class KtoSmokeEnvironment {

    private static final Set<String> ALLOWED_NAMES = Set.of(
            "NULLNULL_ENV",
            "KTO_SERVICE_KEY",
            "KTO_BASE_URL",
            "KTO_FORECAST_BASE_URL",
            "KTO_ENG_BASE_URL",
            "KTO_MOBILE_APP",
            "KTO_MOBILE_OS",
            "KTO_ALLOWED_HOST",
            "APP_RELEASE_VERSION",
            "APP_CONTEST_PROFILE",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD");

    private KtoSmokeEnvironment() {}

    /**
     * Which source each allowed setting came from, names only.
     *
     * <p>{@link #load} lets a process environment variable beat the dotenv file, which is the right
     * precedence and an invisible one: editing .env.local then watching the run fail with the old
     * value gives no hint that the file was read and then overridden. This reports the origin so the
     * question "why is my edit not taking effect" is answerable in one line.
     *
     * <p>Values are never included. KTO_SERVICE_KEY and SPRING_DATASOURCE_PASSWORD are in this list,
     * and the whole point of the smoke harness is that secrets do not reach stdout.
     */
    static List<String> sources(Map<String, String> processEnvironment, Path dotenv) {
        Objects.requireNonNull(processEnvironment, "processEnvironment");
        Map<String, String> fromFile = readDotenv(dotenv);
        List<String> report = new ArrayList<>();
        for (String name : ALLOWED_NAMES.stream().sorted().toList()) {
            boolean inProcess = !normalized(processEnvironment.get(name)).isEmpty();
            boolean inFile = fromFile.containsKey(name);
            String origin = inProcess ? (inFile ? "process env (overrides .env.local)" : "process env")
                    : inFile ? ".env.local" : "absent";
            report.add(name + " <- " + origin);
        }
        return List.copyOf(report);
    }

    static Map<String, String> load(Map<String, String> processEnvironment, Path dotenv) {
        Objects.requireNonNull(processEnvironment, "processEnvironment");
        Objects.requireNonNull(dotenv, "dotenv");
        Map<String, String> values = new LinkedHashMap<>(readDotenv(dotenv));
        for (String name : ALLOWED_NAMES) {
            String processValue = normalized(processEnvironment.get(name));
            if (!processValue.isEmpty()) {
                values.put(name, processValue);
            }
        }
        return Map.copyOf(values);
    }

    static Map<String, Object> gatewayProperties(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        Map<String, Object> properties = new LinkedHashMap<>();
        if (values.containsKey("NULLNULL_ENV")) {
            properties.put("nullnull.env", environment(values));
        }
        put(properties, values, "KTO_SERVICE_KEY", "nullnull.kto.service-key");
        put(properties, values, "KTO_BASE_URL", "nullnull.kto.base-url");
        put(properties, values, "KTO_FORECAST_BASE_URL", "nullnull.kto.forecast-base-url");
        put(properties, values, "KTO_ENG_BASE_URL", "nullnull.kto.eng-base-url");
        put(properties, values, "KTO_MOBILE_APP", "nullnull.kto.mobile-app");
        put(properties, values, "KTO_MOBILE_OS", "nullnull.kto.mobile-os");
        put(properties, values, "APP_RELEASE_VERSION", "nullnull.kto.release-version");
        put(properties, values, "APP_CONTEST_PROFILE", "nullnull.kto.contest-profile");
        put(properties, values, "KTO_ALLOWED_HOST", "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]");
        // application.yaml gives every KTO source the same ${KTO_ALLOWED_HOST}; a dotenv value has to reach
        // the English source too, or a local English command refuses the host the Korean one accepts.
        put(properties, values, "KTO_ALLOWED_HOST", "nullnull.sources.KTO_ENG_SERVICE.allowed-hosts[0]");
        return Map.copyOf(properties);
    }

    static Map<String, Object> runtimeProperties(Map<String, String> values) {
        Map<String, Object> properties = new LinkedHashMap<>(gatewayProperties(values));
        put(properties, values, "SPRING_DATASOURCE_URL", "spring.datasource.url");
        put(properties, values, "SPRING_DATASOURCE_USERNAME", "spring.datasource.username");
        put(properties, values, "SPRING_DATASOURCE_PASSWORD", "spring.datasource.password");
        return Map.copyOf(properties);
    }

    /**
     * Puts the loaded settings where they actually win.
     *
     * <p>{@code SpringApplicationBuilder.properties(Map)} writes into {@code defaultProperties}, the
     * LOWEST precedence Spring Boot has - below {@code application.yaml}. Every key this class loads
     * has a yaml line of the form {@code ${KTO_SERVICE_KEY:}}, which resolves to an empty string when
     * the process environment has no such variable, and an empty string from a higher source beats a
     * real value from a lower one. So the file was read, reported as read, and then overridden by a
     * blank - which is how a correct .env.local produced KTO_NOT_CONFIGURED (#226).
     *
     * <p>{@code addFirst} puts it above every other source, so "read from .env.local" and "used" are
     * the same statement again.
     */
    static ApplicationContextInitializer<ConfigurableApplicationContext> applying(
            Map<String, String> settings) {
        Map<String, Object> properties = runtimeProperties(settings);
        return context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(APPLIED_SOURCE, properties));
    }

    /** The name this class's settings appear under in the environment, for anyone printing sources. */
    static final String APPLIED_SOURCE = "kto-operations-settings";

    /**
     * The failure code an operations command may print.
     *
     * <p>It walks to the first KtoGatewayException, because that is the adapter's own stable
     * vocabulary. When there is none the failure did not come from the provider at all, and the class
     * name says so - a Spring startup failure reading as UNEXPECTED_FAILURE is what made a
     * configuration problem look like a provider problem for two hours (#227). Types only: a message
     * can carry provider text or a configuration value.
     */
    static String failureCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OperationsContext.Refused refused) {
                return refused.code().name();
            }
            if (current instanceof KtoGatewayException gateway) {
                return gateway.failureType() == null
                        ? gateway.code().name()
                        : gateway.code().name() + " (" + gateway.failureType() + ")";
            }
        }
        return "UNEXPECTED_FAILURE (" + failure.getClass().getSimpleName() + ")";
    }

    static String environment(Map<String, String> values) {
        return normalized(values.getOrDefault("NULLNULL_ENV", "local")).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> readDotenv(Path dotenv) {
        if (!Files.isRegularFile(dotenv)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(dotenv)) {
                int equals = line.indexOf('=');
                if (equals < 1) {
                    continue;
                }
                String name = line.substring(0, equals).trim();
                if (!ALLOWED_NAMES.contains(name)) {
                    continue;
                }
                String value = unquote(normalized(line.substring(equals + 1)));
                if (!value.isEmpty()) {
                    values.put(name, value);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("unable to read local KTO smoke settings");
        }
        return values;
    }

    private static void put(Map<String, Object> target, Map<String, String> source, String environmentName,
            String propertyName) {
        String value = source.get(environmentName);
        if (value != null && !value.isBlank()) {
            target.put(propertyName, value);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
