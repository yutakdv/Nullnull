package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.trip.domain.InterestVocabulary;
import io.nullnull.trip.domain.TripInterest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FCR-020 (#154): the interest vocabulary the server enforces is the one the contract publishes.
 *
 * <p>The codes are declared as {@code x-nullnull-interest-codes} rather than an {@code enum}. Their
 * canon is the Figma chip list at {@code 438:3108}, which Frontend owns and expects to grow; a
 * closed enum would turn each new chip into a breaking response change (oasdiff reports 156
 * {@code response-property-enum-value-added} findings for the thirteen alone). Frontend asked for
 * server-side rejection, not for the type to close.
 *
 * <p>That choice costs the generated client its union type, so nothing but this test keeps the two
 * lists together. Without it the extension is decoration: the server could drop a code, or grow one
 * the screen cannot draw, and every other check would stay green.
 */
class InterestVocabularyContractTest {

    private static final Pattern GROUP_ROW =
            Pattern.compile("^\\s+(who|style):\\s*\\[([A-Z_, ]+)]\\s*$");

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

    /** The codes the extension publishes, in document order, duplicates preserved. */
    private static List<String> publishedCodes() {
        List<String> lines = contractLines();
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).trim().equals("x-nullnull-interest-codes:")) {
                assertThat(start).as("the extension is declared exactly once").isEqualTo(-1);
                start = index;
            }
        }
        assertThat(start).as("x-nullnull-interest-codes is declared").isNotEqualTo(-1);
        List<String> codes = new ArrayList<>();
        for (int index = start + 1; index < lines.size(); index++) {
            Matcher row = GROUP_ROW.matcher(lines.get(index));
            if (row.matches()) {
                for (String code : row.group(2).split(",")) {
                    codes.add(code.trim());
                }
                continue;
            }
            // The extension block ends at the next sibling key.
            if (!lines.get(index).isBlank() && !lines.get(index).startsWith("            ")) {
                break;
            }
        }
        return codes;
    }

    @Test
    @DisplayName("BA-030-T1 the published codes are exactly the ones the server accepts")
    void thePublishedVocabularyIsTheEnforcedOne() {
        List<String> published = publishedCodes();
        // Fails loudly if the block is ever parsed as empty: an empty expected set would make
        // every assertion below pass without comparing anything.
        assertThat(published).hasSize(13);
        assertThat(new LinkedHashSet<>(published))
                .as("a code is published once")
                .hasSize(published.size());
        assertThat(Set.copyOf(published))
                .as("contract and InterestVocabulary must name the same codes")
                .isEqualTo(InterestVocabulary.codes());

        // Every published code must actually construct. Publishing one the domain refuses would
        // give Frontend a chip that always 422s.
        for (String code : published) {
            assertThat(new TripInterest(code, InterestVocabulary.NEUTRAL_WEIGHT).code())
                    .isEqualTo(code);
        }
    }

    @Test
    @DisplayName("BA-030-T1 the contract does not close the type it cannot own")
    void theCodeStaysAnOpenStringWithTheRejectionSpelledOut() {
        List<String> lines = contractLines();
        int schema = lines.indexOf("    TripInterest:");
        assertThat(schema).as("TripInterest schema is present").isNotEqualTo(-1);
        String block = String.join("\n", lines.subList(schema,
                Math.min(lines.size(), schema + 40)));
        // An enum here is not wrong by taste - it is a promise about a list Frontend owns, and it
        // would have to travel through the breaking-change registry every time a chip is added.
        assertThat(block).doesNotContain("enum:");
        // An open type is only honest if the contract says what happens to the codes it excludes.
        assertThat(block).contains("VALIDATION_FAILED");
        assertThat(block).contains("Unsupported");
    }
}
