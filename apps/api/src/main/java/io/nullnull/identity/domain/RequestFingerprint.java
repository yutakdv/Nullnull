package io.nullnull.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical identity of a retryable command. docs/api/README.md §5 requires the request hash to cover
 * the operation, the actual path parameters, the semantic body and the command precondition, so that a
 * key replayed against a different resource or a different body is rejected instead of replayed.
 *
 * <p>Normalisation rules, all of them deliberate:
 * <ol>
 * <li>Every component is written as {@code <utf8 byte length>:<value>\n}. Length prefixing means no
 *     value can forge a component boundary: moving a path parameter into the body changes the hash.</li>
 * <li>{@link #SCHEME} is the first component, so changing this canonical form invalidates stored
 *     records instead of colliding with them.</li>
 * <li>Path parameters are sorted by name, and name and value are each written as their own component,
 *     so parameter order in the route never changes the hash.</li>
 * <li>The body is hashed exactly as the client sent it, decoded as UTF-8. Key order and whitespace are
 *     NOT normalised: the API contract requires a retry to send a byte-equivalent semantic body, and
 *     inventing a JSON canonical form would risk treating two different requests as one.</li>
 * <li>A command with no precondition and a command whose precondition is the empty string are
 *     distinguished by an explicit presence component.</li>
 * </ol>
 *
 * <p>The raw body is used to compute the hash and is never stored or logged.
 */
public record RequestFingerprint(String operation, Map<String, String> pathParameters, String body,
        String precondition) {

    /** Version tag of this canonical form; part of the hashed input. */
    public static final String SCHEME = "idempotency-request-v1";

    public RequestFingerprint {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(body, "body");
        if (operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        // Map.copyOf also rejects null names and values.
        pathParameters = Map.copyOf(Objects.requireNonNull(pathParameters, "pathParameters"));
    }

    /** Fingerprint of a command that has no precondition (no If-Match, no expected version). */
    public static RequestFingerprint of(String operation, Map<String, String> pathParameters, String body) {
        return new RequestFingerprint(operation, pathParameters, body, null);
    }

    /** The exact text that is hashed. Exposed so tests and debugging never have to guess it. */
    public String canonicalForm() {
        StringBuilder parameters = new StringBuilder();
        new TreeMap<>(pathParameters).forEach((name, value) -> {
            appendComponent(parameters, name);
            appendComponent(parameters, value);
        });
        StringBuilder canonical = new StringBuilder();
        appendComponent(canonical, SCHEME);
        appendComponent(canonical, operation);
        appendComponent(canonical, parameters.toString());
        appendComponent(canonical, body);
        appendComponent(canonical, precondition == null ? "0" : "1");
        appendComponent(canonical, precondition == null ? "" : precondition);
        return canonical.toString();
    }

    /** Lowercase hex SHA-256 of {@link #canonicalForm()}; 64 characters, as stored in request_hash. */
    public String sha256Hex() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalForm().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
        }
    }

    private static void appendComponent(StringBuilder out, String value) {
        out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value).append('\n');
    }
}
