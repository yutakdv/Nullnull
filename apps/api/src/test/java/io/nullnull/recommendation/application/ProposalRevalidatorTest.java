package io.nullnull.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.PolicyPins;
import io.nullnull.recommendation.domain.Reason;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.LockIn;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.recommendation.testsupport.ItemFixtureLoader;
import io.nullnull.recommendation.testsupport.ItemFixtureLoader.ExpectedProposal;
import io.nullnull.recommendation.testsupport.ItemFixtureLoader.ItemFixture;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockChecks;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §3.1 final check, invariant 8: the worker records FAILED(DATA_CHANGED) and alerts when this returns
 * anything. Every ITEM fixture of {@code apps/ai} is replayed as the answer the service would give, so
 * a Java rule that drifts from the Python filters fails here.
 */
@DisplayName("§3.1 returned proposal re-validation")
class ProposalRevalidatorTest {

    static final String PIPELINE = "nullnull-ai-pipeline-v1";
    static final LocalDate D12 = LocalDate.of(2026, 9, 12);

    static final PolicyDescriptor CACHED = new PolicyDescriptor(PolicyPins.V1.policyVersion(),
            PolicyPins.V1.policyHash(), PIPELINE, "nullnull-ai-0.1.0");

    static final ProposalRevalidator REVALIDATOR = new ProposalRevalidator(CACHED);

    static List<ItemFixture> fixtures;

    @BeforeAll
    static void loadFixtures() {
        fixtures = ItemFixtureLoader.loadItemFixtures();
        assertThat(fixtures).as("ITEM fixtures listed in apps/ai/tests/recommendation/manifest.json").isNotEmpty();
    }

    // ---------------------------------------------------------------- fixture corpus

    @Test
    void theServiceAnswerOfEveryFixtureIsAccepted() {
        List<String> checked = new ArrayList<>();
        for (ItemFixture fixture : fixtures) {
            assertThat(codes(REVALIDATOR.check(fixture.request(), goldenResponse(fixture))))
                    .as("fixture %s must be accepted unchanged", fixture.id()).isEmpty();
            checked.add(fixture.id());
        }
        assertThat(checked).as("ITEM fixtures replayed").hasSize(fixtures.size());
    }

    // ---------------------------------------------------------------- response level

    @Test
    void anOutcomeThatDisagreesWithTheProposalListIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeResponse withoutProposals = response(fixture, ItemProposeResponse.Outcome.PROPOSALS, List.of());
        assertThat(codes(REVALIDATOR.check(fixture.request(), withoutProposals)))
                .contains(ProposalRevalidator.OUTCOME_MISMATCH);
        ItemProposeResponse lockedWithProposals = response(fixture, ItemProposeResponse.Outcome.LOCK_CONFLICT,
                goldenResponse(fixture).proposals());
        assertThat(codes(REVALIDATOR.check(fixture.request(), lockedWithProposals)))
                .contains(ProposalRevalidator.OUTCOME_MISMATCH);
    }

    @Test
    void ranksMustRunFromOneWithoutAGap() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<ItemProposalOut> proposals = new ArrayList<>(goldenResponse(fixture).proposals());
        proposals.set(0, ranked(proposals.get(0), 2));
        proposals.set(1, ranked(proposals.get(1), 3));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .contains(ProposalRevalidator.RANK_NOT_CONTIGUOUS);
    }

    @Test
    void twoProposalsNeverShareOneSlot() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposalOut first = goldenResponse(fixture).proposals().get(0);
        List<ItemProposalOut> proposals = List.of(first, ranked(first, 2));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .contains(ProposalRevalidator.DUPLICATE_SLOT);
    }

    @Test
    void moreProposalsThanThePolicyAllowsAreRejected() {
        ItemFixture fixture = fixture("deterministic-score-boundaries");
        List<ItemProposalOut> golden = goldenResponse(fixture).proposals();
        List<ItemProposalOut> proposals = new ArrayList<>(golden);
        proposals.add(ranked(golden.get(0), 4));
        assertThat(proposals).hasSize(PolicyPins.V1.caps().itemProposals() + 1);
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .contains(ProposalRevalidator.TOO_MANY_PROPOSALS);
    }

    @Test
    void aChangedPolicyVersionOrHashIsRejectedBeforeAnythingElse() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeResponse golden = goldenResponse(fixture);
        ItemProposeResponse otherVersion = new ItemProposeResponse("policy-v2", golden.policyHash(), PIPELINE,
                golden.outcome(), golden.proposals(), golden.reasons(), golden.evaluated(), golden.rejectedByReason());
        assertThat(codes(REVALIDATOR.check(fixture.request(), otherVersion)))
                .contains(ProposalRevalidator.POLICY_VERSION_MISMATCH);

        ItemProposeResponse otherHash = new ItemProposeResponse(golden.policyVersion(), "b".repeat(64), PIPELINE,
                golden.outcome(), golden.proposals(), golden.reasons(), golden.evaluated(), golden.rejectedByReason());
        assertThat(codes(REVALIDATOR.check(fixture.request(), otherHash)))
                .contains(ProposalRevalidator.POLICY_HASH_MISMATCH);
    }

    @Test
    void aCachedPolicyThatIsNotThePinnedPolicyFailsClosed() {
        ItemFixture fixture = fixture("temporal-same-issue");
        PolicyDescriptor stale = new PolicyDescriptor(PolicyPins.V1.policyVersion(), "c".repeat(64), PIPELINE,
                "nullnull-ai-0.1.0");
        ItemProposeResponse golden = goldenResponse(fixture);
        ItemProposeResponse matchingTheStaleCache = new ItemProposeResponse(golden.policyVersion(), "c".repeat(64),
                PIPELINE, golden.outcome(), golden.proposals(), golden.reasons(), golden.evaluated(),
                golden.rejectedByReason());
        assertThat(codes(new ProposalRevalidator(stale).check(fixture.request(), matchingTheStaleCache)))
                .as("a cached policy the API does not pin is never trusted")
                .contains(ProposalRevalidator.POLICY_HASH_MISMATCH);
    }

    @Test
    void responseLevelViolationsAreReportedBeforeProposalLevelOnes() {
        ItemFixture fixture = fixture("locked-date-and-reservation");
        ItemProposeResponse response = lockedFixtureResponseProposingItsFirstCandidate(fixture);
        ItemProposeResponse alsoWrongVersion = new ItemProposeResponse("policy-v2", response.policyHash(), PIPELINE,
                response.outcome(), response.proposals(), response.reasons(), response.evaluated(),
                response.rejectedByReason());
        assertThat(codes(REVALIDATOR.check(fixture.request(), alsoWrongVersion)))
                .containsExactly(ProposalRevalidator.POLICY_VERSION_MISMATCH, LockChecks.DATE_LOCKED,
                        LockChecks.RESERVATION_LOCKED);
    }

    @Test
    void anAnswerFromAPipelineTheWorkerDidNotCacheIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeResponse golden = goldenResponse(fixture);
        ItemProposeResponse otherPipeline = new ItemProposeResponse(golden.policyVersion(), golden.policyHash(),
                "nullnull-ai-pipeline-v2", golden.outcome(), golden.proposals(), golden.reasons(),
                golden.evaluated(), golden.rejectedByReason());
        assertThat(codes(REVALIDATOR.check(fixture.request(), otherPipeline)))
                .contains(ProposalRevalidator.PIPELINE_VERSION_MISMATCH);
    }

    @Test
    void aCachedPipelineThatIsNotThePinnedPipelineFailsClosed() {
        ItemFixture fixture = fixture("temporal-same-issue");
        PolicyDescriptor stale = new PolicyDescriptor(PolicyPins.V1.policyVersion(), PolicyPins.V1.policyHash(),
                "nullnull-ai-pipeline-v2", "nullnull-ai-0.1.0");
        ItemProposeResponse golden = goldenResponse(fixture);
        ItemProposeResponse matchingTheStaleCache = new ItemProposeResponse(golden.policyVersion(),
                golden.policyHash(), "nullnull-ai-pipeline-v2", golden.outcome(), golden.proposals(),
                golden.reasons(), golden.evaluated(), golden.rejectedByReason());
        assertThat(codes(new ProposalRevalidator(stale).check(fixture.request(), matchingTheStaleCache)))
                .as("a cached pipeline the API does not pin is never trusted")
                .contains(ProposalRevalidator.PIPELINE_VERSION_MISMATCH);
    }

    // ---------------------------------------------------------------- proposal level

    @Test
    void aDayAndAnHourCandidateMayShareOneSlotAndTheSnapshotPairSaysWhichWasKept() {
        // apps/ai merges candidates onto one (date, start time) and proposes the best; a DAY candidate
        // keeps the item's current time, so it can land on the same slot as an HOUR candidate.
        ItemProposeRequest request = syntheticRequest(LocalTime.of(10, 0), 90,
                List.of(dayCandidate(D13, "80", "40", SNAP_A, SNAP_B),
                        hourCandidate(D13, LocalTime.of(10, 0), "80", "60", SNAP_C, SNAP_D)));
        ItemProposalOut kept = synthesizedProposal(request, D13, LocalTime.of(10, 0), "20", "0.110000",
                "0.250000", SNAP_C, SNAP_D);
        assertThat(codes(REVALIDATOR.check(request, syntheticResponse(request, List.of(kept)))))
                .as("the HOUR candidate's own snapshot pair is the evidence for the preview").isEmpty();
    }

    @Test
    void aSnapshotPairBelongingToNeitherMergedCandidateIsASnapshotMismatchAndNotAMissingSlot() {
        ItemProposeRequest request = syntheticRequest(LocalTime.of(10, 0), 90,
                List.of(dayCandidate(D13, "80", "40", SNAP_A, SNAP_B),
                        hourCandidate(D13, LocalTime.of(10, 0), "80", "60", SNAP_C, SNAP_D)));
        ItemProposalOut invented = synthesizedProposal(request, D13, LocalTime.of(10, 0), "20", "0.110000",
                "0.250000", SNAP_E, SNAP_F);
        List<String> reasons = codes(REVALIDATOR.check(request, syntheticResponse(request, List.of(invented))));
        assertThat(reasons).containsExactly(ProposalRevalidator.SNAPSHOT_MISMATCH);
        assertThat(reasons).doesNotContain(ProposalRevalidator.PROPOSAL_NOT_IN_REQUEST);
    }

    @Test
    void anUntimedItemMovedToAnotherDateKeepsItsMissingStartTime() {
        // effectiveStartTime(null) on a DAY candidate: the slot has no time, so the opening window only
        // has to be an open day and no neighbour interval exists to overlap (§5.3, filters.py).
        ItemProposeRequest request = syntheticRequest(null, 90,
                List.of(dayCandidate(D13, "80", "60", SNAP_A, SNAP_B)));
        ItemProposalOut untimed = synthesizedProposal(request, D13, null, "20", "0.160000", "0.000000",
                SNAP_A, SNAP_B);
        assertThat(codes(REVALIDATOR.check(request, syntheticResponse(request, List.of(untimed)))))
                .as("a date-only move of an untimed item is verifiable").isEmpty();
    }


    @Test
    void aSlotThatWasNeverOfferedIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<ItemProposalOut> proposals = List.of(atSlot(goldenResponse(fixture).proposals().get(0), D12,
                LocalTime.of(13, 0)));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .containsExactly(ProposalRevalidator.PROPOSAL_NOT_IN_REQUEST);
    }

    @Test
    void anIneligibleComparisonIsNeverProposed() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<TemporalCandidateIn> candidates = new ArrayList<>();
        for (TemporalCandidateIn candidate : fixture.request().candidates()) {
            candidates.add(candidate.time() != null && candidate.time().equals(LocalTime.of(12, 0))
                    ? verdict(candidate, false, "STALE_INPUT") : candidate);
        }
        ItemProposeRequest request = withCandidates(fixture.request(), candidates);
        assertThat(codes(REVALIDATOR.check(request, goldenResponse(fixture))))
                .contains(ProposalRevalidator.VERDICT_INELIGIBLE);
    }

    @Test
    void aSnapshotPairThatDisagreesWithTheRequestIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<ItemProposalOut> proposals = new ArrayList<>(goldenResponse(fixture).proposals());
        ItemProposalOut first = proposals.get(0);
        proposals.set(0, withSnapshots(first, first.afterSnapshotId(), first.beforeSnapshotId()));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .contains(ProposalRevalidator.SNAPSHOT_MISMATCH);
    }

    @Test
    void anUnpinnedMetricIsNeverScored() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<TemporalCandidateIn> candidates = new ArrayList<>();
        for (TemporalCandidateIn candidate : fixture.request().candidates()) {
            candidates.add(candidate.time() != null && candidate.time().equals(LocalTime.of(12, 0))
                    ? metric(candidate, "SEOUL_LIVE_POPULATION") : candidate);
        }
        assertThat(codes(REVALIDATOR.check(withCandidates(fixture.request(), candidates), goldenResponse(fixture))))
                .contains(ProposalRevalidator.METRIC_UNSUPPORTED);
    }

    @Test
    void theImprovementMustBeTheRequestPairsOwnDifference() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<ItemProposalOut> proposals = new ArrayList<>(goldenResponse(fixture).proposals());
        proposals.set(0, withImprovement(proposals.get(0), proposals.get(0).improvement().add(BigDecimal.ONE)));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .contains(ProposalRevalidator.IMPROVEMENT_MISMATCH);
    }

    @Test
    void anImprovementBelowThePolicyMinimumIsRejected() {
        // The 10:15 candidate of the fixture improves 80 -> 77, three points under the minimum of five.
        ItemFixture fixture = fixture("temporal-same-issue");
        List<ItemProposalOut> proposals = List.of(proposalFor(fixture.request(), 1, D12, LocalTime.of(10, 15),
                "3", "0.020000", "0.062500"));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .containsExactly(ProposalRevalidator.IMPROVEMENT_BELOW_MINIMUM);
    }

    @Test
    void aProposalWithoutAPositiveScoreIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        List<ItemProposalOut> proposals = new ArrayList<>(goldenResponse(fixture).proposals());
        proposals.set(0, withScore(proposals.get(0), new BigDecimal("0.000000")));
        assertThat(codes(REVALIDATOR.check(fixture.request(),
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .contains(ProposalRevalidator.SCORE_NOT_POSITIVE);
    }

    @Test
    void proposingTheSlotTheItemAlreadyHasIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeRequest request = withCandidates(fixture.request(),
                append(fixture.request().candidates(), candidateAt(fixture.request(), D12, LocalTime.of(10, 0))));
        List<ItemProposalOut> proposals = List.of(proposalFor(request, 1, D12, LocalTime.of(10, 0), "20",
                "0.160000", "0.000000"));
        assertThat(codes(REVALIDATOR.check(request,
                response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals))))
                .containsExactly(ProposalRevalidator.NO_CHANGE);
    }

    @Test
    void aProposalOutsideTheTripRangeIsRejected() {
        ItemFixture fixture = fixture("deterministic-score-boundaries");
        ItemProposeRequest request = withTripEnd(fixture.request(), D12);
        assertThat(codes(REVALIDATOR.check(request, goldenResponse(fixture))))
                .contains(ProposalRevalidator.OUTSIDE_TRIP_RANGE);
    }

    @Test
    void aLockedDateIsNeverMovedAndEveryBrokenLockIsReportedInTheFixedOrder() {
        ItemFixture same = fixture("temporal-same-issue");
        ItemProposeRequest locked = withLocks(same.request(), List.of(LockIn.date(D12.plusDays(1))));
        assertThat(codes(REVALIDATOR.check(locked, goldenResponse(same)))).contains(LockChecks.DATE_LOCKED);

        ItemFixture both = fixture("locked-date-and-reservation");
        assertThat(codes(REVALIDATOR.check(both.request(), lockedFixtureResponseProposingItsFirstCandidate(both))))
                .containsExactly(LockChecks.DATE_LOCKED, LockChecks.RESERVATION_LOCKED);
    }

    @Test
    void aPinnedStartTimeIsNeverMovedBeyondItsTolerance() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeRequest locked = withLocks(fixture.request(), List.of(LockIn.time(LocalTime.of(10, 0), 0)));
        assertThat(codes(REVALIDATOR.check(locked, goldenResponse(fixture)))).contains(LockChecks.TIME_LOCKED);
    }

    /**
     * The production case, which the explicit-unknown test above does not reach: nothing hydrates
     * openingHours today, so the map is EMPTY rather than holding OpeningWindowIn.unknown(). An
     * absent entry has to mean unverified, never open - that is the branch a real request takes, and
     * the one a future hydration could quietly weaken by filling the map with a guessed window.
     * PM-014: a high UNKNOWN rate is the correct answer to missing evidence, not a defect to relax.
     */
    @Test
    void anAbsentOpeningWindowIsUnverifiedRatherThanOpen() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeRequest noHoursAtAll = rebuild(fixture.request(), fixture.request().tripEnd(),
                fixture.request().target().durationMinutes(), fixture.request().locks(),
                fixture.request().neighbours(), Map.of(), fixture.request().routeEvidence(),
                fixture.request().candidates());

        assertThat(noHoursAtAll.openingHours()).isEmpty();
        assertThat(codes(REVALIDATOR.check(noHoursAtAll, goldenResponse(fixture))))
                .contains(ProposalRevalidator.OPENING_HOURS_UNKNOWN);
    }

    @Test
    void unverifiedOrViolatedOpeningHoursAreRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        assertThat(codes(REVALIDATOR.check(withOpeningWindow(fixture.request(), D12, OpeningWindowIn.unknown()),
                goldenResponse(fixture)))).contains(ProposalRevalidator.OPENING_HOURS_UNKNOWN);
        assertThat(codes(REVALIDATOR.check(withOpeningWindow(fixture.request(), D12, OpeningWindowIn.closed()),
                goldenResponse(fixture)))).contains(ProposalRevalidator.CLOSED);
        assertThat(codes(REVALIDATOR.check(withOpeningWindow(fixture.request(), D12,
                OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(12, 0))), goldenResponse(fixture))))
                .contains(ProposalRevalidator.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void aStayWhoseLengthIsUnknownIsNeverVerified() {
        ItemFixture fixture = fixture("temporal-same-issue");
        ItemProposeRequest request = withTargetDuration(fixture.request(), null);
        assertThat(codes(REVALIDATOR.check(request, goldenResponse(fixture))))
                .contains(ProposalRevalidator.DURATION_UNKNOWN);
    }

    @Test
    void aNeighbourAtTheProposedTimeOrWithoutALengthIsRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        NeighbourItemIn overlapping = new NeighbourItemIn(UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b09"),
                D12, 2, LocalTime.of(12, 0), 60);
        assertThat(codes(REVALIDATOR.check(withNeighbours(fixture.request(), List.of(overlapping),
                ItemProposeRequest.RouteEvidence.VERIFIED), goldenResponse(fixture))))
                .contains(ProposalRevalidator.OVERLAPS_NEIGHBOUR);

        NeighbourItemIn unmeasured = new NeighbourItemIn(UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b09"),
                D12, 2, LocalTime.of(16, 0), null);
        assertThat(codes(REVALIDATOR.check(withNeighbours(fixture.request(), List.of(unmeasured),
                ItemProposeRequest.RouteEvidence.VERIFIED), goldenResponse(fixture))))
                .contains(ProposalRevalidator.NEIGHBOUR_DURATION_UNKNOWN);
    }

    @Test
    void aConfirmedOverlapOutranksAnUnmeasuredNeighbourWhicheverIsStoredFirst() {
        // §6 / filters.py: scanning every neighbour of the date first makes the known fact win, so the
        // reported code cannot flip with the order the trip happened to keep its items in.
        ItemFixture fixture = fixture("temporal-same-issue");
        NeighbourItemIn unmeasured = new NeighbourItemIn(UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b0a"),
                D12, 2, LocalTime.of(9, 30), null);
        NeighbourItemIn overlapping = new NeighbourItemIn(UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b0b"),
                D12, 3, LocalTime.of(12, 0), 60);
        for (List<NeighbourItemIn> stored : List.of(List.of(unmeasured, overlapping),
                List.of(overlapping, unmeasured))) {
            assertThat(codes(REVALIDATOR.check(withNeighbours(fixture.request(), stored,
                    ItemProposeRequest.RouteEvidence.VERIFIED), goldenResponse(fixture))))
                    .as("stored order %s", stored)
                    .contains(ProposalRevalidator.OVERLAPS_NEIGHBOUR)
                    .doesNotContain(ProposalRevalidator.NEIGHBOUR_DURATION_UNKNOWN);
        }
        assertThat(codes(REVALIDATOR.check(withNeighbours(fixture.request(), List.of(unmeasured),
                ItemProposeRequest.RouteEvidence.VERIFIED), goldenResponse(fixture))))
                .as("nothing overlaps, so the missing length is what blocks the preview")
                .contains(ProposalRevalidator.NEIGHBOUR_DURATION_UNKNOWN)
                .doesNotContain(ProposalRevalidator.OVERLAPS_NEIGHBOUR);
    }

    @Test
    void changedTravelLegsWithoutRouteEvidenceAreRejected() {
        ItemFixture fixture = fixture("temporal-same-issue");
        NeighbourItemIn sameDay = new NeighbourItemIn(UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93b09"),
                D12, 2, LocalTime.of(16, 0), 60);
        assertThat(codes(REVALIDATOR.check(withNeighbours(fixture.request(), List.of(sameDay),
                ItemProposeRequest.RouteEvidence.NONE), goldenResponse(fixture))))
                .contains(ProposalRevalidator.ROUTE_EVIDENCE_MISSING);
    }

    // ---------------------------------------------------------------- helpers

    static final LocalDate D13 = LocalDate.of(2026, 9, 13);
    static final UUID SNAP_A = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9da01");
    static final UUID SNAP_B = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9da02");
    static final UUID SNAP_C = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9da03");
    static final UUID SNAP_D = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9da04");
    static final UUID SNAP_E = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9da05");
    static final UUID SNAP_F = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9da06");
    static final UUID SYNTHETIC_ITEM = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9db01");
    static final UUID SYNTHETIC_PLACE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9db02");

    /** A hand-built request that does not depend on any fixture: three open days, no locks, no neighbours. */
    private static ItemProposeRequest syntheticRequest(LocalTime targetStart, Integer durationMinutes,
            List<TemporalCandidateIn> candidates) {
        OpeningWindowIn open = OpeningWindowIn.open(LocalTime.of(9, 0), LocalTime.of(18, 0));
        Map<LocalDate, OpeningWindowIn> hours = new LinkedHashMap<>();
        hours.put(D12, open);
        hours.put(D13, open);
        hours.put(D12.plusDays(2), open);
        return new ItemProposeRequest(Instant.parse("2026-09-06T00:00:00Z"),
                UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93c01"), 7, D12, D12.plusDays(2), "Asia/Seoul",
                new io.nullnull.recommendation.domain.item.TargetItemIn(SYNTHETIC_ITEM, SYNTHETIC_PLACE, D12,
                        targetStart, durationMinutes, 1),
                List.of(), List.of(), hours, ItemProposeRequest.RouteEvidence.NONE, candidates);
    }

    private static TemporalCandidateIn dayCandidate(LocalDate date, String before, String after, UUID beforeSnapshot,
            UUID afterSnapshot) {
        return new TemporalCandidateIn(SYNTHETIC_PLACE, date, null, TemporalCandidateIn.ForecastResolution.DAY,
                new BigDecimal(before), new BigDecimal(after), PolicyPins.KTO_RELATIVE_CONCENTRATION_INDEX, true,
                "SAME_METRIC_AND_ISSUE", beforeSnapshot, afterSnapshot);
    }

    private static TemporalCandidateIn hourCandidate(LocalDate date, LocalTime at, String before, String after,
            UUID beforeSnapshot, UUID afterSnapshot) {
        return new TemporalCandidateIn(SYNTHETIC_PLACE, date, at, TemporalCandidateIn.ForecastResolution.HOUR,
                new BigDecimal(before), new BigDecimal(after), PolicyPins.KTO_RELATIVE_CONCENTRATION_INDEX, true,
                "SAME_METRIC_AND_ISSUE", beforeSnapshot, afterSnapshot);
    }

    private static ItemProposalOut synthesizedProposal(ItemProposeRequest request, LocalDate date, LocalTime at,
            String improvement, String score, String changeCost, UUID beforeSnapshot, UUID afterSnapshot) {
        BigDecimal improvementValue = new BigDecimal(improvement);
        BigDecimal relief = improvementValue.divide(
                BigDecimal.valueOf(PolicyPins.V1.metric(PolicyPins.KTO_RELATIVE_CONCENTRATION_INDEX).metricScale()),
                PolicyPins.V1.numericScale(), PolicyPins.V1.roundingMode());
        return new ItemProposalOut(1, date, at, instant(request, request.target().date(),
                request.target().startTime()), instant(request, date, at), new BigDecimal(score), improvementValue,
                relief, new BigDecimal(changeCost), beforeSnapshot, afterSnapshot, Map.of());
    }

    private static ItemProposeResponse syntheticResponse(ItemProposeRequest request,
            List<ItemProposalOut> proposals) {
        return new ItemProposeResponse(PolicyPins.V1.policyVersion(), PolicyPins.V1.policyHash(), PIPELINE,
                ItemProposeResponse.Outcome.PROPOSALS, proposals, List.of(), request.candidates().size(), Map.of());
    }


    private static List<String> codes(List<Reason> reasons) {
        return reasons.stream().map(Reason::code).toList();
    }

    private static ItemFixture fixture(String id) {
        return fixtures.stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("fixture " + id + " is not in the manifest"));
    }

    /** The answer {@code apps/ai} produces for this fixture, rebuilt from its {@code expected} block. */
    private static ItemProposeResponse goldenResponse(ItemFixture fixture) {
        List<ItemProposalOut> proposals = new ArrayList<>();
        int rank = 1;
        for (ExpectedProposal expected : fixture.expected().proposals()) {
            proposals.add(proposalFor(fixture.request(), rank++, expected.date(), expected.startTime(),
                    expected.improvement(), expected.score(), expected.changeCost()));
        }
        return response(fixture, fixture.expected().outcome(), proposals);
    }

    private static ItemProposeResponse response(ItemFixture fixture, ItemProposeResponse.Outcome outcome,
            List<ItemProposalOut> proposals) {
        return new ItemProposeResponse(PolicyPins.V1.policyVersion(), PolicyPins.V1.policyHash(), PIPELINE, outcome,
                proposals, List.of(), fixture.request().candidates().size(), fixture.expected().rejectedByReason());
    }

    /**
     * One proposal exactly as the service would serialize it for the candidate at that slot. Several
     * candidates may share a slot (a DAY candidate keeps the item's current time), so the expected
     * improvement — the difference of the pair the service kept — says which one won it.
     */
    private static ItemProposalOut proposalFor(ItemProposeRequest request, int rank, LocalDate date,
            LocalTime startTime, String improvement, String score, String changeCost) {
        BigDecimal improvementValue = new BigDecimal(improvement);
        List<TemporalCandidateIn> atSlot = request.candidates().stream()
                .filter(c -> c.date().equals(date)
                        && java.util.Objects.equals(c.effectiveStartTime(request.target().startTime()), startTime))
                .toList();
        TemporalCandidateIn candidate = (atSlot.size() == 1 ? atSlot : atSlot.stream()
                .filter(c -> c.beforeValue().subtract(c.afterValue()).compareTo(improvementValue) == 0).toList())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no candidate at " + date + " " + startTime));
        int metricScale = PolicyPins.V1.metric(candidate.metricCode()).metricScale();
        BigDecimal relief = improvementValue.divide(BigDecimal.valueOf(metricScale), PolicyPins.V1.numericScale(),
                PolicyPins.V1.roundingMode());
        List<ItemLock> locks = request.locks().stream().map(LockIn::toItemLock).toList();
        Map<String, Boolean> lockChecks = new LinkedHashMap<>();
        LockChecks.evaluate(locks, date, startTime, request.target().durationMinutes()).passed()
                .forEach((type, passed) -> lockChecks.put(type.name(), passed));
        return new ItemProposalOut(rank, date, startTime,
                instant(request, request.target().date(), request.target().startTime()),
                instant(request, date, startTime), new BigDecimal(score), improvementValue, relief,
                new BigDecimal(changeCost), candidate.beforeSnapshotId(), candidate.afterSnapshotId(), lockChecks);
    }

    private static ItemProposeResponse lockedFixtureResponseProposingItsFirstCandidate(ItemFixture fixture) {
        TemporalCandidateIn candidate = fixture.request().candidates().get(0);
        LocalTime at = candidate.effectiveStartTime(fixture.request().target().startTime());
        List<ItemProposalOut> proposals = List.of(proposalFor(fixture.request(), 1, candidate.date(), at,
                candidate.beforeValue().subtract(candidate.afterValue()).toPlainString(), "0.360000", "1.000000"));
        return response(fixture, ItemProposeResponse.Outcome.PROPOSALS, proposals);
    }

    private static Instant instant(ItemProposeRequest request, LocalDate date, LocalTime at) {
        return ZonedDateTime.of(date, at == null ? LocalTime.MIDNIGHT : at, ZoneId.of(request.tripZone())).toInstant();
    }

    private static ItemProposalOut ranked(ItemProposalOut p, int rank) {
        return new ItemProposalOut(rank, p.date(), p.startTime(), p.beforeInstant(), p.afterInstant(), p.score(),
                p.improvement(), p.relief(), p.changeCost(), p.beforeSnapshotId(), p.afterSnapshotId(),
                p.lockChecks());
    }

    private static ItemProposalOut atSlot(ItemProposalOut p, LocalDate date, LocalTime startTime) {
        return new ItemProposalOut(p.rank(), date, startTime, p.beforeInstant(), p.afterInstant(), p.score(),
                p.improvement(), p.relief(), p.changeCost(), p.beforeSnapshotId(), p.afterSnapshotId(),
                p.lockChecks());
    }

    private static ItemProposalOut withScore(ItemProposalOut p, BigDecimal score) {
        return new ItemProposalOut(p.rank(), p.date(), p.startTime(), p.beforeInstant(), p.afterInstant(), score,
                p.improvement(), p.relief(), p.changeCost(), p.beforeSnapshotId(), p.afterSnapshotId(),
                p.lockChecks());
    }

    private static ItemProposalOut withImprovement(ItemProposalOut p, BigDecimal improvement) {
        return new ItemProposalOut(p.rank(), p.date(), p.startTime(), p.beforeInstant(), p.afterInstant(), p.score(),
                improvement, p.relief(), p.changeCost(), p.beforeSnapshotId(), p.afterSnapshotId(), p.lockChecks());
    }

    private static ItemProposalOut withSnapshots(ItemProposalOut p, UUID before, UUID after) {
        return new ItemProposalOut(p.rank(), p.date(), p.startTime(), p.beforeInstant(), p.afterInstant(), p.score(),
                p.improvement(), p.relief(), p.changeCost(), before, after, p.lockChecks());
    }

    private static TemporalCandidateIn verdict(TemporalCandidateIn c, boolean eligible, String reasonCode) {
        return new TemporalCandidateIn(c.placeId(), c.date(), c.time(), c.resolution(), c.beforeValue(),
                c.afterValue(), c.metricCode(), eligible, reasonCode, c.beforeSnapshotId(), c.afterSnapshotId());
    }

    private static TemporalCandidateIn metric(TemporalCandidateIn c, String metricCode) {
        return new TemporalCandidateIn(c.placeId(), c.date(), c.time(), c.resolution(), c.beforeValue(),
                c.afterValue(), metricCode, c.verdictEligible(), c.verdictReasonCode(), c.beforeSnapshotId(),
                c.afterSnapshotId());
    }

    private static TemporalCandidateIn candidateAt(ItemProposeRequest request, LocalDate date, LocalTime at) {
        return new TemporalCandidateIn(request.target().placeId(), date, at,
                TemporalCandidateIn.ForecastResolution.HOUR, new BigDecimal("80"), new BigDecimal("60"),
                PolicyPins.KTO_RELATIVE_CONCENTRATION_INDEX, true, "SAME_METRIC_AND_ISSUE",
                UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9d901"),
                UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b9d902"));
    }

    private static <T> List<T> append(List<T> values, T extra) {
        List<T> all = new ArrayList<>(values);
        all.add(extra);
        return all;
    }

    private static ItemProposeRequest rebuild(ItemProposeRequest r, LocalDate tripEnd, Integer durationMinutes,
            List<LockIn> locks, List<NeighbourItemIn> neighbours, Map<LocalDate, OpeningWindowIn> openingHours,
            ItemProposeRequest.RouteEvidence routeEvidence, List<TemporalCandidateIn> candidates) {
        return new ItemProposeRequest(r.evaluatedAt(), r.tripId(), r.tripVersion(), r.tripStart(), tripEnd,
                r.tripZone(), new io.nullnull.recommendation.domain.item.TargetItemIn(r.target().itemId(),
                        r.target().placeId(), r.target().date(), r.target().startTime(), durationMinutes,
                        r.target().position()),
                locks, neighbours, openingHours, routeEvidence, candidates);
    }

    private static ItemProposeRequest withCandidates(ItemProposeRequest r, List<TemporalCandidateIn> candidates) {
        return rebuild(r, r.tripEnd(), r.target().durationMinutes(), r.locks(), r.neighbours(), r.openingHours(),
                r.routeEvidence(), candidates);
    }

    private static ItemProposeRequest withLocks(ItemProposeRequest r, List<LockIn> locks) {
        return rebuild(r, r.tripEnd(), r.target().durationMinutes(), locks, r.neighbours(), r.openingHours(),
                r.routeEvidence(), r.candidates());
    }

    private static ItemProposeRequest withNeighbours(ItemProposeRequest r, List<NeighbourItemIn> neighbours,
            ItemProposeRequest.RouteEvidence routeEvidence) {
        return rebuild(r, r.tripEnd(), r.target().durationMinutes(), r.locks(), neighbours, r.openingHours(),
                routeEvidence, r.candidates());
    }

    private static ItemProposeRequest withOpeningWindow(ItemProposeRequest r, LocalDate date, OpeningWindowIn window) {
        Map<LocalDate, OpeningWindowIn> hours = new LinkedHashMap<>(r.openingHours());
        hours.put(date, window);
        return rebuild(r, r.tripEnd(), r.target().durationMinutes(), r.locks(), r.neighbours(), hours,
                r.routeEvidence(), r.candidates());
    }

    private static ItemProposeRequest withTripEnd(ItemProposeRequest r, LocalDate tripEnd) {
        return rebuild(r, tripEnd, r.target().durationMinutes(), r.locks(), r.neighbours(), r.openingHours(),
                r.routeEvidence(), r.candidates());
    }

    private static ItemProposeRequest withTargetDuration(ItemProposeRequest r, Integer durationMinutes) {
        return rebuild(r, r.tripEnd(), durationMinutes, r.locks(), r.neighbours(), r.openingHours(),
                r.routeEvidence(), r.candidates());
    }
}
