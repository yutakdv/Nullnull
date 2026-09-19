package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.application.ForecastDays;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        return assembledFrom(set, FROZEN);
    }

    /** The one set id this test's run froze; the fake answers for it and for nothing else. */
    private static final UUID FROZEN = UUID.randomUUID();

    private TemporalCandidateAssembler.Candidates assembledFrom(CrowdForecastQuery.SnapshotSet set, UUID frozen) {
        CrowdForecastQuery query = new CrowdForecastQuery() {
            @Override
            public Optional<SnapshotSet> latestFresh(UUID placeId, Instant from, Instant to, Instant now) {
                // #259: choosing "the newest" here was a second read that a set stored after the freeze
                // could change. Thrown rather than answered so a return to it is a failure, not a pass.
                throw new AssertionError("the assembler reads the set the run froze; it does not choose one");
            }

            @Override
            public Optional<SnapshotSet> latestStale(UUID placeId, Instant from, Instant to, Instant now) {
                throw new AssertionError("a proposal is an instruction to change a plan; stale is not evidence for one");
            }

            @Override
            public Optional<SnapshotSet> frozenSet(UUID setId, UUID placeId, Instant from, Instant to) {
                return setId.equals(FROZEN) ? Optional.ofNullable(set) : Optional.empty();
            }

            @Override
            public List<Snapshot> points(List<UUID> ids) {
                // Reading stored points back by id is what a reader of a proposal does.
                throw new AssertionError("the assembler reads a frozen set, it does not read points by id");
            }
        };
        return new TemporalCandidateAssembler(query, new CrowdProvenanceProjection())
                .candidatesFor(List.of(frozen), PLACE, CURRENT, START, END, NOW);
    }

    @Test
    @DisplayName("BA-051-T21 #259 the candidates come from the set the run froze, asked for by its id")
    void theCandidatesComeFromTheFrozenSet() {
        CrowdForecastQuery.SnapshotSet frozen = set(
                snapshot(CURRENT, "80", ISSUE, "v1", Set.of()),
                snapshot(END, "20", ISSUE, "v1", Set.of()));

        TemporalCandidateAssembler.Candidates offered = assembledFrom(frozen, FROZEN);
        assertThat(offered.snapshotIds()).containsExactlyInAnyOrderElementsOf(
                frozen.snapshots().stream().map(CrowdForecastQuery.Snapshot::id).toList());

        // A run that froze a different set is not handed this one - there is no "newest" to fall back to.
        assertThat(assembledFrom(frozen, UUID.randomUUID()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("#259 a frozen set that has gone stale since the freeze offers nothing")
    void aFrozenSetThatWentStaleOffersNothing() {
        CrowdForecastQuery.SnapshotSet stale = set(
                staleSnapshot(CURRENT, "80"), staleSnapshot(END, "20"));

        assertThat(assembled(stale).isEmpty()).isTrue();
    }

    private static CrowdForecastQuery.Snapshot staleSnapshot(LocalDate date, String value) {
        CrowdForecastQuery.Snapshot fresh = snapshot(date, value, ISSUE, "v1", Set.of());
        return new CrowdForecastQuery.Snapshot(fresh.id(), fresh.snapshotSetId(), fresh.collectorRunId(),
                fresh.placeId(), fresh.source(), fresh.sourceState(), fresh.observedAt(), fresh.targetAt(),
                fresh.fetchedAt(), NOW, fresh.metricCode(), fresh.value(), fresh.unit(), fresh.ordinalLevel(),
                fresh.confidence(), fresh.qualityFlags(), fresh.forecastIssueId(), fresh.comparisonGroupId(),
                fresh.normalizationVersion(), fresh.observedAtSkewSeconds(), fresh.scope(), fresh.scopeLabel(),
                fresh.mappingType(), fresh.fallbackUsed(), fresh.incidentActive());
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
        assertThat(candidates.sourceRegistryVersions()).isEqualTo(Map.of(KtoForecastSnapshotSet.SOURCE_CODE, 1));
        assertThat(candidates.normalizationVersion()).isEqualTo("v1");
        assertThat(candidates.forecastIssueId()).isEqualTo(ISSUE);
        assertThat(candidates.metricCode()).isEqualTo("KTO_RELATIVE_CONCENTRATION_INDEX");
        assertThat(candidates.attribution()).isEqualTo("한국관광공사");
    }

    @Test
    @DisplayName("a FORECAST point from a source whose day zone is unknown is refused, not read as KST")
    void aPointFromAnUnknownForecastSourceIsRefused() {
        CrowdForecastQuery.SnapshotSet set = set(
                snapshotFrom("SOME_OTHER_FORECAST", CURRENT, "80", ISSUE, "v1", Set.of()),
                snapshotFrom("SOME_OTHER_FORECAST", END, "20", ISSUE, "v1", Set.of()));
        assertThatThrownBy(() -> assembled(set))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SOME_OTHER_FORECAST");
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
        return snapshotFrom(KtoForecastSnapshotSet.SOURCE_CODE, date, value, issueId, normalizationVersion, flags);
    }

    private static CrowdForecastQuery.Snapshot snapshotFrom(String sourceCode, LocalDate date, String value,
            String issueId, String normalizationVersion, Set<QualityFlag> flags) {
        return new CrowdForecastQuery.Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PLACE,
                new CrowdForecastQuery.SourceDescriptor(sourceCode, "KTO", 1L, "OGL", "https://kto",
                        "https://kto/license", "한국관광공사", "relative concentration"),
                SourceState.FORECAST, NOW, ForecastDays.startOf(date), NOW,
                NOW.plusSeconds(86_400 * 30), "KTO_RELATIVE_CONCENTRATION_INDEX", new BigDecimal(value),
                "index", null, new BigDecimal("0.8"), flags, issueId, "group-1", normalizationVersion,
                0, ComparisonScope.PLACE, "place", "EXACT", false, false);
    }
}
