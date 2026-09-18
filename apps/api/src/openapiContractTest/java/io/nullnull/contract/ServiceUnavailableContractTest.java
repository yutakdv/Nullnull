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
            "getReadiness", "SOURCE_UNAVAILABLE",
            // TripDraftPreviewFailsClosedIT (closed catalog) and TripDraftPreviewGatewayIT (apps/ai
            // unanswered) assert $.code on both producers.
            "previewTripDraft", "SOURCE_UNAVAILABLE"));

    private static final Pattern OPERATION = Pattern.compile("^\\s+operationId: (\\w+)\\s*$");

    /** The 503 responses that say which code arrives. A generic one is no longer declarable. */
    private static final java.util.Set<String> NAMED_503 =
            java.util.Set.of("SourceUnavailable", "ApplyUnavailable");

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
            if (line.contains("responses/SourceUnavailable")) {
                declarations.put(operation, "SourceUnavailable");
            } else if (line.contains("responses/ApplyUnavailable")) {
                // Recognised here or this parser stops seeing the operation at all - and an operation
                // the scanner cannot see passes every assertion below by being absent, which is the
                // shape of green this suite exists to refuse.
                declarations.put(operation, "ApplyUnavailable");
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

    /**
     * BA-032-T1, rewritten when the unnamed 503 was deleted.
     *
     * <p>It used to assert that no IMPLEMENTED operation declared the generic {@code
     * ServiceUnavailable}. That component now has no referrer and has been removed, so the old
     * assertion could not fail for any input - the shape this suite exists to refuse.
     *
     * <p>What replaces it is the claim the old one was reaching for: an implemented operation that
     * declares a 503 declares a NAMED one. Measured rather than assumed - two runs with a defect
     * planted showed the parser's knowledge of a component changed no verdict at all while nothing
     * asserted this, which is how a line that looks like coverage buys none.
     */
    @Test
    @DisplayName("BA-032-T1 an implemented operation's 503 names the code it carries")
    void everyImplementedFiveOhThreeIsNamed() {
        List<String> unnamed = declared503().entrySet().stream()
                .filter(entry -> ImplementedOperationsRegistry.IMPLEMENTED.contains(entry.getKey()))
                .filter(entry -> !NAMED_503.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        assertThat(unnamed)
                .as("an implemented operation may not leave its 503 code unnamed")
                .isEmpty();
    }
}
