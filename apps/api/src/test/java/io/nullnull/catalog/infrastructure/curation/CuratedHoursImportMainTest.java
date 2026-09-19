package io.nullnull.catalog.infrastructure.curation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.catalog.application.CuratedHoursImporter.ImportReport;
import io.nullnull.catalog.application.CuratedHoursPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the hours import prints is what the staging operator lets through, for success and for failure. */
class CuratedHoursImportMainTest {

    /**
     * The curated-hours half of scripts/aws/staging_operator.py OPS_LOG_LINE (mirrored, as KtoDemoRefreshIT mirrors
     * the KTO half). The Python test feeds the lines this class prints, verbatim, to the real pattern.
     */
    private static final Pattern OPS_LOG_LINE = Pattern.compile("^(curated_hours_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}"
            + "|curated_hours [0-9a-f-]{36} (RECORDED|REPLACED) \\(windows=[0-9]{1,4}\\)"
            + "|curated_hours_recorded=[0-9]{1,4}"
            + "|curated_hours_failed reason=[A-Za-z_]{1,80})$");

    @TempDir Path directory;

    @Test
    void everyLineTheImportPrintsPassesTheOperatorsAllowlist() throws Exception {
        UUID place = UUID.fromString("01a0b825-4f15-7e7b-b30c-87cf71861c9c");
        ImportReport report = new ImportReport(List.of(
                new ImportReport.Entry(place, ImportReport.Outcome.RECORDED, 48),
                new ImportReport.Entry(place, ImportReport.Outcome.REPLACED, 48)));
        Path file = directory.resolve("hours.json");
        Files.writeString(file, "{\"places\":[]}");
        OperationsPlan.Text plan = OperationsPlan.read(java.util.Map.of(CuratedHoursImportMain.PLAN_PATH, file.toString()),
                null, CuratedHoursImportMain.PLAN);

        List<String> lines = new java.util.ArrayList<>();
        lines.add(CuratedHoursImportMain.planLine(plan));
        lines.addAll(CuratedHoursImportMain.summary(report).lines().toList());
        lines.add(CuratedHoursImportMain.failureLine(new OperationsContext.Refused(
                OperationsContext.Refused.Code.OPERATIONS_TARGET_NOT_CONFIRMED, "target")));
        lines.add(CuratedHoursImportMain.failureLine(new IllegalStateException("no place at /tmp/x")));

        assertThat(lines).containsExactly(
                "curated_hours_plan sha256=" + plan.sha256() + " bytes=13",
                "curated_hours 01a0b825-4f15-7e7b-b30c-87cf71861c9c RECORDED (windows=48)",
                "curated_hours 01a0b825-4f15-7e7b-b30c-87cf71861c9c REPLACED (windows=48)",
                "curated_hours_recorded=2",
                "curated_hours_failed reason=OPERATIONS_TARGET_NOT_CONFIRMED",
                "curated_hours_failed reason=IllegalStateException");
        assertThat(lines).allSatisfy(line -> assertThat(OPS_LOG_LINE.matcher(line).matches()).as(line).isTrue());
    }

    @Test
    void aRefusedPlanPrintsItsFailureLineAndStillFailsTheProcess() throws Exception {
        // Neither source, then bytes that are not the approved ones: both stop before any database, print the one line
        // the operator's allowlist passes, and are rethrown so the task exits non-zero.
        for (java.util.Map<String, String> environment : List.of(java.util.Map.<String, String>of(),
                java.util.Map.of("NULLNULL_HOURS_PLAN_GZIP_BASE64", "H4sIAAAAAAAA/6uuBQBDv6ajAgAAAA==",
                        "NULLNULL_HOURS_PLAN_SHA256", "0".repeat(64)))) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.PrintStream out = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);

            assertThatIllegalStateException().isThrownBy(() -> CuratedHoursImportMain.run(environment, null, out));

            assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList())
                    .containsExactly("curated_hours_failed reason=IllegalStateException");
        }
    }

    @Test
    void theSuitesReaderParsesAPlanFileAsTheStagingPathWould() throws Exception {
        Path file = directory.resolve("hours.json");
        Files.writeString(file, """
                {"places":[{"placeId":"01a0b825-4f15-7e7b-b30c-87cf71861c9c",
                  "evidenceUrl":"https://royal.khs.go.kr/ROYAL/contents/R702000000.do",
                  "observedAt":"2026-09-13T18:40:00Z","outcome":"OBSERVED",
                  "windows":[{"date":"2026-09-14","state":"OPEN","opensAt":"09:00:00","closesAt":"18:00:00"},
                             {"date":"2026-09-15","state":"CLOSED"}]}]}
                """);

        CuratedHoursPlan plan = CuratedHoursImportMain.read(file);

        assertThat(plan.places()).singleElement().satisfies(place -> assertThat(place.windows()).hasSize(2));
    }
}
