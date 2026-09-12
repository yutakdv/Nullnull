package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 503 alone does not say what failed, so an operation that can send one has to say which code.
 *
 * <p>Two {@code ProblemCode}s carry SERVICE_UNAVAILABLE - {@code SOURCE_UNAVAILABLE} for a data
 * source that is not published and {@code ROUTE_UNAVAILABLE} for a route that could not be
 * confirmed - so the shared {@code ServiceUnavailable} response cannot name one, and a client
 * reading only the status has nothing to branch on. Frontend hit exactly that: the 503 was declared
 * and the screen still fell through to a generic error.
 *
 * <p>The fix is not to pin a code on the shared response, which would force the same code on every
 * user of it. It is that an operation which KNOWS its code uses {@code SourceUnavailable}, and the
 * generic form is left to operations whose 503 does not exist yet. This test keeps that split
 * honest in both directions, and the second direction is the useful one: when BA-052 implements
 * {@code decideOptimization} this turns red, which is the reminder to decide its code rather than
 * inherit an unnamed 503.
 */
class ServiceUnavailableContractTest {

    /**
     * Operations whose 503 the server actually produces today, with the code it sends. Each is
     * evidenced by a test that asserts the live response, not by reading the source.
     */
    private static final Map<String, String> PRODUCED_503 = new LinkedHashMap<>(Map.of(
            // FeedFailsClosedIT asserts $.code on both.
            "listFeed", "SOURCE_UNAVAILABLE",
            "getPost", "SOURCE_UNAVAILABLE",
            // HealthController throws it when a readiness check reports NOT_READY.
            "getReadiness", "SOURCE_UNAVAILABLE"));

    private static final Pattern OPERATION = Pattern.compile("^\\s+operationId: (\\w+)\\s*$");

    private static List<String> contractLines() {
        String property = System.getProperty("nullnull.openapi.path");
        Path file = property == null || property.isBlank()
                ? Path.of("../../docs/api/openapi.yaml") : Path.of(property);
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the contract", missing);
        }
    }

    /** operationId -> the 503 response component it references, for every operation that has one. */
    private static Map<String, String> declared503() {
        Map<String, String> declarations = new LinkedHashMap<>();
        String operation = null;
        for (String line : contractLines()) {
            Matcher header = OPERATION.matcher(line);
            if (header.matches()) {
                operation = header.group(1);
                continue;
            }
            if (line.contains("responses/ServiceUnavailable")) {
                declarations.put(operation, "ServiceUnavailable");
            } else if (line.contains("responses/SourceUnavailable")) {
                declarations.put(operation, "SourceUnavailable");
            }
        }
        return declarations;
    }

    @Test
    @DisplayName("BA-032-T1 an operation that sends a 503 names the code it sends")
    void everyProducedFiveOhThreeNamesItsCode() {
        Map<String, String> declared = declared503();
        assertThat(declared).as("the parser found 503 declarations at all").isNotEmpty();

        List<String> unnamed = new ArrayList<>();
        for (Map.Entry<String, String> produced : PRODUCED_503.entrySet()) {
            assertThat(declared)
                    .as("%s answers 503 and must declare it", produced.getKey())
                    .containsKey(produced.getKey());
            if (!"SourceUnavailable".equals(declared.get(produced.getKey()))) {
                unnamed.add(produced.getKey());
            }
        }
        assertThat(unnamed)
                .as("these operations send SOURCE_UNAVAILABLE but declare the unnamed 503")
                .isEmpty();

        // Every code above really is SOURCE_UNAVAILABLE; a second code would need its own response
        // rather than being folded into this one.
        assertThat(PRODUCED_503.values()).containsOnly("SOURCE_UNAVAILABLE");
    }

    @Test
    @DisplayName("BA-032-T1 the unnamed 503 is left only to operations that do not exist yet")
    void theUnnamedFiveOhThreeIsOnlyForUnimplementedOperations() {
        List<String> implementedButUnnamed = declared503().entrySet().stream()
                .filter(entry -> "ServiceUnavailable".equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .filter(ImplementedOperationsRegistry.IMPLEMENTED::contains)
                .toList();
        // When BA-052 implements decideOptimization this fails, and that is the point: an
        // implemented operation whose 503 has no code is a screen that cannot branch on it.
        assertThat(implementedButUnnamed)
                .as("an implemented operation may not leave its 503 code unnamed")
                .isEmpty();
    }
}
