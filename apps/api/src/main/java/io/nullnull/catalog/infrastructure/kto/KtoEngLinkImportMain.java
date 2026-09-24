package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.catalog.application.EngTextLinkImporter;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports an owner-reviewed English link plan (BA-086). Prints the plan's hash and the place ids it
 * processed - never the plan's evidence URLs.
 */
public final class KtoEngLinkImportMain {

    private static final OperationsPlan.Source PLAN = new OperationsPlan.Source("NULLNULL_ENG_LINK_PLAN",
            "NULLNULL_ENG_LINK_PLAN_GZIP_BASE64", "NULLNULL_ENG_LINK_PLAN_SHA256", "English link plan");

    private KtoEngLinkImportMain() {
    }

    public static void main(String[] args) {
        run(System.getenv(), args, System.out);
    }

    static void run(Map<String, String> environment, String[] args, PrintStream out) {
        try {
            OperationsPlan.Text text = OperationsPlan.read(environment, args, PLAN);
            out.println("eng_link_plan sha256=" + text.sha256() + " bytes=" + text.bytes());
            EngTextLinkImporter.Plan plan = parse(text.json());
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                for (UUID placeId : context.getBean(EngTextLinkImporter.class).importPlan(plan)) {
                    out.println("eng_link " + placeId + " PROCESSED");
                }
                out.println("eng_links_processed=" + plan.links().size());
            }
        } catch (Exception failure) {
            out.println("eng_links_failed reason=" + OperationsPlan.failureReason(failure));
            throw new IllegalStateException("English link import failed", failure);
        }
    }

    /**
     * A malformed plan is reported by where it broke, not by what it said: a parser message quotes the text
     * around the error, and that text can be an evidence URL.
     */
    static EngTextLinkImporter.Plan parse(String json) {
        try {
            return JsonMapper.builder().findAndAddModules().build().readValue(json, EngTextLinkImporter.Plan.class);
        } catch (tools.jackson.core.JacksonException invalid) {
            var location = invalid.getLocation();
            throw new IllegalArgumentException("English link plan is invalid"
                    + (location == null ? "" : " at line " + location.getLineNr() + " column " + location.getColumnNr())
                    + reason(invalid));
        }
    }

    /** The plan records' own refusal, whose messages name a field and never carry its value. */
    private static String reason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root instanceof IllegalArgumentException && root != failure ? ": " + root.getMessage() : "";
    }
}
