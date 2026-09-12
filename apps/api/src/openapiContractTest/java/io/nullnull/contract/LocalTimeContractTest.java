package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PM-008 (#145): a wall-clock field must never carry a UTC offset.
 *
 * <p>The contract used to declare {@code startTime}, {@code endTime} and {@code suggestedTime} as
 * {@code format: time}, which is RFC 3339 full-time and therefore REQUIRES an offset - while
 * docs/architecture/ERD.md stores them in a PostgreSQL {@code time} column that cannot hold one and
 * docs/api/README.md calls them offset-less local time. The contract was the outlier, and the gap
 * was not cosmetic: {@code "09:30:00Z"} validated, the column dropped the offset, and a Seoul trip
 * silently gained nine hours.
 *
 * <p>Two things are pinned here. That no wall-clock field has gone back to {@code format: time},
 * and that no example value carries an offset. The second matters because an example is what
 * Frontend builds against, and one with an offset would teach the shape the schema now rejects.
 */
class LocalTimeContractTest {

    /** Fields the API README §12 defines as local wall-clock in the trip's timezone. */
    private static final Pattern WALL_CLOCK_FIELD =
            Pattern.compile("^\\s+(startTime|endTime|suggestedTime):\\s*$");
    private static final String EXPECTED_PATTERN =
            "pattern: \"^([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$\"";
    /** A time-of-day value carrying an offset or a Z, anywhere in the document. */
    private static final Pattern OFFSET_BEARING_TIME =
            Pattern.compile("\"\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})\"");

    private static List<String> contractLines() {
        String property = System.getProperty("nullnull.openapi.path");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("system property nullnull.openapi.path is required");
        }
        try {
            return Files.readAllLines(Path.of(property), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot read the contract", exception);
        }
    }

    @Test
    @DisplayName("BA-000-T3 no wall-clock field is declared with a format that requires an offset")
    void wallClockFieldsUseTheOffsetLessPattern() {
        List<String> lines = contractLines();
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int index = 0; index < lines.size(); index++) {
            Matcher field = WALL_CLOCK_FIELD.matcher(lines.get(index));
            if (!field.matches()) {
                continue;
            }
            // The property's own block: type, then the constraint, within the next few lines.
            String block = String.join("\n", lines.subList(index,
                    Math.min(lines.size(), index + 5)));
            if (!block.contains("format: date-time")) {
                checked += 1;
                if (block.contains("format: time") || !block.contains(EXPECTED_PATTERN)) {
                    wrong.add("line " + (index + 1) + " " + field.group(1));
                }
            }
        }
        // An empty sweep would make this vacuously true, which is the failure shape this repository
        // keeps finding.
        assertThat(checked).as("wall-clock fields found in the contract").isGreaterThanOrEqualTo(14);
        assertThat(wrong).as("wall-clock fields not pinned to the offset-less pattern").isEmpty();
    }

    @Test
    @DisplayName("BA-000-T3 no example teaches a wall-clock value with an offset")
    void examplesCarryNoOffsetOnAWallClockValue() {
        List<String> offenders = new ArrayList<>();
        List<String> lines = contractLines();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!OFFSET_BEARING_TIME.matcher(line).find()) {
                continue;
            }
            // date-time values legitimately carry one; a bare time-of-day never may.
            boolean partOfATimestamp = line.matches(".*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
            if (!partOfATimestamp) {
                offenders.add("line " + (index + 1) + ": " + line.trim());
            }
        }
        assertThat(offenders).as("wall-clock example values carrying an offset").isEmpty();
    }
}
