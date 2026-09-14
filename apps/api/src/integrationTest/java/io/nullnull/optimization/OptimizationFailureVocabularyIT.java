package io.nullnull.optimization;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.optimization.domain.OptimizationFailureCode;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
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
 * One vocabulary declared in three places, compared in both directions.
 *
 * <p>The run failure codes live in this enum, in {@code optimization_runs_failure_code_check} and in
 * the published contract. Three copies drift the way copies do, and the drift is invisible until a
 * run actually fails with the value one of them is missing - at which point the write is refused by
 * a constraint, or the response carries a code no client branches on.
 *
 * <p>{@code OptimizationFailureCode}'s javadoc claimed this test existed before it did. That is why
 * it is written now: a comment naming a device is not the device, and the gap sat there through two
 * slices without anything noticing.
 *
 * <p>APPLY_FAILED is the one deliberate difference. It belongs to a decision rather than a run, so
 * the contract publishes it and the run plane must not accept it - which this asserts rather than
 * subtracting quietly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-051 optimization failure vocabulary")
class OptimizationFailureVocabularyIT {

    /** Published for decisions, never for runs. */
    private static final String DECISION_ONLY = "APPLY_FAILED";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-051-T11 the enum, the column constraint and the contract publish one vocabulary")
    void threeDeclarationsAgree() {
        Set<String> enumValues = Arrays.stream(OptimizationFailureCode.values())
                .map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        Set<String> constraintValues = valuesInCheckConstraint();
        Set<String> contractValues = valuesInContract();

        assertThat(enumValues).as("the enum has values to compare").isNotEmpty();
        assertThat(constraintValues).as("the CHECK was found and parsed").isNotEmpty();
        assertThat(contractValues).as("the contract enum was found and parsed").isNotEmpty();

        // Both directions between the two places a run's failure is actually written.
        assertThat(enumValues).isEqualTo(constraintValues);

        // And against what clients are told, minus the one value that is a decision's to report. The
        // subtraction is asserted rather than assumed: if APPLY_FAILED ever became storable on a run,
        // this fails instead of quietly agreeing.
        assertThat(contractValues).contains(DECISION_ONLY);
        assertThat(enumValues).doesNotContain(DECISION_ONLY);
        Set<String> publishedForRuns = new TreeSet<>(contractValues);
        publishedForRuns.remove(DECISION_ONLY);
        assertThat(enumValues).isEqualTo(publishedForRuns);
    }

    /** What PostgreSQL actually enforces, read back from the live constraint rather than the file. */
    private Set<String> valuesInCheckConstraint() {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class,
                "optimization_runs_failure_code_check");
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> values = new TreeSet<>();
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    private static Set<String> valuesInContract() {
        Matcher block = Pattern.compile("(?s)OptimizationFailure:.*?code:.*?enum: \\[([^\\]]+)\\]")
                .matcher(contract());
        if (!block.find()) {
            throw new IllegalStateException("OptimizationFailure.code was not found in the contract");
        }
        return Arrays.stream(block.group(1).split(","))
                .map(String::trim).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String contract() {
        String property = System.getProperty("nullnull.openapi.path");
        Path file = property == null || property.isBlank()
                ? Path.of("../../docs/api/openapi.yaml") : Path.of(property);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the contract", missing);
        }
    }
}
