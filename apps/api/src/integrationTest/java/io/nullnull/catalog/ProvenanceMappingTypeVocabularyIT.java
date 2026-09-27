package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DataProvenance.mappingType: every word a server path writes into it, and the list the contract publishes.
 *
 * <p>Three producers fill the field and none of them can see the others or the contract. A relation's
 * provenance carries its {@code derivation} (CatalogRelationProjectionService), which
 * {@code place_relations_derivation_check} bounds; a place shown with a Seoul area reading carries its
 * {@code mapping_type}, which {@code seoul_live_area_maps_type_check} bounds; and a KTO forecast snapshot is
 * written with the SQL literal {@code DIRECT} (JdbcKtoForecastSnapshotStore), whose column checks only that
 * it is not blank - so that one word is named here rather than read. Until #387's follow-up the contract
 * listed only DIRECT, AREA and AREA_FALLBACK while every related place went out as INTERNAL_RULE, and
 * nothing noticed: the list is {@code x-extensible-enum}, which no validator enforces, so this test is the
 * only thing that keeps the published words and the written ones together.
 *
 * <p>The name carries the work ID but no acceptance ID: BA-024's clauses are about relations themselves,
 * and an acceptance ID in a @DisplayName is counted as covered by check_test_reports.py on sight.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-024 provenance mappingType vocabulary")
class ProvenanceMappingTypeVocabularyIT {

    /** The forecast snapshot writer's literal; its column's CHECK does not enumerate words. */
    private static final String FORECAST_MAPPING = "DIRECT";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-024 the contract publishes exactly the words the server writes into mappingType")
    void theContractListsEveryWrittenMappingType() {
        Set<String> written = new LinkedHashSet<>();
        written.addAll(checkConstraintValues("place_relations_derivation_check"));
        written.addAll(checkConstraintValues("seoul_live_area_maps_type_check"));
        written.add(FORECAST_MAPPING);

        assertThat(publishedMappingTypes())
                .as("DataProvenance.mappingType must publish every word a server path writes, and no other")
                .containsExactlyInAnyOrderElementsOf(written);
    }

    private Set<String> checkConstraintValues(String constraintName) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, constraintName);
        assertThat(definition).as("constraint %s must exist", constraintName).isNotNull();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("constraint %s must actually enumerate values", constraintName).isNotEmpty();
        return values;
    }

    private static Set<String> publishedMappingTypes() {
        String property = System.getProperty("nullnull.openapi.path");
        Path file = property == null || property.isBlank()
                ? Path.of("../../docs/api/openapi.yaml") : Path.of(property);
        String contract;
        try {
            contract = Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the contract", missing);
        }
        int schema = contract.indexOf("\n    DataProvenance:\n");
        assertThat(schema).as("DataProvenance must be declared in the contract").isNotNegative();
        int mapping = contract.indexOf("\n        mappingType:\n", schema);
        assertThat(mapping).as("DataProvenance.mappingType must be declared").isNotNegative();
        // The property's own lines only: up to the next key at the same eight-space indent.
        Matcher sibling = Pattern.compile("\\n {8}\\S").matcher(contract);
        int end = sibling.find(mapping + 1) ? sibling.start() : contract.length();
        Matcher matcher = Pattern.compile("\\n {10}x-extensible-enum:\\s*\\[([A-Z_, ]+)]")
                .matcher(contract.substring(mapping, end));
        assertThat(matcher.find())
                .as("DataProvenance.mappingType must publish its words as x-extensible-enum")
                .isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
