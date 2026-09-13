package io.nullnull.trip;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.trip.domain.CandidateSourceType;
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
 * BA-040: where a candidate came from is declared in three places that cannot see each other.
 *
 * <p>{@link CandidateSourceType} parses it, {@code candidate_sources_type_check} stores it, and the
 * contract publishes it. Adding a word to one of them alone compiles and passes every suite that does
 * not write that exact value - the failure arrives later, on the one branch that produces it. That is
 * the same shape {@code ProviderOutcomeVocabularyIT} exists for, and this is the trip module's copy
 * of that comparison.
 *
 * <p>The contract publishes the list as {@code x-extensible-enum} rather than {@code enum}, for the
 * reason {@code x-nullnull-interest-codes} does: a closed response enum turns every new word into a
 * breaking change - adding TRIP_SEED as an enum value reports fifteen
 * {@code response-property-enum-value-added} findings - and this vocabulary is expected to grow,
 * since LIVE belongs to a P1 tab that does not exist yet. That choice costs the generated client its
 * union type, so nothing but this test keeps the published list and the enforced one together.
 *
 * <p>The name carries the work ID but no acceptance ID. BA-040-T1 is reorder atomicity and
 * BA-040-T2 is the candidate transition landing with the item; neither is what this proves, and
 * an acceptance ID in a @DisplayName is counted as covered by check_test_reports.py on sight.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-040 candidate source vocabulary")
class CandidateSourceVocabularyIT {

    private static final Pattern PUBLISHED = Pattern.compile(
            "^\\s+x-extensible-enum:\\s*\\[([A-Z_, ]+)]\\s*$", Pattern.MULTILINE);

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-040 the enum, the database CHECK and the published list carry the same words")
    void theThreeDeclarationsOfTheCandidateSourceVocabularyAgree() {
        Set<String> parsed = Arrays.stream(CandidateSourceType.values())
                .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> stored = checkConstraintValues("candidate_sources_type_check");
        Set<String> published = publishedCandidateSourceTypes();

        assertThat(stored).as("a value the enum parses must be storable, and the CHECK must permit no more")
                .containsExactlyInAnyOrderElementsOf(parsed);
        assertThat(published).as("the contract must publish exactly what the server accepts")
                .containsExactlyInAnyOrderElementsOf(parsed);
        // Named so that deleting the value, not only renaming it, is caught: an item that arrived
        // through seedItems has no other true answer, and SEARCH would be an invented provenance.
        assertThat(parsed).contains("TRIP_SEED");
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
        assertThat(values).as("the constraint text must actually enumerate values").isNotEmpty();
        return values;
    }

    private Set<String> publishedCandidateSourceTypes() {
        String property = System.getProperty("nullnull.openapi.path");
        Path file = property == null || property.isBlank()
                ? Path.of("../../docs/api/openapi.yaml") : Path.of(property);
        String contract;
        try {
            contract = Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the contract", missing);
        }
        int candidateSource = contract.indexOf("\n    CandidateSource:\n");
        assertThat(candidateSource).as("CandidateSource must be declared in the contract").isNotNegative();
        Matcher matcher = PUBLISHED.matcher(contract.substring(candidateSource));
        assertThat(matcher.find())
                .as("CandidateSource.type must publish its words as x-extensible-enum")
                .isTrue();
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
