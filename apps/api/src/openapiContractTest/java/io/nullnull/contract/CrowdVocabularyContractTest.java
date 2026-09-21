package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.CrowdForecastProjectionService.UnavailableReason;
import io.nullnull.crowd.domain.CrowdStage;
import io.nullnull.crowd.domain.SeoulCongestionStage;
import io.nullnull.testsupport.OpenApiDocument;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
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

    /**
     * Source codes around the reviewed set. Every member of {@link CrowdStage#SOURCES_WITH_REVIEWED_SCALE}
     * is here AND several registered sources that are not, so "served for exactly the reviewed ones" is
     * not satisfied by a descriptor that is always absent or always present.
     */
    private static final List<String> SOURCE_PROBES = List.of("SEOUL_CITYDATA", "KTO_KOR_SERVICE_2",
            "KTO_CONCENTRATION_FORECAST", "KTO_RELATED_PLACES", "DEMO_REPLAY", "NULLNULL_CATALOG_RULE",
            "NOT_A_SOURCE");

    @Test
    @DisplayName("BA-023-T24 a scale descriptor is served for exactly the sources with a reviewed mapping")
    void theDescriptorIsServedForExactlyTheReviewedSources() {
        Set<String> described = SOURCE_PROBES.stream().filter(code -> CrowdStage.scaleOf(code).isPresent())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(SOURCE_PROBES).as("every reviewed source is probed")
                .containsAll(CrowdStage.SOURCES_WITH_REVIEWED_SCALE);
        assertThat(SOURCE_PROBES).as("at least one unreviewed source is probed, or the check is vacuous")
                .anyMatch(code -> !CrowdStage.SOURCES_WITH_REVIEWED_SCALE.contains(code));
        // A described source with no reviewed mapping publishes a scale nobody approved; a reviewed one
        // with no descriptor leaves Frontend back at "Nth of five", which is what #97 is about.
        assertThat(described).isEqualTo(new TreeSet<>(CrowdStage.SOURCES_WITH_REVIEWED_SCALE));
    }

    @Test
    @DisplayName("BA-023-T25 each descriptor publishes exactly the cells that source's reviewed mapping assigns")
    void theDescriptorCellsAreTheReviewedMappingsCells() {
        assertThat(CrowdStage.SOURCES_WITH_REVIEWED_SCALE).as("there is a reviewed source to check").isNotEmpty();
        for (String code : CrowdStage.SOURCES_WITH_REVIEWED_SCALE) {
            Set<String> published = new TreeSet<>(CrowdStage.scaleOf(code).orElseThrow().publishedCells());
            Set<String> reviewed = new TreeSet<>(reviewedCellsOf(code));
            assertThat(published).as("%s publishes its reviewed cells and no others", code)
                    .isEqualTo(reviewed);
        }
    }

    @Test
    @DisplayName("BA-023-T26 the descriptor's size is the product scale's cell count, not the source's step count")
    void theDescriptorSizeIsTheProductScale() {
        for (String code : CrowdStage.SOURCES_WITH_REVIEWED_SCALE) {
            CrowdStage.Scale scale = CrowdStage.scaleOf(code).orElseThrow();
            assertThat(scale.size()).as("%s reports the scale's size", code).isEqualTo(CrowdStage.SCALE.size());
            // The two numbers are different for every source that fills part of the scale, and that
            // difference is the whole reason the descriptor exists - so they must not be read as one.
            assertThat(scale.publishedCells()).as("%s fills part of the scale", code)
                    .hasSizeLessThanOrEqualTo(scale.size());
        }
    }

    @Test
    @DisplayName("BA-023-T27 the contract's publishedCells vocabulary accepts exactly the server's scale")
    void publishedCellsVocabularyMatchesTheServersScale() {
        JsonNode cells = property("CrowdMetric", "ordinalScale").path("properties").path("publishedCells");
        assertThat(cells.isMissingNode()).as("ordinalScale.publishedCells exists in the contract").isFalse();
        Pattern published = Pattern.compile(cells.path("items").path("pattern").asText());
        Set<String> acceptedByContract = PROBES.stream().filter(probe -> published.matcher(probe).find())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(PROBES).as("every step of the scale is probed").containsAll(CrowdStage.SCALE);
        assertThat(acceptedByContract).isEqualTo(new TreeSet<>(CrowdStage.SCALE));
        // Same reasoning as ordinalLevel one property up, and now measured for a NEW property too: a
        // real enum here is 0 oasdiff errors today but turns a sixth cell into response-property-enum-
        // value-added, while widening this pattern is a warning.
        assertThat(cells.path("items").has("enum"))
                .as("an enum here would make growing the scale a breaking change").isFalse();
    }

    @Test
    @DisplayName("BA-023-T28 every approved SEOUL example carries the scale the server emits for SEOUL_CITYDATA")
    void approvedSeoulExamplesCarryTheServersScale() throws Exception {
        CrowdStage.Scale server = CrowdStage.scaleOf("SEOUL_CITYDATA").orElseThrow();
        Map<String, Object> expected = Map.of("size", server.size(), "publishedCells", server.publishedCells());
        List<String> found = new ArrayList<>();
        walkExamples(openApiRoot(), "$", false, (where, metric) -> {
            found.add(where);
            // A SEOUL example without the key is the drift #97 exists to prevent, not a pass.
            assertThat(metric).as("%s carries ordinalScale", where).containsKey("ordinalScale");
            assertThat(metric.get("ordinalScale")).as("%s shows the scale the server emits", where)
                    .isEqualTo(expected);
        });
        // Without this the loop passes on a contract with no SEOUL example at all - including one
        // where a rename or a moved example means this walk simply stopped finding them.
        assertThat(found).as("approved examples with a SEOUL_CITYDATA reading were found").isNotEmpty();
    }

    /**
     * Every CrowdMetric inside an example value whose provenance names SEOUL_CITYDATA. Only example
     * values qualify: in a schema definition {@code provenance} is a {@code $ref} with no source.
     */
    @SuppressWarnings("unchecked")
    private static void walkExamples(Object node, String where, boolean inExample,
            BiConsumer<String, Map<String, Object>> seoulMetric) {
        if (node instanceof Map<?, ?> map) {
            if (inExample && map.containsKey("ordinalLevel") && map.get("provenance") instanceof Map<?, ?> p
                    && "SEOUL_CITYDATA".equals(p.get("source"))) {
                seoulMetric.accept(where, (Map<String, Object>) map);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                walkExamples(entry.getValue(), where + "." + key, inExample || "examples".equals(key),
                        seoulMetric);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walkExamples(list.get(i), where + "[" + i + "]", inExample, seoulMetric);
            }
        }
    }

    /** The same file and parser OpenApiDocument uses, read raw because it has no example accessor. */
    private static Object openApiRoot() throws Exception {
        String path = System.getProperty("nullnull.openapi.path");
        assertThat(path).as("nullnull.openapi.path is set for this suite").isNotBlank();
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        return new Yaml(new SafeConstructor(options))
                .load(Files.readString(Path.of(path), StandardCharsets.UTF_8));
    }

    /** The cells a source's reviewed mapping assigns, read from the mapping itself and not relisted. */
    private static Collection<String> reviewedCellsOf(String sourceCode) {
        if ("SEOUL_CITYDATA".equals(sourceCode)) {
            return SeoulCongestionStage.mapping().values();
        }
        throw new AssertionError("no reviewed mapping wired into this test for " + sourceCode
                + " - add it here when the source is added to SOURCES_WITH_REVIEWED_SCALE");
    }

    /** schemaJson wraps the component in a $ref root, so the properties live under components.schemas. */
    private static JsonNode property(String schema, String name) {
        JsonNode node = JSON.readTree(OpenApiDocument.load().schemaJson(schema))
                .path("components").path("schemas").path(schema).path("properties").path(name);
        assertThat(node.isMissingNode()).as("%s.%s exists in the contract", schema, name).isFalse();
        return node;
    }
}
