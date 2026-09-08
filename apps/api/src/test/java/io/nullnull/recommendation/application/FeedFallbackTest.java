package io.nullnull.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Order parity with apps/ai: both sides read the same fixture
 * (apps/ai/tests/recommendation/fixtures/feed-order-cases.json, located by the system property
 * {@code nullnull.ai.fixtures.path}) and must produce the same order for every case.
 */
@DisplayName("§5.1 feed fallback order")
class FeedFallbackTest {

    static JsonNode cases;

    @BeforeAll
    static void loadFixture() throws IOException {
        String directory = System.getProperty("nullnull.ai.fixtures.path");
        assertThat(directory).as("system property nullnull.ai.fixtures.path").isNotBlank();
        JsonNode fixture = JsonMapper.builder().build()
                .readTree(Files.readString(Path.of(directory, "feed-order-cases.json")));
        cases = fixture.get("cases");
        assertThat(cases).as("cases").isNotNull();
        assertThat(cases.size()).as("case count").isGreaterThanOrEqualTo(3);
    }

    @Test
    void ordersEveryFixtureCaseLikeTheService() {
        for (JsonNode fixtureCase : cases) {
            assertThat(FeedFallback.order(candidatesOf(fixtureCase), evaluatedAtOf(fixtureCase),
                    fixtureCase.get("cap").asInt()))
                    .as(fixtureCase.get("id").asString())
                    .isEqualTo(expectedOrderOf(fixtureCase));
        }
    }

    @Test
    void tieBreakIsTheCanonicalStringAndNotSignedUuidOrder() {
        // Guards the fixture: at least one case must separate the two orders, otherwise the parity
        // assertion above would still pass with UUID.compareTo (signed) and the check is vacuous.
        List<String> divergent = new ArrayList<>();
        for (JsonNode fixtureCase : cases) {
            List<UUID> signedOrder = candidatesOf(fixtureCase).stream()
                    .filter(candidate -> candidate.status() == FeedCandidateIn.PostStatus.PUBLISHED
                            && candidate.publishedAt() != null
                            && !candidate.publishedAt().isAfter(evaluatedAtOf(fixtureCase))
                            && candidate.primaryPlaceId() != null)
                    .sorted(Comparator.comparing(FeedCandidateIn::publishedAt).reversed()
                            .thenComparing(FeedCandidateIn::postId))
                    .limit(fixtureCase.get("cap").asInt())
                    .map(FeedCandidateIn::postId)
                    .toList();
            if (!signedOrder.equals(expectedOrderOf(fixtureCase))) {
                divergent.add(fixtureCase.get("id").asString());
            }
        }
        assertThat(divergent).as("cases where signed UUID order differs from the expected order").isNotEmpty();
    }

    private static Instant evaluatedAtOf(JsonNode fixtureCase) {
        return Instant.parse(fixtureCase.get("evaluatedAt").asString());
    }

    private static List<FeedCandidateIn> candidatesOf(JsonNode fixtureCase) {
        List<FeedCandidateIn> candidates = new ArrayList<>();
        for (JsonNode candidate : fixtureCase.get("candidates")) {
            candidates.add(new FeedCandidateIn(
                    UUID.fromString(candidate.get("postId").asString()),
                    optionalInstant(candidate.get("publishedAt")),
                    FeedCandidateIn.PostStatus.valueOf(candidate.get("status").asString()),
                    optionalUuid(candidate.get("primaryPlaceId"))));
        }
        return candidates;
    }

    private static List<UUID> expectedOrderOf(JsonNode fixtureCase) {
        List<UUID> expected = new ArrayList<>();
        for (JsonNode postId : fixtureCase.get("expectedOrder")) {
            expected.add(UUID.fromString(postId.asString()));
        }
        return expected;
    }

    private static Instant optionalInstant(JsonNode node) {
        return node == null || node.isNull() ? null : Instant.parse(node.asString());
    }

    private static UUID optionalUuid(JsonNode node) {
        return node == null || node.isNull() ? null : UUID.fromString(node.asString());
    }
}
