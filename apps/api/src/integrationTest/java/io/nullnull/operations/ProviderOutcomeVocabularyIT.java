package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderResponseValidator;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.Arrays;
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
 * BA-020: one validation vocabulary, declared in three places that cannot see each other.
 *
 * <p>{@code CollectorRunRecorder} bridges the validator to the audit plane with
 * {@code ValidationResult.valueOf(verdict.outcome().name())}, and the row then has to satisfy a CHECK
 * constraint written in SQL. A value added to one of the three and not the others compiles, passes
 * every suite that does not produce that exact outcome, and throws only on the branch that does -
 * in the collector, against a real provider response. This compares the three as SETS, in both
 * directions, so the drift fails here instead.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-020 provider validation vocabulary")
class ProviderOutcomeVocabularyIT {

    /** PENDING is the audit plane's "not judged yet"; the validator has no such state to return. */
    private static final String AUDIT_ONLY = "PENDING";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-020-T1 the validator enum, the audit enum and the database CHECK carry the same values")
    void theThreeDeclarationsOfTheValidationVocabularyAgree() {
        Set<String> validator = Arrays.stream(ProviderResponseValidator.Outcome.values())
                .map(Enum::name).collect(Collectors.toSet());
        Set<String> audit = Arrays.stream(IngestAudit.ValidationResult.values())
                .map(Enum::name).collect(Collectors.toSet());
        Set<String> database = checkConstraintValues("api_ingest_validation_check");

        assertThat(audit).as("every validator outcome must survive valueOf in CollectorRunRecorder")
                .containsExactlyInAnyOrderElementsOf(union(validator, AUDIT_ONLY));
        assertThat(database).as("every audit value must be storable, and the CHECK must permit no more")
                .containsExactlyInAnyOrderElementsOf(audit);
        // Named explicitly so that deleting the value, not just renaming it, is also caught.
        assertThat(validator).contains("MAPPING_UNCERTAIN");
    }

    private Set<String> checkConstraintValues(String constraintName) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraintName);
        assertThat(definition).as("constraint %s must exist", constraintName).isNotNull();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> values = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("the constraint text must actually enumerate values").isNotEmpty();
        return values;
    }

    private static Set<String> union(Set<String> values, String extra) {
        Set<String> merged = new java.util.LinkedHashSet<>(values);
        merged.add(extra);
        return merged;
    }
}
