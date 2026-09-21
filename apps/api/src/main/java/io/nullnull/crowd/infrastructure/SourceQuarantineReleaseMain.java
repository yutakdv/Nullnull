package io.nullnull.crowd.infrastructure;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.crowd.application.SourceRegistryStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Releases a source whose latest collector run is quarantined, as a reviewed operator decision.
 *
 * <p>This is the tool that did not exist. Every gateway asks {@link SourceRegistryStore#conditionAt}
 * before {@code collector.start}, so a quarantined latest run used to shut a source for good: no newer
 * run could be recorded to displace it, and the staging database is not reachable from outside the
 * VPC. {@code SEOUL_CITYDATA} locked itself that way on 2026-09-21 on its own five-minute schedule.
 *
 * <p>It records a {@code RESOLVED} incident and changes nothing else. The refused run keeps its
 * {@code QUARANTINED} status, so the history still says the source was shut and why. The owner's
 * approval travels with the task ({@code --owner-approval}); this main does not decide that a source
 * is safe, a person does, and this only writes down that they did.
 *
 * <p>When the latest run is not quarantined it says so and exits zero, printing a line the operator
 * refuses to count as a release - a no-op must not read as a source reopened.
 */
public final class SourceQuarantineReleaseMain {

    /** Registered source codes are upper snake case; anything else is a typo, not a source. */
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private SourceQuarantineReleaseMain() {
    }

    public static void main(String[] args) {
        try {
            String code = System.getenv("NULLNULL_RELEASE_SOURCE_CODE");
            if (code == null || !SOURCE_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("NULLNULL_RELEASE_SOURCE_CODE must name a registered source");
            }
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                release(code, context.getBean(SourceRegistryStore.class), Clock.systemUTC());
            }
        } catch (RuntimeException failure) {
            System.out.println("source_quarantine_release_failed reason=" + OperationsPlan.failureReason(failure));
            throw failure;
        }
    }

    static boolean release(String code, SourceRegistryStore store, Clock clock) {
        Instant now = clock.instant();
        // One incident per release; the code only has to be unique for this source.
        String incidentCode = "operator-release-" + now.toEpochMilli();
        Optional<SourceRegistryStore.ReleasedRun> released = store.releaseLatestQuarantine(code, incidentCode, now);
        if (released.isEmpty()) {
            System.out.println("source_quarantine_release_refused source=" + code + " reason=not-quarantined");
            return false;
        }
        SourceRegistryStore.ReleasedRun run = released.get();
        System.out.println("source_quarantine_released source=" + code + " run=" + run.runId()
                + " run_started=" + run.startedAt());
        return true;
    }
}
