package io.nullnull.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.recommendation.domain.PolicyDescriptor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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
        assertThat(schemas.get("FeedRankRequest").get("properties").get("candidates").get("maxItems").asInt())
                .isEqualTo(FeedRankRequest.MAX_CANDIDATES);
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
        assertThat(schemas.get("ItemProposeRequest").get("properties").get("candidates").get("maxItems").asInt())
                .isEqualTo(ItemProposeRequest.MAX_CANDIDATES);
        assertThat(schemas.get("ItemProposeRequest").get("properties").get("locks").get("maxItems").asInt())
                .isEqualTo(ItemProposeRequest.MAX_LOCKS);
        assertThat(schemas.get("ItemProposeRequest").get("properties").get("neighbours").get("maxItems").asInt())
                .isEqualTo(ItemProposeRequest.MAX_NEIGHBOURS);
        assertThat(schemas.get("ItemProposeRequest").get("properties").get("openingHours").get("maxProperties")
                .asInt()).isEqualTo(ItemProposeRequest.MAX_OPENING_HOURS);
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
        assertThat(schemas.get("SlotEvaluateRequest").get("properties").get("items").get("maxItems").asInt())
                .isEqualTo(SlotEvaluateRequest.MAX_ITEMS);
        assertThat(schemas.get("SlotEvaluateRequest").get("properties").get("openingHours").get("maxProperties")
                .asInt()).isEqualTo(SlotEvaluateRequest.MAX_OPENING_HOURS);
        assertThat(schemas.get("SlotEvaluateRequest").get("properties").get("datesWithSamePlace").get("maxItems")
                .asInt()).isEqualTo(SlotEvaluateRequest.MAX_DATES_WITH_SAME_PLACE);
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
        assertThat(schemas.get("RelatedRankRequest").get("properties").get("candidates").get("maxItems").asInt())
                .isEqualTo(RelatedRankRequest.MAX_CANDIDATES);
        assertThat(schemas.get("RelatedRankRequest").get("properties").get("categories").get("maxItems").asInt())
                .isEqualTo(RelatedRankRequest.MAX_CATEGORIES);
        // A category match is 1, 0.5, 0 or null; it travels as a string so it arrives as an exact BigDecimal.
        assertThat(schemas.get("RelatedItemOut").get("properties").get("categoryMatch").get("anyOf").get(0).get("type")
                .asString()).isEqualTo("string");
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
