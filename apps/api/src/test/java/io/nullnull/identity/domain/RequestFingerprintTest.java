package io.nullnull.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BA-002: the canonical request hash of docs/api/README.md §5. */
@DisplayName("BA-002 canonical request fingerprint")
class RequestFingerprintTest {

    private static final String ROUTE = "POST /trips/{tripId}/candidates";

    @Test
    void hashIsSixtyFourLowercaseHexCharactersAndStable() {
        RequestFingerprint fingerprint =
                RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), "{\"placeId\":\"p-1\"}");
        assertThat(fingerprint.sha256Hex()).matches("[0-9a-f]{64}");
        assertThat(fingerprint.sha256Hex()).isEqualTo(fingerprint.sha256Hex());
    }

    /**
     * Golden vector, computed from the rules in docs/api/README.md §5 and the record's own javadoc,
     * not from a previous run: SCHEME, operation, the parameter block sorted by name, body, and the
     * two precondition components, each written as {@code <utf8 length>:<value>\n}.
     *
     * <p>Pinning it is what makes the sort observable. {@code Map.copyOf} keeps its own iteration
     * order stable inside one JVM, so comparing two fingerprints with the same key set can never see
     * a missing sort; across processes that order is re-randomised, and an unsorted canonical form
     * would hash the same retry differently on another pod.
     *
     * <p>{@code placeName} carries a three-character, nine-byte value on purpose, so the vector also
     * pins the length prefixes to UTF-8 BYTES: switching {@code appendComponent} to UTF-16 char
     * length changes {@code 9:경복궁} and the {@code 109:} block prefix, and this vector fails.
     */
    private static final String GOLDEN_CANONICAL_FORM =
            "22:idempotency-request-v1\n"
            + "35:POST /trips/{tripId}/items/{itemId}\n"
            + "109:11:candidateId\n"
            + "3:c-1\n"
            + "10:decisionId\n"
            + "3:d-1\n"
            + "6:itemId\n"
            + "3:i-1\n"
            + "9:placeName\n"
            + "9:경복궁\n"
            + "5:runId\n"
            + "3:r-1\n"
            + "6:tripId\n"
            + "3:t-1\n"
            + "\n"
            + "17:{\"placeId\":\"p-1\"}\n"
            + "1:0\n"
            + "0:\n";

    private static final String GOLDEN_DIGEST =
            "dc67fc753c3e7a2798538c0df968123ebf5c2226bb77fb48f0ad360a58ca0327";

    @Test
    void pathParametersAreCanonicalisedInNameOrderAgainstAGoldenVector() {
        Map<String, String> reverseOfNameOrder = new LinkedHashMap<>();
        reverseOfNameOrder.put("tripId", "t-1");
        reverseOfNameOrder.put("runId", "r-1");
        reverseOfNameOrder.put("placeName", "경복궁");
        reverseOfNameOrder.put("itemId", "i-1");
        reverseOfNameOrder.put("decisionId", "d-1");
        reverseOfNameOrder.put("candidateId", "c-1");

        RequestFingerprint fingerprint = RequestFingerprint.of("POST /trips/{tripId}/items/{itemId}",
                reverseOfNameOrder, "{\"placeId\":\"p-1\"}");

        assertThat(fingerprint.canonicalForm()).isEqualTo(GOLDEN_CANONICAL_FORM);
        assertThat(fingerprint.sha256Hex()).isEqualTo(GOLDEN_DIGEST);
    }

    /**
     * NOT a guard on the name-order sort, despite what the shape suggests: the record normalises
     * through {@code Map.copyOf}, and two maps with the same key set iterate identically inside one
     * JVM, so this stays green with the sort removed. What it does check is the property a caller
     * relies on directly - building the parameter map in either order yields one fingerprint, so a
     * retry assembled by different code takes the same reservation. The cross-process guarantee is
     * pinned by {@link #pathParametersAreCanonicalisedInNameOrderAgainstAGoldenVector()}, which fails
     * the moment the sort goes.
     */
    @Test
    void parameterMapsBuiltInDifferentOrdersAgreeWithinOneJvm() {
        Map<String, String> one = new LinkedHashMap<>();
        one.put("tripId", "t-1");
        one.put("itemId", "i-1");
        Map<String, String> other = new LinkedHashMap<>();
        other.put("itemId", "i-1");
        other.put("tripId", "t-1");
        assertThat(RequestFingerprint.of(ROUTE, one, "{}").sha256Hex())
                .isEqualTo(RequestFingerprint.of(ROUTE, other, "{}").sha256Hex());
    }

    @Test
    void aDifferentResolvedPathParameterIsADifferentRequest() {
        // Same route template and same body, another trip: the contract requires 409, not a replay.
        assertThat(RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), "{}").sha256Hex())
                .isNotEqualTo(RequestFingerprint.of(ROUTE, Map.of("tripId", "t-2"), "{}").sha256Hex());
    }

    @Test
    void operationBodyAndPreconditionEachChangeTheHash() {
        String base = RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), "{\"a\":1}").sha256Hex();
        assertThat(RequestFingerprint.of("DELETE /trips/{tripId}/candidates", Map.of("tripId", "t-1"),
                "{\"a\":1}").sha256Hex()).isNotEqualTo(base);
        assertThat(RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), "{\"a\":2}").sha256Hex())
                .isNotEqualTo(base);
        assertThat(new RequestFingerprint(ROUTE, Map.of("tripId", "t-1"), "{\"a\":1}", "\"3\"")
                .sha256Hex()).isNotEqualTo(base);
    }

    @Test
    void anAbsentPreconditionDiffersFromAnEmptyOne() {
        assertThat(new RequestFingerprint(ROUTE, Map.of(), "{}", null).sha256Hex())
                .isNotEqualTo(new RequestFingerprint(ROUTE, Map.of(), "{}", "").sha256Hex());
    }

    @Test
    void componentBoundariesCannotBeForgedFromContent() {
        // Without length prefixing these two would canonicalise to the same text.
        assertThat(RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1\n3:x"), "{}").sha256Hex())
                .isNotEqualTo(RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), "3:x\n{}").sha256Hex());
        assertThat(RequestFingerprint.of(ROUTE, Map.of("a", "1"), "{}").sha256Hex())
                .isNotEqualTo(RequestFingerprint.of(ROUTE, Map.of("a1", ""), "{}").sha256Hex());
    }

    @Test
    void canonicalFormIsLengthPrefixedAndVersioned() {
        String canonical = RequestFingerprint.of("GET /x", Map.of("id", "가"), "{}").canonicalForm();
        assertThat(canonical).startsWith("22:" + RequestFingerprint.SCHEME + "\n6:GET /x\n");
        // "가" is three bytes in UTF-8, so the prefix counts bytes and not UTF-16 units.
        assertThat(canonical).contains("2:id\n3:가\n");
    }

    @Test
    void bodyIsHashedAsSentWithoutJsonNormalisation() {
        assertThat(RequestFingerprint.of(ROUTE, Map.of(), "{\"a\":1,\"b\":2}").sha256Hex())
                .isNotEqualTo(RequestFingerprint.of(ROUTE, Map.of(), "{\"b\":2,\"a\":1}").sha256Hex());
    }

    @Test
    void rejectsMissingComponents() {
        assertThatThrownBy(() -> RequestFingerprint.of(" ", Map.of(), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestFingerprint.of(ROUTE, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestFingerprint.of(ROUTE, null, "{}"))
                .isInstanceOf(NullPointerException.class);
    }
}
