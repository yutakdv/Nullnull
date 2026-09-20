package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.social.domain.NotificationType;
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
 * One notification vocabulary declared in three places that cannot see each other.
 *
 * <p>{@code NotificationType}, {@code notifications_type_check} and the contract's
 * {@code Notification.type} enum. A value added to one and not the others compiles, passes every
 * suite that does not produce that exact value, and fails only when a notification of that kind is
 * first written - at which point the insert is refused by a constraint, or a client is handed a
 * type it has no branch for. {@code ProviderOutcomeVocabularyIT} records the same shape for the
 * provider validation vocabulary, and {@code OptimizationFailureVocabularyIT} for run failures.
 *
 * <p>There is no deliberate difference between the three here. If one appears - a type the contract
 * publishes that the server must never store, the way APPLY_FAILED is for runs - it belongs in this
 * file as an asserted subtraction rather than as a quietly relaxed comparison.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-085 notification type vocabulary")
class NotificationTypeVocabularyIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-085-T9 the enum, the column constraint and the contract publish one vocabulary")
    void threeDeclarationsAgree() {
        Set<String> enumValues = Arrays.stream(NotificationType.values())
                .map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        Set<String> constraintValues = valuesInCheckConstraint();
        Set<String> contractValues = valuesInContract();

        // Non-vacuity first: an empty set compares equal to an empty set, so a parse that found
        // nothing would otherwise report agreement.
        assertThat(enumValues).as("the enum has values to compare").hasSize(6);
        assertThat(constraintValues).as("the CHECK was found and parsed").isNotEmpty();
        assertThat(contractValues).as("the contract enum was found and parsed").isNotEmpty();

        assertThat(constraintValues).as("what the database will store").isEqualTo(enumValues);
        assertThat(contractValues).as("what clients are told").isEqualTo(enumValues);
        // Named explicitly so deleting a value, not only renaming one, is caught.
        assertThat(enumValues).contains("OPTIMIZATION_READY", "SOURCE_DEGRADED");
    }

    /** What PostgreSQL actually enforces, read from the live constraint rather than the file. */
    private Set<String> valuesInCheckConstraint() {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class,
                "notifications_type_check");
        assertThat(definition).as("notifications_type_check must exist").isNotNull();
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> values = new TreeSet<>();
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    /** The contract writes this enum as a block list, one value per line. */
    private static Set<String> valuesInContract() {
        Matcher block = Pattern.compile("(?s)\\n    Notification:\\n.*?\\n        type:\\n"
                        + "          type: string\\n          enum:\\n((?:            - [A-Z_]+\\n)+)")
                .matcher(contract());
        if (!block.find()) {
            throw new IllegalStateException("Notification.type was not found in the contract");
        }
        return Arrays.stream(block.group(1).split("\\n"))
                .map(line -> line.replace("-", " ").trim())
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
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
