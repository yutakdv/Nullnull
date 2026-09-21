package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Imports the exact owner-approved list of normalized snapshots as an immutable replay. */
public final class ReplayManifestImportMain {

    private static final OperationsPlan.Source PLAN = new OperationsPlan.Source("NULLNULL_REPLAY_PLAN",
            "NULLNULL_REPLAY_PLAN_GZIP_BASE64", "NULLNULL_REPLAY_PLAN_SHA256", "replay plan");

    private ReplayManifestImportMain() {
    }

    public static void main(String[] args) {
        run(System.getenv(), args, System.out);
    }

    static void run(Map<String, String> environment, String[] args, PrintStream out) {
        try {
            OperationsPlan.Text text = OperationsPlan.read(environment, args, PLAN);
            out.println("replay_capture_plan sha256=" + text.sha256() + " bytes=" + text.bytes());
            ReplayManifestImporter.Plan plan = parse(text.json());
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                UUID manifestId = context.getBean(ReplayManifestImporter.class).importPlan(plan);
                for (UUID snapshotId : plan.snapshotIds()) {
                    out.println("replay_snapshot " + snapshotId + " CAPTURED");
                }
                out.println("replay_snapshots_captured=" + plan.snapshotIds().size());
                out.println("replay_manifest_id=" + manifestId);
            }
        } catch (Exception failure) {
            out.println("replay_capture_failed reason=" + OperationsPlan.failureReason(failure));
            throw new IllegalStateException("replay capture failed", failure);
        }
    }

    static ReplayManifestImporter.Plan parse(String json) {
        try {
            return JsonMapper.builder().findAndAddModules().build()
                    .readValue(json, ReplayManifestImporter.Plan.class);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("replay plan is invalid", invalid);
        }
    }
}
