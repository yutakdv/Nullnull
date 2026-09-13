package io.nullnull.social.infrastructure.curation;

import io.nullnull.NullnullApplication;
import io.nullnull.social.application.CuratedPostImporter;
import io.nullnull.social.application.CuratedPostImporter.ImportReport;
import io.nullnull.social.application.CuratedPostPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-031's operations script: publishes the curated feed posts written in a plan file.
 *
 * <p>Run it with the path to the plan:
 *
 * <pre>
 * NULLNULL_CURATION_PLAN=ops/curated-posts.json ./gradlew curatePosts
 * </pre>
 *
 * <p>It makes no external request and creates no catalog row. Everything it writes is either the
 * editorial content the file states or the 1st-party cover asset that content needs, and every place
 * it links must already be in the catalog. The plan file is the reviewable artefact: it can be read,
 * diffed and approved before anything runs, which is the property a migration and a writing endpoint
 * both lack - one buries editorial content in the schema, the other has no reviewer at all.
 *
 * <p>The environment guard is the one {@code ktoSmoke} uses and for the same reason: a script that
 * writes published content should not be runnable against whatever database happens to be configured.
 */
public final class CuratedPostImportMain {

    static final String PLAN_PATH = "NULLNULL_CURATION_PLAN";

    private CuratedPostImportMain() {
    }

    public static void main(String[] args) {
        Path plan = planPath(System.getenv(), args);
        CuratedPostPlan parsed = read(plan);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NullnullApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .run()) {
            ImportReport report = context.getBean(CuratedPostImporter.class).importPlan(parsed);
            System.out.println(summary(report));
        }
    }

    /**
     * The report, as one line per post and a total.
     *
     * <p>Ids and counts only. A curated post's title is editorial content the operator already has in
     * front of them in the file, and an operations log is not the place to copy it to.
     */
    static String summary(ImportReport report) {
        StringBuilder out = new StringBuilder();
        report.entries().forEach(entry -> out.append("curated_post ").append(entry.postId())
                .append(' ').append(entry.outcome()).append(" (").append(entry.detail()).append(")\n"));
        return out.append("curated_posts_published=").append(report.published())
                .append(" of ").append(report.entries().size()).toString();
    }

    static Path planPath(Map<String, String> environment, String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        String configured = environment.get(PLAN_PATH);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(PLAN_PATH + " must name the curation plan file");
        }
        return Path.of(configured);
    }

    /** Public so the suite can parse the sample plan with the reader the script itself uses. */
    public static CuratedPostPlan read(Path plan) {
        if (!Files.isRegularFile(plan)) {
            // The path is echoed because it is the operator's own argument, not user data.
            throw new IllegalStateException("no curation plan at " + plan);
        }
        ObjectMapper json = JsonMapper.builder()
                .findAndAddModules()
                .build();
        try {
            return json.readValue(Files.readString(plan), CuratedPostPlan.class);
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("the curation plan could not be read: " + plan, unreadable);
        }
    }
}
