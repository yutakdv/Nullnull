package io.nullnull.catalog.infrastructure.curation;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.catalog.application.CuratedHoursImporter;
import io.nullnull.catalog.application.CuratedHoursImporter.ImportReport;
import io.nullnull.catalog.application.CuratedHoursPlan;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * BA-025's operations script: records the curated opening hours written in a plan file.
 *
 * <pre>
 * cd apps/api && NULLNULL_HOURS_PLAN="$(git rev-parse --show-toplevel)/ops/curated-hours.json" ./gradlew curateHours
 * </pre>
 *
 * <p>The only Gradle wrapper and the task's working directory are {@code apps/api}, and {@code ops/} is at the
 * repository root, hence the absolute path. In staging
 * the plan cannot be a file: the ops task runs the release's image, so the staging operator sends the owner-approved
 * bytes inline with their sha256 ({@code NULLNULL_HOURS_PLAN_GZIP_BASE64}, {@code NULLNULL_HOURS_PLAN_SHA256}; see
 * {@link OperationsPlan}). Either way the first line printed is the sha256 of the exact bytes imported, which the
 * operator compares with the one the owner approved.
 *
 * <p>It makes no external request. A-027 approved one exploratory call to KTO's {@code detailIntro2}
 * and said in the same breath that it was not an adoption, and no adoption decision followed - so the
 * page a curator reads is the place's own public notice and {@code evidenceUrl} names it. Nothing
 * here calls a provider, which is also why the script needs no approval of its own.
 *
 * <p>The plan file is the reviewable artefact: it can be read, diffed and approved before anything
 * runs. That is the property a migration and a writing endpoint both lack - one buries the content in
 * the schema where it cannot be corrected, the other has no reviewer at all.
 */
public final class CuratedHoursImportMain {

    static final String PLAN_PATH = "NULLNULL_HOURS_PLAN";
    static final OperationsPlan.Source PLAN = new OperationsPlan.Source(PLAN_PATH, "NULLNULL_HOURS_PLAN_GZIP_BASE64",
            "NULLNULL_HOURS_PLAN_SHA256", "curated hours plan");

    private CuratedHoursImportMain() {
    }

    public static void main(String[] args) {
        run(System.getenv(), args, System.out);
    }

    /**
     * The whole command. A failure prints one line the staging operator's log allowlist passes - so it is not silent
     * there - and is then rethrown, so the process exits non-zero: the operator reads success from the exit code as
     * well as from these lines, and a failure that returned normally would read as an import.
     */
    static void run(Map<String, String> environment, String[] args, PrintStream out) {
        try {
            OperationsPlan.Text plan = OperationsPlan.read(environment, args, PLAN);
            out.println(planLine(plan));
            CuratedHoursPlan parsed = parse(plan.json(), plan.origin());
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                ImportReport report = context.getBean(CuratedHoursImporter.class).importPlan(parsed);
                out.println(summary(report));
            }
        } catch (RuntimeException failure) {
            out.println(failureLine(failure));
            throw failure;
        }
    }

    static String planLine(OperationsPlan.Text plan) {
        return "curated_hours_plan sha256=" + plan.sha256() + " bytes=" + plan.bytes();
    }

    static String failureLine(Throwable failure) {
        return "curated_hours_failed reason=" + OperationsPlan.failureReason(failure);
    }

    /**
     * The report, as one line per place and a total.
     *
     * <p>Ids and counts only. The page a reading came from is in the file the operator is holding,
     * and an operations log is not the place to copy an external URL to.
     */
    static String summary(ImportReport report) {
        StringBuilder out = new StringBuilder();
        report.entries().forEach(entry -> out.append("curated_hours ").append(entry.placeId())
                .append(' ').append(entry.outcome())
                .append(" (windows=").append(entry.windows()).append(")\n"));
        return out.append("curated_hours_recorded=").append(report.recorded()).toString();
    }

    /** Public so the suite can parse a sample plan with the reader the script itself uses. */
    public static CuratedHoursPlan read(Path plan) {
        return parse(OperationsPlan.read(Map.of(PLAN_PATH, plan.toString()), null, PLAN).json(), plan.toString());
    }

    static CuratedHoursPlan parse(String text, String origin) {
        ObjectMapper json = JsonMapper.builder()
                .findAndAddModules()
                .build();
        try {
            return json.readValue(text, CuratedHoursPlan.class);
        } catch (tools.jackson.core.JacksonException unreadable) {
            throw new IllegalStateException("the curated hours plan could not be read: " + origin, unreadable);
        }
    }
}
