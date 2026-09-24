package io.nullnull.shared.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The shipped application.yaml and the production host allowlist are two declarations of one list, and
 * ProviderKitTest compares the allowlist only with a literal written beside it. A source added to one and not
 * the other stayed green everywhere and failed at the first production start. This reads the shipped file's
 * defaults - what production gets when no override is set - and hands them to the production policy.
 */
@DisplayName("BA-020 production provider hosts match the shipped configuration")
class ProviderHostPolicyYamlTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Z0-9_]+:([^}]*)}");

    @Test
    @DisplayName("every source application.yaml configures is exactly the reviewed production allowlist")
    void shippedSourcesAreTheReviewedAllowlist() throws IOException {
        Map<String, Set<String>> shipped = shippedDefaultHosts();

        assertThat(shipped).as("sources read from application.yaml").isNotEmpty().containsKey("KTO_ENG_SERVICE");
        assertThatCode(() -> new ProviderHostPolicy("production").validate(shipped)).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> shippedDefaultHosts() throws IOException {
        try (InputStream in = ProviderHostPolicyYamlTest.class.getResourceAsStream("/application.yaml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> nullnull = (Map<String, Object>) root.get("nullnull");
            Map<String, Object> sources = (Map<String, Object>) nullnull.get("sources");
            Map<String, Set<String>> hosts = new LinkedHashMap<>();
            for (Map.Entry<String, Object> source : sources.entrySet()) {
                List<String> declared = (List<String>) ((Map<String, Object>) source.getValue()).get("allowed-hosts");
                Set<String> resolved = new LinkedHashSet<>();
                for (String host : declared) {
                    Matcher placeholder = PLACEHOLDER.matcher(host);
                    resolved.add(placeholder.matches() ? placeholder.group(1) : host);
                }
                hosts.put(source.getKey(), resolved);
            }
            return hosts;
        }
    }
}
