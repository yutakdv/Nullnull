package io.nullnull.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderRequest;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderResponse;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.LockIn;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.recommendation.domain.related.PlaceCategoryIn;
import io.nullnull.recommendation.domain.related.RelatedItemOut;
import io.nullnull.recommendation.domain.related.RelatedRankRequest;
import io.nullnull.recommendation.domain.related.RelatedRankResponse;
import io.nullnull.recommendation.domain.related.RelationCandidateIn;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;
import io.nullnull.recommendation.domain.slot.SlotOut;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java records that carry the internal recommendation contract must name exactly the fields the
 * service declares (apps/ai/contracts/recommendation-internal-v1.json). A drift on either side fails here.
 */
@DisplayName("apps/ai internal contract v1 parity")
class InternalContractParityTest {

    /** The JSON Schema keywords that constrain a value's size or magnitude; the whole set, so a lower bound counts. */
    private static final Set<String> BOUND_KEYWORDS = Set.of("minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties");

    /**
     * Every bound the service declares, and the Java value that enforces the same limit. A bound the
     * service declares without an entry here fails {@link #everyDeclaredBoundHasAJavaCounterpart()}: an
     * unenforced bound is a request this API would send and the service would refuse with a 422, or a
     * response value the service never promised and this API would still persist.
     *
     * <p>Most entries are the record's own public constant. The literals are the bounds a canonical
     * constructor or the gateway checks inline, and each names where: {@code toleranceMinutes} in
     * {@code io.nullnull.trip.domain.ItemLock.Time}, {@code evidenceCount} and {@code summary} in
     * {@code HttpRecommendationGateway}, and the remaining {@code minimum}/{@code exclusiveMinimum}
     * values in the canonical constructor of the record that carries the field.
     *
     * <p>A {@code minLength: 1} on a required string is enforced as non-blank: every one of them is a
     * code, a zone id or a rendered sentence, so a whitespace-only value is the same hydration bug as
     * an empty one and is refused here rather than sent for the service to answer with a 422.
     */
    private static final Map<String, Integer> BOUNDS = Map.ofEntries(
            Map.entry("ExplanationRenderRequest.placeName.maxLength", ExplanationRenderRequest.MAX_PLACE_NAME),
            // ExplanationRenderRequest.requireBounded: an approved string is never blank.
            Map.entry("ExplanationRenderRequest.placeName.minLength", 1),
            Map.entry("ExplanationRenderRequest.metricLabel.minLength", 1),
            Map.entry("ExplanationRenderRequest.attribution.minLength", 1),
            Map.entry("ExplanationRenderRequest.metricLabel.maxLength", ExplanationRenderRequest.MAX_METRIC_LABEL),
            Map.entry("ExplanationRenderRequest.attribution.maxLength", ExplanationRenderRequest.MAX_ATTRIBUTION),
            Map.entry("ExplanationRenderRequest.forecastIssueId.maxLength",
                    ExplanationRenderRequest.MAX_FORECAST_ISSUE_ID),
            // Enforced by HttpRecommendationGateway on the one line the FE renders.
            Map.entry("ExplanationRenderResponse.summary.maxLength", ExplanationRenderResponse.MAX_SUMMARY_LENGTH),
            // HttpRecommendationGateway: an explanation is never an empty sentence.
            Map.entry("ExplanationRenderResponse.summary.minLength", 1),
            Map.entry("FeedRankRequest.candidates.maxItems", FeedRankRequest.MAX_CANDIDATES),
            Map.entry("FeedRankRequest.sortVersion.minimum", 1),
            Map.entry("ItemProposalOut.rank.minimum", 1),
            Map.entry("ItemProposeRequest.candidates.maxItems", ItemProposeRequest.MAX_CANDIDATES),
            Map.entry("ItemProposeRequest.locks.maxItems", ItemProposeRequest.MAX_LOCKS),
            Map.entry("ItemProposeRequest.neighbours.maxItems", ItemProposeRequest.MAX_NEIGHBOURS),
            Map.entry("ItemProposeRequest.openingHours.maxProperties", ItemProposeRequest.MAX_OPENING_HOURS),
            Map.entry("ItemProposeRequest.tripVersion.minimum", 1),
            Map.entry("ItemProposeRequest.tripZone.maxLength", ItemProposeRequest.MAX_TRIP_ZONE),
            Map.entry("ItemProposeRequest.tripZone.minLength", 1),
            Map.entry("LockIn.toleranceMinutes.minimum", 0),
            Map.entry("LockIn.toleranceMinutes.maximum", 180),
            Map.entry("NeighbourItemIn.durationMinutes.exclusiveMinimum", 0),
            Map.entry("PlaceCategoryIn.categoryCode.maxLength", PlaceCategoryIn.MAX_CODE),
            Map.entry("PlaceCategoryIn.parentCategoryCode.maxLength", PlaceCategoryIn.MAX_CODE),
            Map.entry("PlaceCategoryIn.taxonomyVersion.maxLength", PlaceCategoryIn.MAX_TAXONOMY_VERSION),
            Map.entry("PlaceCategoryIn.taxonomyVersion.minLength", 1),
            Map.entry("RelatedItemOut.evidenceCount.minimum", 1),
            Map.entry("RelatedRankRequest.candidates.maxItems", RelatedRankRequest.MAX_CANDIDATES),
            Map.entry("RelatedRankRequest.categories.maxItems", RelatedRankRequest.MAX_CATEGORIES),
            Map.entry("RelationCandidateIn.sourceCode.maxLength", RelationCandidateIn.MAX_SOURCE_CODE),
            Map.entry("RelationCandidateIn.channel.maxLength", RelationCandidateIn.MAX_CHANNEL),
            Map.entry("RelationCandidateIn.sourceCode.minLength", 1),
            Map.entry("RelationCandidateIn.channel.minLength", 1),
            Map.entry("SlotEvaluateRequest.items.maxItems", SlotEvaluateRequest.MAX_ITEMS),
            Map.entry("SlotEvaluateRequest.openingHours.maxProperties", SlotEvaluateRequest.MAX_OPENING_HOURS),
            Map.entry("SlotEvaluateRequest.datesWithSamePlace.maxItems",
                    SlotEvaluateRequest.MAX_DATES_WITH_SAME_PLACE),
            Map.entry("SlotEvaluateRequest.durationMinutes.exclusiveMinimum", 0),
            Map.entry("SlotEvaluateRequest.maxItemsPerDay.minimum", 1),
            Map.entry("SlotEvaluateRequest.tripZone.maxLength", SlotEvaluateRequest.MAX_TRIP_ZONE),
            Map.entry("SlotEvaluateRequest.tripZone.minLength", 1),
            Map.entry("TargetItemIn.durationMinutes.exclusiveMinimum", 0),
            Map.entry("TemporalCandidateIn.metricCode.maxLength", TemporalCandidateIn.MAX_METRIC_CODE),
            Map.entry("TemporalCandidateIn.metricCode.minLength", 1));

    static JsonNode schemas;

    @BeforeAll
    static void loadContract() throws IOException {
        String property = System.getProperty("nullnull.ai.contract.path");
        assertThat(property).as("system property nullnull.ai.contract.path").isNotBlank();
        schemas = JsonMapper.builder().build().readTree(Files.readString(Path.of(property))).get("components").get("schemas");
        assertThat(schemas).isNotNull();
    }

    @Test
    void feedRankRequestMatches() {
        assertParity(FeedRankRequest.class, "FeedRankRequest");
        assertParity(FeedCandidateIn.class, "FeedCandidateIn");
    }

    @Test
    void feedRankResponseAndPolicyDescriptorMatch() {
        assertParity(FeedRankResponse.class, "FeedRankResponse");
        assertParity(FeedRankResponse.StageCount.class, "StageCountOut");
        assertParity(PolicyDescriptor.class, "PolicyDescriptor");
    }

    @Test
    void itemProposeRequestMatches() {
        assertParity(ItemProposeRequest.class, "ItemProposeRequest");
        assertParity(TargetItemIn.class, "TargetItemIn");
        assertParity(LockIn.class, "LockIn");
        assertParity(NeighbourItemIn.class, "NeighbourItemIn");
        assertParity(OpeningWindowIn.class, "OpeningWindowIn");
        assertParity(TemporalCandidateIn.class, "TemporalCandidateIn");
    }

    @Test
    void itemProposeResponseMatches() {
        assertParity(ItemProposeResponse.class, "ItemProposeResponse");
        assertParity(ItemProposalOut.class, "ItemProposalOut");
    }

    @Test
    void slotEvaluateContractMatches() {
        assertParity(SlotEvaluateRequest.class, "SlotEvaluateRequest");
        assertParity(SlotEvaluateResponse.class, "SlotEvaluateResponse");
        assertParity(SlotOut.class, "SlotOut");
        // P0 answers a date and never a time: the service declares the field as null-typed, not as a time.
        assertThat(schemas.get("SlotOut").get("properties").get("suggestedTime").get("type").asString())
                .isEqualTo("null");
    }

    @Test
    void relatedRankContractMatches() {
        assertParity(RelatedRankRequest.class, "RelatedRankRequest");
        assertParity(PlaceCategoryIn.class, "PlaceCategoryIn");
        assertParity(RelationCandidateIn.class, "RelationCandidateIn");
        assertParity(RelatedRankResponse.class, "RelatedRankResponse");
        assertParity(RelatedItemOut.class, "RelatedItemOut");
        // A category match is 1, 0.5, 0 or null; it travels as a string so it arrives as an exact BigDecimal.
        assertThat(schemas.get("RelatedItemOut").get("properties").get("categoryMatch").get("anyOf").get(0).get("type")
                .asString()).isEqualTo("string");
    }

    @Test
    void explanationRenderContractMatches() {
        assertParity(ExplanationRenderRequest.class, "ExplanationRenderRequest");
        assertParity(ExplanationRenderResponse.class, "ExplanationRenderResponse");
    }

    /**
     * {@code locale} and {@code source} are Literals on the service side and Strings here, so the enum
     * check in {@link #assertParity} cannot see them: P0 ships Korean and English and nothing else, and
     * an explanation has exactly two writers. {@code source} is a String so that an unknown writer is
     * rejected by the gateway as a contract break instead of failing enum deserialization as an outage.
     */
    @Test
    void explanationLocalesAndWritersAreTheTwoPublishedValuesEach() {
        Set<String> locales = new TreeSet<>();
        schemas.get("ExplanationRenderRequest").get("properties").get("locale").get("enum")
                .forEach(node -> locales.add(node.asString()));
        assertThat(locales).isEqualTo(new TreeSet<>(Set.of("ko", "en")));
        Set<String> sources = new TreeSet<>();
        schemas.get("ExplanationRenderResponse").get("properties").get("source").get("enum")
                .forEach(node -> sources.add(node.asString()));
        assertThat(sources).isEqualTo(new TreeSet<>(ExplanationRenderResponse.SOURCES));
    }

    /**
     * The eleven comparison reason codes are a Literal on the service side and a String here, so the
     * enum check above cannot see them: pin the set explicitly against {@link ComparisonReasonCode}.
     */
    @Test
    void temporalCandidateReasonCodesAreTheElevenPublishedCodes() {
        Set<String> declared = new TreeSet<>();
        schemas.get("TemporalCandidateIn").get("properties").get("verdictReasonCode").get("enum")
                .forEach(node -> declared.add(node.asString()));
        assertThat(declared).isEqualTo(new TreeSet<>(Set.of(
                ComparisonReasonCode.SAME_METRIC_AND_ISSUE, ComparisonReasonCode.SAME_SOURCE_SCOPE_SET,
                ComparisonReasonCode.DIFFERENT_SOURCE, ComparisonReasonCode.DIFFERENT_SCOPE,
                ComparisonReasonCode.DIFFERENT_FORECAST_ISSUE, ComparisonReasonCode.STALE_INPUT,
                ComparisonReasonCode.REPLAY_INPUT, ComparisonReasonCode.QUALITATIVE_ONLY,
                ComparisonReasonCode.MAPPING_UNCERTAIN, ComparisonReasonCode.PROVIDER_INCIDENT,
                ComparisonReasonCode.MISSING_PROVENANCE)));
    }

    /**
     * Every bound in the contract, compared as a set: a bound the service added is a bound this API
     * does not enforce yet, and an entry that no longer matches a declaration is a stale exception.
     * Neither is skipped or warned about, because a bound only this side believes in is exactly how a
     * request that the service answers with a 422 gets built.
     */
    @Test
    void everyDeclaredBoundHasAJavaCounterpart() {
        Map<String, BigDecimal> declared = new TreeMap<>();
        schemas.properties().forEach(entry -> collectBounds(entry.getValue(), entry.getKey(), declared));
        Map<String, BigDecimal> enforced = new TreeMap<>();
        BOUNDS.forEach((key, value) -> enforced.put(key, BigDecimal.valueOf(value)));

        assertThat(declared.keySet()).as("contract bounds without a Java counterpart, and stale table entries")
                .containsExactlyInAnyOrderElementsOf(enforced.keySet());
        declared.forEach((key, value) -> assertThat(value).as(key).isEqualByComparingTo(enforced.get(key)));
    }

    /**
     * Collects every bound in a schema subtree, keyed {@code Schema.property.keyword}. The walk enters
     * {@code anyOf} branches, so an optional field that carries its bound in one branch is seen too.
     */
    private static void collectBounds(JsonNode node, String path, Map<String, BigDecimal> into) {
        if (node.isArray()) {
            node.forEach(child -> collectBounds(child, path, into));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.properties().forEach(entry -> {
            String keyword = entry.getKey();
            if (BOUND_KEYWORDS.contains(keyword)) {
                BigDecimal value = entry.getValue().decimalValue();
                BigDecimal previous = into.putIfAbsent(path + "." + keyword, value);
                if (previous != null) {
                    assertThat(previous).as("%s.%s is declared twice", path, keyword).isEqualByComparingTo(value);
                }
            } else if (keyword.equals("properties")) {
                entry.getValue().properties()
                        .forEach(property -> collectBounds(property.getValue(), path + "." + property.getKey(), into));
            } else {
                collectBounds(entry.getValue(), path, into);
            }
        });
    }

    /**
     * Field names must match exactly, every schema-required property must exist on the record, and every
     * enum-typed record component must carry exactly the schema's enum values (Literal drift fails here).
     */
    private static void assertParity(Class<? extends Record> type, String schemaName) {
        assertThat(fieldsOf(type)).as(schemaName + " fields").isEqualTo(propertiesOf(schemaName));
        JsonNode schema = schemas.get(schemaName);
        Set<String> required = new TreeSet<>();
        if (schema.has("required")) {
            schema.get("required").forEach(n -> required.add(n.asString()));
        }
        assertThat(fieldsOf(type)).as(schemaName + " required").containsAll(required);
        for (RecordComponent component : type.getRecordComponents()) {
            if (!component.getType().isEnum()) {
                continue;
            }
            JsonNode property = schema.get("properties").get(component.getName());
            JsonNode enumNode = property.has("enum") ? property.get("enum") : resolveRef(property).get("enum");
            assertThat(enumNode).as(schemaName + "." + component.getName() + " enum").isNotNull();
            Set<String> declared = new TreeSet<>();
            enumNode.forEach(n -> declared.add(n.asString()));
            Set<String> javaValues = new TreeSet<>();
            for (Object constant : component.getType().getEnumConstants()) {
                javaValues.add(((Enum<?>) constant).name());
            }
            assertThat(declared).as(schemaName + "." + component.getName()).isEqualTo(javaValues);
        }
    }

    private static JsonNode resolveRef(JsonNode property) {
        JsonNode ref = property.get("$ref");
        if (ref == null && property.has("anyOf")) {
            for (JsonNode option : property.get("anyOf")) {
                if (option.has("$ref")) {
                    ref = option.get("$ref");
                }
            }
        }
        assertThat(ref).as("enum property must inline or $ref a schema").isNotNull();
        String name = ref.asString().substring(ref.asString().lastIndexOf('/') + 1);
        return schemas.get(name);
    }

    @Test
    void everyContractSchemaRejectsUnknownFields() {
        schemas.properties().forEach(entry -> {
            String name = entry.getKey();
            if (name.equals("HTTPValidationError") || name.equals("ValidationError")) {
                return;
            }
            JsonNode schema = entry.getValue();
            if (schema.has("type") && schema.get("type").asString().equals("object")) {
                assertThat(schema.get("additionalProperties").asBoolean()).as(name).isFalse();
            }
        });
    }

    private static Set<String> fieldsOf(Class<? extends Record> type) {
        Set<String> names = new TreeSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static Set<String> propertiesOf(String schemaName) {
        JsonNode schema = schemas.get(schemaName);
        assertThat(schema).as("schema %s", schemaName).isNotNull();
        Set<String> names = new TreeSet<>();
        schema.get("properties").properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
