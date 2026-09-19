package io.nullnull.social.infrastructure.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.social.application.CuratedPostImporter.ImportReport;
import io.nullnull.social.application.CuratedPostImporter.ImportReport.Entry;
import io.nullnull.social.application.CuratedPostImporter.ImportReport.Outcome;
import io.nullnull.social.application.CuratedPostPlan;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * The curated-posts half of scripts/aws/staging_operator.py OPS_LOG_LINE (mirrored, as CuratedHoursImportMainTest
     * mirrors the hours half). The Python test feeds the lines this class prints, verbatim, to the real pattern.
     */
    private static final Pattern OPS_LOG_LINE = Pattern.compile("^(curated_posts_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}"
            + "|curated_post [0-9a-f-]{36} (PUBLISHED \\([0-9]{1,3} place\\(s\\)\\)|ALREADY_PRESENT \\(left as it is\\))"
            + "|curated_posts_published=[0-9]{1,4} of [0-9]{1,4}"
            + "|curated_posts_failed reason=[A-Za-z_]{1,80})$");

    @TempDir Path directory;

    @Test
    @DisplayName("BA-032-T4 the plan comes from the argument, then the environment, and is required")
    void thePlanPathHasOneOrderAndNoDefault() throws Exception {
        Path fromArgument = Files.writeString(directory.resolve("from-argument.json"), "{\"from\":\"argument\"}");
        Path fromEnvironment = Files.writeString(directory.resolve("from-environment.json"), "{\"from\":\"env\"}");

        assertThat(read(Map.of(), fromArgument.toString()).json()).contains("argument");
        // The argument wins, so an operator running the task by hand is not overridden by whatever
        // the shell happens to export.
        assertThat(read(Map.of(CuratedPostImportMain.PLAN_PATH, fromEnvironment.toString()), fromArgument.toString())
                .json()).contains("argument");
        assertThat(read(Map.of(CuratedPostImportMain.PLAN_PATH, fromEnvironment.toString())).json()).contains("env");

        // No default. A curation run that guessed its own input could publish yesterday's plan
        // against today's database, and the operator would have no way to tell from the command.
        assertThatThrownBy(() -> read(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CuratedPostImportMain.PLAN_PATH);
        assertThatThrownBy(() -> read(Map.of(CuratedPostImportMain.PLAN_PATH, "  "), "  "))
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

    @Test
    void everyLineThePublicationPrintsPassesTheOperatorsAllowlist() throws Exception {
        UUID post = UUID.fromString("01a0b463-4600-7183-8000-000000000001");
        ImportReport report = new ImportReport(List.of(
                new Entry(post, Outcome.PUBLISHED, "2 place(s)"),
                new Entry(post, Outcome.ALREADY_PRESENT, "left as it is")));
        Path file = Files.writeString(directory.resolve("posts.json"), "{\"posts\":[]}");
        OperationsPlan.Text plan = read(Map.of(CuratedPostImportMain.PLAN_PATH, file.toString()));

        List<String> lines = new ArrayList<>();
        lines.add(CuratedPostImportMain.planLine(plan));
        lines.addAll(CuratedPostImportMain.summary(report).lines().toList());
        lines.add(CuratedPostImportMain.failureLine(new OperationsContext.Refused(
                OperationsContext.Refused.Code.OPERATIONS_TARGET_NOT_CONFIRMED, "target")));
        lines.add(CuratedPostImportMain.failureLine(new IllegalStateException("no curation plan at /tmp/x")));

        assertThat(lines).containsExactly(
                "curated_posts_plan sha256=" + plan.sha256() + " bytes=12",
                "curated_post 01a0b463-4600-7183-8000-000000000001 PUBLISHED (2 place(s))",
                "curated_post 01a0b463-4600-7183-8000-000000000001 ALREADY_PRESENT (left as it is)",
                "curated_posts_published=1 of 2",
                "curated_posts_failed reason=OPERATIONS_TARGET_NOT_CONFIRMED",
                "curated_posts_failed reason=IllegalStateException");
        assertThat(lines).allSatisfy(line -> assertThat(OPS_LOG_LINE.matcher(line).matches()).as(line).isTrue());
    }

    @Test
    void aRefusedPlanPrintsItsFailureLineAndStillFailsTheProcess() {
        // Neither source, then bytes that are not the approved ones: both stop before any database, print the one line
        // the operator's allowlist passes, and are rethrown so the task exits non-zero.
        for (Map<String, String> environment : List.of(Map.<String, String>of(),
                Map.of("NULLNULL_POSTS_PLAN_GZIP_BASE64", "H4sIAAAAAAAA/6uuBQBDv6ajAgAAAA==",
                        "NULLNULL_POSTS_PLAN_SHA256", "0".repeat(64)))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

            assertThatIllegalStateException().isThrownBy(() -> CuratedPostImportMain.run(environment, null, out));

            assertThat(bytes.toString(StandardCharsets.UTF_8).lines().toList())
                    .containsExactly("curated_posts_failed reason=IllegalStateException");
        }
    }

    @Test
    void thePlanShapeTheOwnerApprovesParsesWithTheScriptsOwnReader() throws Exception {
        // The shape of ops/curated-posts.json, including the "_source_file" note FE leaves on each post naming the
        // cover it came from. A reader that refused an unknown key would reject the approved plan in staging, after the
        // approval, where the only remedy is another approval.
        Path file = Files.writeString(directory.resolve("posts.json"), """
                {"posts":[{"id":"01a0b463-4600-7183-8000-000000000001","title":"담장을 따라 걷는 하루","body":"본문",
                  "publishedAt":"2026-09-18T03:00:00Z",
                  "cover":{"url":"https://d54awmnmi4c3z.cloudfront.net/covers/01-gyeongbokgung.jpg",
                           "alt":"근정전 앞 넓은 마당","checksum":"%s"},
                  "places":[{"placeId":"01a0b825-4f15-7e7b-b30c-87cf71861c9c","primary":true},
                            {"placeId":"01a0b9f7-8138-7051-bfbb-0a6420647c52","primary":false}],
                  "_source_file":"docs/contest/covers/01-gyeongbokgung.jpg"}]}
                """.formatted("7".repeat(64)));

        CuratedPostPlan plan = CuratedPostImportMain.read(file);

        assertThat(plan.posts()).singleElement().satisfies(post -> {
            assertThat(post.cover().url()).isEqualTo("https://d54awmnmi4c3z.cloudfront.net/covers/01-gyeongbokgung.jpg");
            assertThat(post.places()).hasSize(2);
        });
    }

    private static OperationsPlan.Text read(Map<String, String> environment, String... args) {
        return OperationsPlan.read(environment, args, CuratedPostImportMain.PLAN);
    }
}
