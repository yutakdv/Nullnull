package io.nullnull.social.infrastructure.curation;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.social.application.CuratedPostImporter;
import io.nullnull.social.application.CuratedPostImporter.ImportReport;
import io.nullnull.social.application.CuratedPostPlan;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A-031's operations script: publishes the curated feed posts written in a plan file.
 *
 * <p>Run it with the path to the plan:
 *
 * <pre>
 * cd apps/api && NULLNULL_CURATION_PLAN="$(git rev-parse --show-toplevel)/ops/curated-posts.json" ./gradlew curatePosts
 * </pre>
 *
 * <p>In staging the plan cannot be a file: the ops task runs the release's image with a read-only root, so the staging
 * operator sends the owner-approved bytes inline with their sha256 ({@code NULLNULL_POSTS_PLAN_GZIP_BASE64},
 * {@code NULLNULL_POSTS_PLAN_SHA256}; see {@link OperationsPlan}), exactly as it does for the hours. Either way the first
 * line printed is the sha256 of the bytes imported, which the operator compares with the one the owner approved. Until
 * this read a path only, the staging operator had no way to run it at all and the curate-posts task was withdrawn.
 *
 * <p>It makes no external request and creates no catalog row. Everything it writes is either the
 * editorial content the file states or the 1st-party cover asset that content needs, and every place
 * it links must already be in the catalog. The plan file is the reviewable artefact: it can be read,
 * diffed and approved before anything runs, which is the property a migration and a writing endpoint
 * both lack - one buries editorial content in the schema, the other has no reviewer at all.
 *
 * <p>It starts through {@link io.nullnull.OperationsContext} (#183) as a writing tool, like every operations
 * tool; the rules are written there. In staging or production it runs only when
 * {@code NULLNULL_OPERATIONS_TARGET} names the database it printed - step 5 of "staging이 선 날의 순서" in
 * {@code docs/contest/CURATED_POSTS_TEMPLATE.md}, made a check instead of a look. That holds only when
 * {@code NULLNULL_ENV} says staging or production; a deployed database reached with the label left at local is
 * treated as local. This comment once claimed the guard {@code ktoSmoke} uses; there was none until this.
 */
public final class CuratedPostImportMain {

    static final String PLAN_PATH = "NULLNULL_CURATION_PLAN";
    static final OperationsPlan.Source PLAN = new OperationsPlan.Source(PLAN_PATH, "NULLNULL_POSTS_PLAN_GZIP_BASE64",
            "NULLNULL_POSTS_PLAN_SHA256", "curation plan");

    private CuratedPostImportMain() {
    }

    public static void main(String[] args) {
        run(System.getenv(), args, System.out);
    }

    /**
     * The whole command. A failure prints one line the staging operator's log allowlist passes - so it is not silent
     * there - and is then rethrown, so the process exits non-zero: the operator reads success from the exit code as
     * well as from these lines, and a failure that returned normally would read as a publication.
     */
    static void run(Map<String, String> environment, String[] args, PrintStream out) {
        try {
            OperationsPlan.Text plan = OperationsPlan.read(environment, args, PLAN);
            out.println(planLine(plan));
            CuratedPostPlan parsed = parse(plan.json(), plan.origin());
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                ImportReport report = context.getBean(CuratedPostImporter.class).importPlan(parsed);
                out.println(summary(report));
            }
        } catch (RuntimeException failure) {
            out.println(failureLine(failure));
            throw failure;
        }
    }

    static String planLine(OperationsPlan.Text plan) {
        return "curated_posts_plan sha256=" + plan.sha256() + " bytes=" + plan.bytes();
    }

    static String failureLine(Throwable failure) {
        return "curated_posts_failed reason=" + OperationsPlan.failureReason(failure);
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

    /** Public so the suite can parse the sample plan with the reader the script itself uses. */
    public static CuratedPostPlan read(Path plan) {
        return parse(OperationsPlan.read(Map.of(PLAN_PATH, plan.toString()), null, PLAN).json(), plan.toString());
    }

    static CuratedPostPlan parse(String text, String origin) {
        ObjectMapper json = JsonMapper.builder()
                .findAndAddModules()
                .build();
        try {
            return json.readValue(text, CuratedPostPlan.class);
        } catch (tools.jackson.core.JacksonException unreadable) {
            throw new IllegalStateException("the curation plan could not be read: " + origin, unreadable);
        }
    }
}
