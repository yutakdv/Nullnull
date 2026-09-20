package io.nullnull.live.infrastructure.curation;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Imports an exact owner-approved mapping plan without logging its evidence URLs. */
public final class LiveMappingImportMain {

    private static final OperationsPlan.Source PLAN = new OperationsPlan.Source("NULLNULL_LIVE_MAPPING_PLAN",
            "NULLNULL_LIVE_MAPPING_PLAN_GZIP_BASE64", "NULLNULL_LIVE_MAPPING_PLAN_SHA256", "live mapping plan");

    private LiveMappingImportMain() {
    }

    public static void main(String[] args) {
        run(System.getenv(), args, System.out);
    }

    static void run(Map<String, String> environment, String[] args, PrintStream out) {
        try {
            OperationsPlan.Text text = OperationsPlan.read(environment, args, PLAN);
            out.println("curated_live_maps_plan sha256=" + text.sha256() + " bytes=" + text.bytes());
            var plan = parse(text.json());
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                for (UUID placeId : context.getBean(LiveMappingImporter.class).importPlan(plan)) {
                    out.println("curated_live_map " + placeId + " PROCESSED");
                }
                out.println("curated_live_maps_processed=" + plan.mappings().size());
            }
        } catch (Exception failure) {
            out.println("curated_live_maps_failed reason=" + OperationsPlan.failureReason(failure));
            throw new IllegalStateException("live mapping import failed", failure);
        }
    }

    static LiveMappingImporter.Plan parse(String json) {
        try {
            return JsonMapper.builder().findAndAddModules().build()
                    .readValue(json, LiveMappingImporter.Plan.class);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("live mapping plan is invalid", invalid);
        }
    }
}
