package io.nullnull.crowd.infrastructure.audit;

import io.nullnull.OperationsContext;
import io.nullnull.crowd.application.KtoCallInventory;
import io.nullnull.crowd.application.KtoCallInventoryQuery;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Lists the KTO operations one release actually used, for the submission's API list (CMP-KTO-006,
 * docs/contest/SUBMISSION_RUNBOOK.md).
 *
 * <pre>
 * NULLNULL_INVENTORY_RELEASE=&lt;release&gt; ./gradlew ktoCallInventory
 * </pre>
 *
 * <p>It reads the call-audit and nothing else, and starts through {@link OperationsContext} as a reading tool
 * (the rules are written there). Starting the application still does what every start does against its
 * database: TombstoneReapplier re-applies retained deletions, the idempotent step the deletion policy requires
 * after a restore. The release is required and never defaulted - the configured default is
 * {@code local-unreleased}, and a list for the wrong release is the easiest way to submit a set nobody called.
 *
 * <p>Its report opens with a line naming the database and the environment it read. Only a deployed
 * environment's list is evidence, by the same rule as {@code scripts/check_actual_call_evidence.py}: a local
 * database proves the code path, not that the deployed service called the provider.
 */
public final class KtoCallInventoryMain {

    /** Mirrors DEPLOYED_ENVIRONMENTS in scripts/check_actual_call_evidence.py. */
    static final Set<String> DEPLOYED_ENVIRONMENTS = Set.of("staging", "production");

    private KtoCallInventoryMain() {
    }

    public static void main(String[] args) {
        String release = System.getenv("NULLNULL_INVENTORY_RELEASE");
        if (release == null || release.isBlank()) {
            throw new IllegalStateException(
                    "NULLNULL_INVENTORY_RELEASE is required; the inventory is per release and has no default");
        }
        try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.READ)) {
            String environment = context.getEnvironment().getProperty("nullnull.env", "");
            String target = OperationsContext.target(context.getEnvironment().getProperty("spring.datasource.url", ""));
            KtoCallInventory inventory = context.getBean(KtoCallInventoryQuery.class).forRelease(release.strip());
            System.out.println(render(target, environment, inventory));
        }
    }

    /** Whether this list may stand as evidence: a deployed environment, and at least one usable call. */
    static String evidence(String environment, KtoCallInventory inventory) {
        if (!DEPLOYED_ENVIRONMENTS.contains(environment.toLowerCase(Locale.ROOT))) {
            return "counts_as_evidence=false reason=environment-not-deployed";
        }
        if (inventory.operations().isEmpty()) {
            return "counts_as_evidence=false reason=no-usable-call";
        }
        return "counts_as_evidence=true";
    }

    static String render(String target, String environment, KtoCallInventory inventory) {
        StringBuilder out = new StringBuilder();
        out.append("kto_inventory target=").append(target)
                .append(" environment=").append(environment.isBlank() ? "unset" : environment)
                .append(" release=").append(inventory.release()).append('\n');
        for (KtoCallInventory.Operation operation : inventory.operations()) {
            out.append("kto_operation source=").append(operation.sourceCode())
                    .append(" endpoint=").append(operation.endpointKey())
                    .append(" calls=").append(operation.calls())
                    .append(" first=").append(operation.firstCalledAt())
                    .append(" last=").append(operation.lastCalledAt()).append('\n');
        }
        out.append("kto_inventory_excluded rejected=").append(inventory.rejectedCalls())
                .append(" replay=").append(inventory.replayCalls()).append('\n');
        out.append("kto_inventory operations=").append(inventory.operations().size()).append(' ')
                .append(evidence(environment, inventory));
        return out.toString();
    }
}
