package io.nullnull.social.infrastructure.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.CuratedPostImporter.ImportReport;
import io.nullnull.social.application.CuratedPostImporter.ImportReport.Entry;
import io.nullnull.social.application.CuratedPostImporter.ImportReport.Outcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-032-T4: the parts of the curation script an operator touches directly.
 *
 * <p>{@code CuratedPostImportIT} drives the importer, which is where the rules are. This covers what
 * sits in front of it - how the plan is named and what the run prints - because those are the two
 * things an operator interacts with and neither had a test. A script whose argument handling has
 * never been exercised is the same shape of problem as a guard that has never fired.
 */
@DisplayName("BA-032-T4 the curation script's entry point")
class CuratedPostImportMainTest {

    @Test
    @DisplayName("BA-032-T4 the plan comes from the argument, then the environment, and is required")
    void thePlanPathHasOneOrderAndNoDefault() {
        assertThat(CuratedPostImportMain.planPath(Map.of(), new String[] {"ops/from-argument.json"}))
                .hasToString("ops/from-argument.json");
        // The argument wins, so an operator running the task by hand is not overridden by whatever
        // the shell happens to export.
        assertThat(CuratedPostImportMain.planPath(
                Map.of(CuratedPostImportMain.PLAN_PATH, "ops/from-environment.json"),
                new String[] {"ops/from-argument.json"}))
                .hasToString("ops/from-argument.json");
        assertThat(CuratedPostImportMain.planPath(
                Map.of(CuratedPostImportMain.PLAN_PATH, "ops/from-environment.json"), new String[0]))
                .hasToString("ops/from-environment.json");

        // No default. A curation run that guessed its own input could publish yesterday's plan
        // against today's database, and the operator would have no way to tell from the command.
        assertThatThrownBy(() -> CuratedPostImportMain.planPath(Map.of(), new String[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CuratedPostImportMain.PLAN_PATH);
        assertThatThrownBy(() -> CuratedPostImportMain.planPath(
                Map.of(CuratedPostImportMain.PLAN_PATH, "  "), new String[] {"  "}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BA-032-T4 the printed report names ids and counts, never the editorial text")
    void theSummaryIsIdsAndCounts() {
        UUID published = UUID.fromString("019321b0-0000-7000-8000-000000000001");
        UUID skipped = UUID.fromString("019321b0-0000-7000-8000-000000000002");

        String summary = CuratedPostImportMain.summary(new ImportReport(List.of(
                new Entry(published, Outcome.PUBLISHED, "2 place(s)"),
                new Entry(skipped, Outcome.ALREADY_PRESENT, "left as it is"))));

        assertThat(summary)
                .contains(published.toString(), "PUBLISHED", "2 place(s)")
                .contains(skipped.toString(), "ALREADY_PRESENT")
                // The total is on its own line, because that is the number an operator reads back to
                // confirm the run did what the file asked.
                .contains("curated_posts_published=1 of 2");
    }
}
