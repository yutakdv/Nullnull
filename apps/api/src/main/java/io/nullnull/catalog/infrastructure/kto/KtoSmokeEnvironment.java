package io.nullnull.catalog.infrastructure.kto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads only the KTO smoke allowlist from a local dotenv file; unrelated blank settings never reach Spring. */
final class KtoSmokeEnvironment {

    private static final Set<String> ALLOWED_NAMES = Set.of(
            "NULLNULL_ENV",
            "KTO_SERVICE_KEY",
            "KTO_BASE_URL",
            "KTO_MOBILE_APP",
            "KTO_MOBILE_OS",
            "KTO_ALLOWED_HOST",
            "APP_RELEASE_VERSION",
            "APP_CONTEST_PROFILE",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD");

    private KtoSmokeEnvironment() {}

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
        put(properties, values, "KTO_MOBILE_APP", "nullnull.kto.mobile-app");
        put(properties, values, "KTO_MOBILE_OS", "nullnull.kto.mobile-os");
        put(properties, values, "APP_RELEASE_VERSION", "nullnull.kto.release-version");
        put(properties, values, "APP_CONTEST_PROFILE", "nullnull.kto.contest-profile");
        put(properties, values, "KTO_ALLOWED_HOST", "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]");
        return Map.copyOf(properties);
    }

    static Map<String, Object> runtimeProperties(Map<String, String> values) {
        Map<String, Object> properties = new LinkedHashMap<>(gatewayProperties(values));
        put(properties, values, "SPRING_DATASOURCE_URL", "spring.datasource.url");
        put(properties, values, "SPRING_DATASOURCE_USERNAME", "spring.datasource.username");
        put(properties, values, "SPRING_DATASOURCE_PASSWORD", "spring.datasource.password");
        return Map.copyOf(properties);
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
