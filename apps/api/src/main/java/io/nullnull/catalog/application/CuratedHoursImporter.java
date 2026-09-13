package io.nullnull.catalog.application;

import io.nullnull.catalog.application.CuratedHoursPlan.CuratedPlaceHours;
import io.nullnull.catalog.application.CuratedHoursPlan.CuratedWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one reviewed plan file of curated opening hours (A-031's script, BA-025).
 *
 * <p>One file is one transaction. A plan that cannot be applied leaves nothing behind, so an
 * operator never has to work out how far a half-run got - the same property {@code CuratedPostImporter}
 * was built for, and the reason the plan validates itself before this class opens anything.
 *
 * <p>Re-running a file is the same request, not a second one. Each place's previous reading is
 * superseded rather than deleted, so the evidence that was current yesterday is still readable, and
 * the partial unique index on {@code superseded_at IS NULL} is what makes "current" mean one row.
 */
@Service
public class CuratedHoursImporter {

    /**
     * A-032: a human reading of opening hours is trusted for P30D. The same number the registry row
     * carries, kept here because the staleness of a reading is measured from when it was read - the
     * registry states the policy and each row states its own deadline.
     */
    static final Duration TRUSTED_FOR = Duration.ofDays(30);

    private final CatalogHoursStore hours;
    private final Clock clock;

    public CuratedHoursImporter(CatalogHoursStore hours, Clock clock) {
        this.hours = Objects.requireNonNull(hours, "hours");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public ImportReport importPlan(CuratedHoursPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Instant now = clock.instant();
        List<ImportReport.Entry> entries = new ArrayList<>(plan.places().size());
        for (CuratedPlaceHours place : plan.places()) {
            entries.add(apply(place, now));
        }
        return new ImportReport(List.copyOf(entries));
    }

    private ImportReport.Entry apply(CuratedPlaceHours place, Instant now) {
        java.util.Optional<UUID> current = hours.currentObservationId(place.placeId());
        ImportReport.Outcome outcome = current.isPresent()
                ? ImportReport.Outcome.REPLACED
                : ImportReport.Outcome.RECORDED;
        current.ifPresent(previous -> hours.supersede(previous, now));
        UUID observation = hours.recordObservation(place.placeId(), place.outcome().name(),
                place.observedAt(), place.evidenceUrl(), place.observedAt().plus(TRUSTED_FOR), now);
        for (CuratedWindow window : place.windows()) {
            hours.recordWindow(observation, window.date(), window.state().name(),
                    window.opensAt(), window.closesAt());
        }
        return new ImportReport.Entry(place.placeId(), outcome, place.windows().size());
    }

    /** Ids and counts only - a reading's page is in the file the operator already has open. */
    public record ImportReport(List<Entry> entries) {

        public record Entry(UUID placeId, Outcome outcome, int windows) {}

        public enum Outcome { RECORDED, REPLACED }

        public long recorded() {
            return entries.size();
        }
    }
}
