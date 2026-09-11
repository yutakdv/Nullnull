package io.nullnull.shared.provider;

import java.util.Map;
import java.util.Set;

/**
 * Production source hosts are an allowlist owned in code, not an operator-selectable destination.
 * Test and local runs may use an isolated stub host, but production may not silently drift to one.
 */
final class ProviderHostPolicy {

    private static final Set<String> ENVIRONMENTS = Set.of("local", "test", "staging", "production");
    private static final Map<String, Set<String>> PRODUCTION_HOSTS = Map.of(
            "KTO_KOR_SERVICE_2", Set.of("apis.data.go.kr"),
            "KTO_CONCENTRATION_FORECAST", Set.of("apis.data.go.kr"),
            "KTO_RELATED_PLACES", Set.of("apis.data.go.kr"),
            "SEOUL_CITYDATA", Set.of("openapi.seoul.go.kr"));

    private final String environment;

    ProviderHostPolicy(String environment) {
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("NULLNULL_ENV must be one of " + ENVIRONMENTS);
        }
        this.environment = environment;
    }

    void validate(Map<String, Set<String>> configuredHosts) {
        if (!"production".equals(environment)) {
            return;
        }
        if (!PRODUCTION_HOSTS.equals(configuredHosts)) {
            throw new IllegalStateException("production provider hosts must match the reviewed exact allowlist");
        }
    }
}
