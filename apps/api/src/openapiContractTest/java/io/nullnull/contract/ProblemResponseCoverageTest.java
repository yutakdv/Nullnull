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
    private static final Pattern SECURITY_BLOCK = Pattern.compile("^ {6}security:\\s*$");
    private static final Pattern OPERATION_FIELD = Pattern.compile("^ {6}\\w+:.*$");
    private static final Pattern SECURITY_SCHEME = Pattern.compile("^\\s+-?\\s*(\\w+): \\[\\]\\s*$");

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

    /** operationId -> the security schemes it requires, in document order. */
    private static Map<String, List<String>> declaredSecurity() {
        Map<String, List<String>> declared = new LinkedHashMap<>();
        String operation = null;
        boolean inSecurity = false;
        for (String line : contractLines()) {
            Matcher header = OPERATION.matcher(line);
            if (header.matches()) {
                operation = header.group(1);
                declared.put(operation, new ArrayList<>());
                inSecurity = false;
                continue;
            }
            if (operation == null) {
                continue;
            }
            if (SECURITY_BLOCK.matcher(line).matches()) {
                inSecurity = true;
                continue;
            }
            if (OPERATION_FIELD.matcher(line).matches()) {
                inSecurity = false;
            }
            Matcher scheme = SECURITY_SCHEME.matcher(line);
            if (inSecurity && scheme.matches()) {
                declared.get(operation).add(scheme.group(1));
            }
        }
        return declared;
    }

    @Test
    @DisplayName("BA-003-T1 every implemented operation declares the statuses its own security makes reachable")
    void securityRequirementsAndDeclaredStatusesAgree() {
        // PM-019 answered: Frontend branches on `code`, never on `status` (PROBLEM_POLICY is keyed by
        // code and problem.ts only type-checks status as a number), so this is not about giving the
        // screen a number to switch on. It is about the generated client having a type at all for an
        // answer the server really produces. `default` alone is true but says nothing about WHICH
        // statuses are reachable, so the previous version of this case - 401 OR default - passed on
        // every operation in the contract without proving anything.
        //
        // The rule is derived, not curated: the session filter answers 401 wherever a session is
        // required, and the CSRF filter answers 403 (CSRF_INVALID) wherever a token is required.
        // SessionContractTest pins the contract's security against the @NullnullOperation annotations,
        // so reading security here is the same as reading the code. A resource owned by someone else
        // answers 404, so 403 has exactly one producer and no operation outside CSRF declares it.
        //
        // Both directions matter. Missing means the client has no type for an answer it will get;
        // extra means the contract advertises a failure nothing can produce, which is the shape this
        // repository refuses elsewhere (429 has no in-application producer and is not enumerated).
        Map<String, List<String>> responses = declaredResponses();
        Map<String, List<String>> security = declaredSecurity();
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        // Every operation in the contract, not only the implemented ones. The rule is derived from
        // the operation's own security, which is true whether or not a controller exists yet, and
        // checking only the implemented set would let each new slice land a route with no declared
        // failures and fix it afterwards.
        for (String operation : responses.keySet()) {
            List<String> declared = responses.get(operation);
            List<String> schemes = security.get(operation);
            assertThat(declared).as("%s is declared in the contract", operation).isNotNull();
            assertThat(schemes).as("%s security is parsed", operation).isNotNull();
            checked++;
            if (schemes.contains("sessionCookie") != declared.contains("401")) {
                wrong.add(operation + ": sessionCookie=" + schemes.contains("sessionCookie")
                        + " but 401 declared=" + declared.contains("401"));
            }
            if (schemes.contains("csrfToken") != declared.contains("403")) {
                wrong.add(operation + ": csrfToken=" + schemes.contains("csrfToken")
                        + " but 403 declared=" + declared.contains("403"));
            }
        }
        // Non-vacuous twice over: an empty registry, or a parser that found no security at all,
        // would otherwise let this pass while comparing nothing.
        assertThat(checked).isGreaterThan(40);
        assertThat(security.values().stream().filter(list -> !list.isEmpty()).count())
                .as("the security parser found requirements to compare")
                .isGreaterThan(20);
        assertThat(wrong).isEmpty();
    }
}
