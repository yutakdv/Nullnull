package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.CrowdForecastProjectionService.UnavailableReason;
import io.nullnull.crowd.domain.CrowdStage;
import io.nullnull.testsupport.OpenApiDocument;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two crowd fields Frontend renders from a token rather than show as text (#105): the contract's
 * declaration and the server's must be the same vocabulary, compared in both directions.
 *
 * <p>Neither is a JSON Schema {@code enum}, and that is measured, not chosen for taste: an enum added to
 * a response property that had none is reported by the gate's oasdiff as one ERROR per value per
 * operation that embeds the schema (174 of them). So {@code unavailableReason} lists its tokens in
 * {@code x-extensible-enum} - a list that may grow, which is what a reason list is - and
 * {@code ordinalLevel} carries the five-step scale as a pattern. Neither form gives the generated client
 * a union type, so this test is the only thing that keeps the server and the contract saying the same.
 */
@DisplayName("BA-023 crowd vocabulary: contract and server")
class CrowdVocabularyContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Strings around the scale, each accepted or refused by both sides for the same reason. Everything in
     * {@link CrowdStage#SCALE} is here, so an accepted set equal to the scale is not an empty agreement.
     */
    private static final List<String> PROBES = List.of("0", "1", "2", "3", "4", "5", "6", "9", "10", "05",
            " 3", "3 ", "", "보통", "HIGH", "4.0");

    @Test
    @DisplayName("BA-023-T21 the contract's unavailableReason tokens are exactly the reasons the server can send")
    void unavailableReasonTokensMatchTheServer() {
        JsonNode property = property("CrowdSeries", "unavailableReason");
        Set<String> published = new TreeSet<>();
        property.get("x-extensible-enum").forEach(token -> published.add(token.asText()));
        Set<String> sendable = Arrays.stream(UnavailableReason.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(published).as("the contract's list was found").isNotEmpty();
        // A reason in the enum and not the list reaches Frontend unannounced; one in the list and not the
        // enum is a token Frontend writes copy for and the server can never send.
        assertThat(published).isEqualTo(sendable);
        assertThat(property.has("enum"))
                .as("an enum here is 174 oasdiff errors and closes a list that has to grow")
                .isFalse();
    }

    @Test
    @DisplayName("BA-023-T22 the contract's ordinalLevel pattern accepts exactly the server's five-step scale")
    void ordinalLevelPatternMatchesTheServersScale() {
        Pattern published = Pattern.compile(property("CrowdMetric", "ordinalLevel").get("pattern").asText());
        Set<String> acceptedByContract = PROBES.stream().filter(probe -> published.matcher(probe).find())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> acceptedByServer = PROBES.stream().filter(CrowdStage::onScale)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(PROBES).as("every step of the scale is probed").containsAll(CrowdStage.SCALE);
        assertThat(acceptedByServer).containsExactlyInAnyOrderElementsOf(CrowdStage.SCALE);
        assertThat(acceptedByContract).isEqualTo(acceptedByServer);
    }

    /** schemaJson wraps the component in a $ref root, so the properties live under components.schemas. */
    private static JsonNode property(String schema, String name) {
        JsonNode node = JSON.readTree(OpenApiDocument.load().schemaJson(schema))
                .path("components").path("schemas").path(schema).path("properties").path(name);
        assertThat(node.isMissingNode()).as("%s.%s exists in the contract", schema, name).isFalse();
        return node;
    }
}
