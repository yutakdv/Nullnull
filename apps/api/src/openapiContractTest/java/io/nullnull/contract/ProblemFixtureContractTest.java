package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.problem.ProblemCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * packages/contracts/fixtures/problems/** is what Frontend's msw serves for every error screen, so
 * a wrong value there teaches the client a behaviour the server never produces. ajv already proves
 * each file satisfies the Problem schema; the schema cannot say which status or which retryable
 * belongs to which code, so that pairing went unchecked and drifted: APPLY_FAILED shipped
 * retryable true while docs/api/README.md and {@link ProblemCode} both say false.
 *
 * <p>This pins each fixture to the enum the server actually answers from. The fixture set is
 * one file per code, holding that code's DEFAULT face; codes that a specific operation answers
 * with another status (README's UI mapping table lists LOCK_CONFLICT 409/422 and APPLY_FAILED
 * 500/503) are not represented here, and changing a fixture to such a variant must be a decision,
 * not a drift — which is why the assertion is equality against the default rather than membership.
 */
class ProblemFixtureContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Path problemsDirectory() {
        String property = System.getProperty("nullnull.fixtures.path");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("system property nullnull.fixtures.path is required");
        }
        return Path.of(property).resolve("problems");
    }

    private static Map<String, JsonNode> fixtures() {
        Map<String, JsonNode> byCode = new TreeMap<>();
        Path directory = problemsDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> jsonFiles = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            // An empty directory would make every assertion below vacuously true.
            assertThat(jsonFiles).as("problem fixtures in %s", directory).isNotEmpty();
            for (Path file : jsonFiles) {
                JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                String code = node.path("code").asString();
                assertThat(byCode).as("two fixtures claim code %s", code).doesNotContainKey(code);
                byCode.put(code, node);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read problem fixtures from " + directory, exception);
        }
        return byCode;
    }

    @Test
    @DisplayName("BA-000-T3 every Problem code has exactly one fixture and no fixture invents a code")
    void coverage() {
        List<String> expected = Stream.of(ProblemCode.values()).map(Enum::name).sorted().toList();
        assertThat(fixtures().keySet()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("BA-000-T3 fixture type, title, status and retryable match the code the server answers with")
    void matchesServerDefaults() {
        Map<String, JsonNode> fixtures = fixtures();
        List<String> mismatches = new ArrayList<>();
        for (ProblemCode code : ProblemCode.values()) {
            JsonNode fixture = fixtures.get(code.name());
            if (fixture == null) {
                continue; // coverage() owns this failure and reports the whole set at once.
            }
            check(mismatches, code, "type", code.typeReference(), fixture.path("type").asString());
            check(mismatches, code, "title", code.title(), fixture.path("title").asString());
            check(mismatches, code, "status", Integer.toString(code.defaultStatus().value()),
                    Integer.toString(fixture.path("status").asInt()));
            check(mismatches, code, "retryable", Boolean.toString(code.defaultRetryable()),
                    Boolean.toString(fixture.path("retryable").asBoolean()));
        }
        assertThat(mismatches).as("fixture values the server never produces").isEmpty();
    }

    @Test
    @DisplayName("BA-000-T3 fixture detail is the English markup-free fallback the contract promises")
    void detailShape() {
        List<String> violations = new ArrayList<>();
        fixtures().forEach((code, fixture) -> {
            String detail = fixture.path("detail").asString();
            // docs/api/README.md: detail is a fallback, always English regardless of session locale,
            // and carries no markup because Frontend must never render it as HTML.
            if (detail.isBlank()) {
                violations.add(code + ".detail is blank");
            }
            if (detail.chars().anyMatch(character -> character > 127)) {
                violations.add(code + ".detail is not plain English text");
            }
            if (detail.contains("<") || detail.contains("&#")) {
                violations.add(code + ".detail carries markup");
            }
            // Problem.of leaves fieldErrors null and ProblemResponses only attaches a non-empty
            // list, so an empty array is a shape the server cannot emit.
            JsonNode fieldErrors = fixture.path("fieldErrors");
            if (fieldErrors.isArray() && fieldErrors.isEmpty()) {
                violations.add(code + ".fieldErrors is an empty array the server never sends");
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("BA-000-T3 exactly the three codes the contract calls retryable are retryable")
    void retryableSet() {
        List<String> retryable = fixtures().entrySet().stream()
                .filter(entry -> entry.getValue().path("retryable").asBoolean())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        // docs/api/README.md states this set outright: "현재 true인 code는 ROUTE_UNAVAILABLE,
        // SOURCE_UNAVAILABLE, RATE_LIMITED 셋뿐이다". Asserting the whole set, not each member,
        // is what makes a fourth one fail here.
        assertThat(retryable)
                .containsExactly("RATE_LIMITED", "ROUTE_UNAVAILABLE", "SOURCE_UNAVAILABLE");
    }

    @Test
    @DisplayName("BA-000-T3 the contract's claim about who produces 429 matches who actually does")
    void rateLimitedHasNoProducerInThisService() throws IOException {
        // The contract declares 429 on six operations and promises it everywhere else through
        // x-nullnull-common-contract.rateLimitErrors, and Frontend built a retry policy for it -
        // while nothing in apps/api has ever thrown RATE_LIMITED. That gap is a recorded decision
        // (DECISIONS_AND_RISKS.md D-033), and a decision is only worth recording if it cannot
        // silently stop being true. Implementing an in-app limiter must update that note; deleting
        // the note while no limiter exists must fail too.
        Path main = Path.of(System.getProperty("nullnull.api.source", "src/main/java"));
        List<String> producers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.endsWith("ProblemCode.java")) {
                    continue; // the enum declares the constant; declaring is not producing
                }
                if (Files.readString(file, StandardCharsets.UTF_8).contains("ProblemCode.RATE_LIMITED")) {
                    producers.add(main.relativize(file).toString());
                }
            }
        }
        // Whitespace-collapsed because the claim is a wrapped YAML scalar: re-flowing the line must
        // not be what decides whether the contract still says this.
        String contract = Files.readString(
                Path.of(System.getProperty("nullnull.openapi.path")), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
        boolean contractSaysNoProducer =
                contract.contains("No part of apps/api produces one today");

        if (producers.isEmpty()) {
            assertThat(contractSaysNoProducer)
                    .as("no apps/api code throws RATE_LIMITED, so rateLimitErrors must keep saying so")
                    .isTrue();
        } else {
            assertThat(contractSaysNoProducer)
                    .as("%s now throws RATE_LIMITED, so rateLimitErrors and D-033 are stale", producers)
                    .isFalse();
        }
    }

    private static void check(List<String> mismatches, ProblemCode code, String field,
            String expected, String actual) {
        if (!expected.equals(actual)) {
            mismatches.add("%s.%s: fixture %s, server %s".formatted(code.name(), field, actual, expected));
        }
    }
}
