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
            "previewTripDraft", "SOURCE_UNAVAILABLE",
            // OptimizationFailsClosedIT asserts $.code on both: a run holding proposals while the
            // catalog is closed, read directly and through a replayed create.
            "getOptimization", "SOURCE_UNAVAILABLE",
            "createOptimization", "SOURCE_UNAVAILABLE",
            // CrowdForecastQueryFailsClosedIT asserts $.code with the catalog closed (BA-023-T13).
            "queryPlaceCrowdForecasts", "SOURCE_UNAVAILABLE"));

    private static final Pattern OPERATION = Pattern.compile("^\\s+operationId: (\\w+)\\s*$");

    private static final Pattern COMPONENT = Pattern.compile("#/components/responses/(\\w+)");

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

    /**
     * operationId -> the 503 response component it references, for every operation that declares a
     * 503 at all. A 503 with no component is recorded as {@code (inline)}.
     *
     * <p>Every 503 is recorded, whatever it points at. This used to record only the two NAMED
     * components, so an operation declaring any other 503 was simply absent from the map and the
     * implemented-operation case below could not fail for any input (#195, measured: pointing
     * getTrip's 503 at an unnamed component left both cases green).
     */
    private static Map<String, String> declared503() {
        Map<String, String> declarations = new LinkedHashMap<>();
        List<String> lines = contractLines();
        String operation = null;
        for (int index = 0; index < lines.size(); index++) {
            Matcher header = OPERATION.matcher(lines.get(index));
            if (header.matches()) {
                operation = header.group(1);
                continue;
            }
            // The response key itself, not an x-error-codes entry ("503": [CODE, ...]) - those name
            // codes and declare no response.
            if (!lines.get(index).trim().equals("\"503\":")) {
                continue;
            }
            Matcher component = COMPONENT.matcher(index + 1 < lines.size() ? lines.get(index + 1) : "");
            declarations.put(operation, component.find() ? component.group(1) : "(inline)");
        }
        return declarations;
    }

    @Test
    @DisplayName("BA-003-T9 an operation that sends a 503 names the code it sends")
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
     * BA-003-T10, rewritten when the unnamed 503 was deleted. It carried BA-032-T1 until #195, a
     * clause about feed paging that this never measured.
     *
     * <p>It used to assert that no IMPLEMENTED operation declared the generic {@code
     * ServiceUnavailable}. That component now has no referrer and has been removed, so the old
     * assertion could not fail for any input - the shape this suite exists to refuse.
     *
     * <p>What replaces it is the claim the old one was reaching for: an implemented operation that
     * declares a 503 declares a NAMED one. It still could not fail until #195, because the parser
     * only ever recorded named components - see declared503().
     */
    @Test
    @DisplayName("BA-003-T10 an implemented operation's 503 names the code it carries")
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
