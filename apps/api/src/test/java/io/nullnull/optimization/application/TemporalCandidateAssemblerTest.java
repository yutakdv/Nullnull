package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the optimizer is allowed to be asked about.
 *
 * <p>The cases that matter are the empty ones. A run with no candidates and a run whose itinerary is
 * already the best one look identical downstream, so the two reasons a date can be missing - no
 * forecast for the trip, no forecast for the day the item is on - have to be visible here rather
 * than discovered later as "the optimizer proposed nothing".
 */
@DisplayName("temporal candidate assembly")
class TemporalCandidateAssemblerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final UUID PLACE = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 10, 5);
    private static final LocalDate END = LocalDate.of(2026, 10, 8);
    private static final LocalDate CURRENT = LocalDate.of(2026, 10, 6);
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String ISSUE = "issue-1";

    @Test
    @DisplayName("every other forecast day in the trip becomes a candidate against the day the item is on")
    void otherDaysBecomeCandidates() {
        List<TemporalCandidateIn> candidates = assemble(set(
                snapshot(START, "10", ISSUE, "v1", Set.of()),
                snapshot(CURRENT, "80", ISSUE, "v1", Set.of()),
                snapshot(END, "20", ISSUE, "v1", Set.of())));

        assertThat(candidates).extracting(TemporalCandidateIn::date).containsExactly(START, END);
        // Day resolution and therefore no time: the only forecast source stores one point per date.
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.resolution()).isEqualTo(TemporalCandidateIn.ForecastResolution.DAY);
            assertThat(candidate.time()).isNull();
            assertThat(candidate.beforeValue()).isEqualByComparingTo(new BigDecimal("80"));
        });
        assertThat(candidates.get(0).afterValue()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(candidates).allMatch(TemporalCandidateIn::verdictEligible);
    }

    @Test
    @DisplayName("a pair that may not be compared is still offered, carrying the reason it may not")
    void anIneligiblePairIsCarriedRatherThanDropped() {
        // Dropping it would tell the optimizer the date does not exist. Offering it with the reason
        // lets the answer say "this day could not be compared", which is a different sentence and the
        // true one.
        List<TemporalCandidateIn> candidates = assemble(set(
                snapshot(CURRENT, "80", ISSUE, "v1", Set.of()),
                snapshot(END, "20", ISSUE, "v2", Set.of())));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).verdictEligible()).isFalse();
        assertThat(candidates.get(0).verdictReasonCode()).isNotBlank();
    }

    @Test
    @DisplayName("no forecast for the trip and no forecast for the item's own day both yield nothing")
    void theTwoEmptyCasesAreBothEmpty() {
        assertThat(assemble(null)).isEmpty();

        // A set that covers the trip but not the day the item sits on: there is no "before" to
        // compare against, so every other day is unmeasurable rather than unattractive.
        assertThat(assemble(set(snapshot(START, "10", ISSUE, "v1", Set.of()),
                snapshot(END, "20", ISSUE, "v1", Set.of())))).isEmpty();
    }

    @Test
    @DisplayName("a forecast day outside the trip is not a candidate")
    void datesOutsideTheTripAreNotOffered() {
        List<TemporalCandidateIn> candidates = assemble(set(
                snapshot(CURRENT, "80", ISSUE, "v1", Set.of()),
                snapshot(END.plusDays(3), "5", ISSUE, "v1", Set.of())));

        // The window is asked for in instants and the trip is lived in local dates; the traveller's
        // dates are the ones that decide.
        assertThat(candidates).isEmpty();
    }

    private List<TemporalCandidateIn> assemble(CrowdForecastQuery.SnapshotSet set) {
        return assembled(set).items();
    }

    private TemporalCandidateAssembler.Candidates assembled(CrowdForecastQuery.SnapshotSet set) {
        CrowdForecastQuery query = new CrowdForecastQuery() {
            @Override
            public Optional<SnapshotSet> latestFresh(UUID placeId, Instant from, Instant to, Instant now) {
                return Optional.ofNullable(set);
            }

            @Override
            public Optional<SnapshotSet> latestStale(UUID placeId, Instant from, Instant to, Instant now) {
                throw new AssertionError("a proposal is an instruction to change a plan; stale is not evidence for one");
            }
        };
        return new TemporalCandidateAssembler(query, new CrowdProvenanceProjection())
                .candidatesFor(PLACE, CURRENT, START, END, SEOUL, NOW);
    }

    @Test
    @DisplayName("the set's own facts come back with it, read once rather than fetched again")
    void theFrozenSetDescribesItself() {
        TemporalCandidateAssembler.Candidates candidates = assembled(set(
                snapshot(CURRENT, "80", ISSUE, "v1", Set.of()),
                snapshot(END, "20", ISSUE, "v1", Set.of())));

        // The fingerprint pins these, and the explanation names the metric and the source line. All
        // describe ONE set, so a caller that went back for them would be reading a second moment.
        assertThat(candidates.snapshotIds()).hasSize(2);
        assertThat(candidates.sourceRegistryVersions()).isEqualTo(Map.of("KTO_TARRLTVL", 1));
        assertThat(candidates.normalizationVersion()).isEqualTo("v1");
        assertThat(candidates.forecastIssueId()).isEqualTo(ISSUE);
        assertThat(candidates.metricCode()).isEqualTo("KTO_RELATIVE_CONCENTRATION_INDEX");
        assertThat(candidates.attribution()).isEqualTo("한국관광공사");
    }

    @Test
    @DisplayName("an empty answer carries no facts to mistake for evidence")
    void nothingFoundMeansNothingDescribed() {
        TemporalCandidateAssembler.Candidates none = assembled(null);

        // Not a set with zero candidates: a run that pinned an empty snapshot set would claim to have
        // frozen evidence it never saw, and RunFingerprint refuses an empty pin for that reason.
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.snapshotIds()).isEmpty();
        assertThat(none.normalizationVersion()).isNull();
    }

    private static CrowdForecastQuery.SnapshotSet set(CrowdForecastQuery.Snapshot... snapshots) {
        return new CrowdForecastQuery.SnapshotSet(UUID.randomUUID(), List.of(snapshots));
    }

    private static CrowdForecastQuery.Snapshot snapshot(LocalDate date, String value, String issueId,
            String normalizationVersion, Set<QualityFlag> flags) {
        return new CrowdForecastQuery.Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PLACE,
                new CrowdForecastQuery.SourceDescriptor("KTO_TARRLTVL", "KTO", 1L, "OGL", "https://kto",
                        "https://kto/license", "한국관광공사", "relative concentration"),
                SourceState.FORECAST, NOW, date.atStartOfDay(SEOUL).toInstant(), NOW,
                NOW.plusSeconds(86_400 * 30), "KTO_RELATIVE_CONCENTRATION_INDEX", new BigDecimal(value),
                "index", null, new BigDecimal("0.8"), flags, issueId, "group-1", normalizationVersion,
                0, ComparisonScope.PLACE, "place", "EXACT", false, false);
    }
}
