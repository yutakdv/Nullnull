package io.nullnull.catalog.infrastructure.curation;

import io.nullnull.NullnullApplication;
import io.nullnull.catalog.application.CuratedHoursImporter;
import io.nullnull.catalog.application.CuratedHoursImporter.ImportReport;
import io.nullnull.catalog.application.CuratedHoursPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * BA-025's operations script: records the curated opening hours written in a plan file.
 *
 * <pre>
 * NULLNULL_HOURS_PLAN=ops/curated-hours.json ./gradlew curateHours
 * </pre>
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

    private CuratedHoursImportMain() {
    }

    public static void main(String[] args) {
        Path plan = planPath(System.getenv(), args);
        CuratedHoursPlan parsed = read(plan);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run()) {
            ImportReport report = context.getBean(CuratedHoursImporter.class).importPlan(parsed);
            System.out.println(summary(report));
        }
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

    static Path planPath(Map<String, String> environment, String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        String configured = environment.get(PLAN_PATH);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(PLAN_PATH + " must name the curated hours plan file");
        }
        return Path.of(configured);
    }

    /** Public so the suite can parse a sample plan with the reader the script itself uses. */
    public static CuratedHoursPlan read(Path plan) {
        if (!Files.isRegularFile(plan)) {
            // The path is echoed because it is the operator's own argument, not user data.
            throw new IllegalStateException("no curated hours plan at " + plan);
        }
        ObjectMapper json = JsonMapper.builder()
                .findAndAddModules()
                .build();
        try {
            return json.readValue(Files.readString(plan), CuratedHoursPlan.class);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("the curated hours plan could not be read: " + plan, unreadable);
        }
    }
}
