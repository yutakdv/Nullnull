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
 * PM-019: prose about errors does not give a generated client a shape to branch on.
 *
 * <p>{@code x-nullnull-common-contract} says every protected route can answer 401 and every route
 * carries {@code X-Request-ID}. That is true and it is written down, but an operation whose
 * contract lists only {@code 200} and {@code 404} produces a client with no type for anything else:
 * {@code getTrip} needs a session, so 401 is reachable, and Frontend had nothing to branch on.
 *
 * <p>The claim being pinned is narrow and checkable: {@link
 * io.nullnull.shared.problem.GlobalExceptionHandler} is a {@code @RestControllerAdvice}, so it
 * applies to EVERY route. There is therefore no implemented operation that cannot answer with a
 * Problem, and {@code default} is simply true for all of them. Listing more precise statuses
 * per operation is a separate, larger change that Frontend should review; declaring the catch-all
 * is not, because it only adds a type where there was none.
 */
class ProblemResponseCoverageTest {

    private static final Pattern OPERATION = Pattern.compile("^\\s+operationId: (\\w+)\\s*$");
    private static final Pattern STATUS = Pattern.compile("^\\s+\"(\\d{3})\":\\s*$");
    private static final Pattern DEFAULT = Pattern.compile("^\\s+default:\\s*$");

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

    /** operationId -> the response keys it declares, in document order. */
    private static Map<String, List<String>> declaredResponses() {
        Map<String, List<String>> declared = new LinkedHashMap<>();
        String operation = null;
        for (String line : contractLines()) {
            Matcher header = OPERATION.matcher(line);
            if (header.matches()) {
                operation = header.group(1);
                declared.put(operation, new ArrayList<>());
                continue;
            }
            if (operation == null) {
                continue;
            }
            Matcher status = STATUS.matcher(line);
            if (status.matches()) {
                declared.get(operation).add(status.group(1));
            } else if (DEFAULT.matcher(line).matches()) {
                declared.get(operation).add("default");
            }
        }
        return declared;
    }

    @Test
    @DisplayName("BA-003-T1 every implemented operation declares the catch-all Problem response")
    void everyImplementedOperationDeclaresDefault() {
        Map<String, List<String>> declared = declaredResponses();
        assertThat(declared).as("the parser found operations at all").isNotEmpty();

        List<String> checked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String operation : ImplementedOperationsRegistry.IMPLEMENTED) {
            List<String> responses = declared.get(operation);
            assertThat(responses).as("%s is declared in the contract", operation).isNotNull();
            checked.add(operation);
            if (!responses.contains("default")) {
                missing.add(operation);
            }
        }
        // Non-vacuous: an empty registry would make the loop below pass without comparing anything.
        assertThat(checked).hasSizeGreaterThan(20);
        assertThat(missing)
                .as("GlobalExceptionHandler is a @RestControllerAdvice, so any route can answer "
                        + "with a Problem; an operation without `default` gives the generated "
                        + "client no type for that answer")
                .isEmpty();
    }

    @Test
    @DisplayName("BA-003-T1 a session-protected operation can answer 401, so it may not stop at 404")
    void sessionProtectedOperationsCoverTheUnauthorisedAnswer() {
        Map<String, List<String>> declared = declaredResponses();
        // The narrow version of PM-019's complaint, kept as its own case because it is the one
        // Frontend actually hit: a read that needs a cookie, declaring only success and not-found.
        for (String operation : List.of("getTrip", "getPlace", "getPost", "listTrips")) {
            List<String> responses = declared.get(operation);
            assertThat(responses).as("%s", operation).isNotNull();
            assertThat(responses.contains("401") || responses.contains("default"))
                    .as("%s needs a session, so 401 is reachable and must have a declared shape",
                            operation)
                    .isTrue();
        }
    }
}
