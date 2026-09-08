package io.nullnull.trip.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.LockIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.recommendation.testsupport.ItemFixtureLoader;
import io.nullnull.recommendation.testsupport.ItemFixtureLoader.ItemFixture;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REC-SLOT-01 four independent locks. The fixture cases are the ones {@code apps/ai} evaluates
 * (apps/ai/tests/recommendation/fixtures), so a lock rule that drifts between the two languages
 * fails here rather than in production.
 */
@DisplayName("REC-SLOT-01 four independent locks")
class LockChecksTest {

    /** apps/ai/tests/recommendation/manifest.json randomSeeds[0]. */
    static final long SEED = 20260906L;

    static final LocalDate D12 = LocalDate.of(2026, 9, 12);
    static final LocalDate D13 = LocalDate.of(2026, 9, 13);

    static List<ItemFixture> fixtures;

    @BeforeAll
    static void loadFixtures() {
        fixtures = ItemFixtureLoader.loadItemFixtures();
        assertThat(fixtures).as("ITEM fixtures listed in apps/ai/tests/recommendation/manifest.json").isNotEmpty();
    }

    @Test
    void everyLockFixtureRejectsTheSameCandidatesForTheSameReasons() {
        List<String> checked = new ArrayList<>();
        for (ItemFixture fixture : fixtures) {
            List<ItemLock> locks = fixture.request().locks().stream().map(LockIn::toItemLock).toList();
            TargetItemIn target = fixture.request().target();
            if (fixture.expected().outcome() != ItemProposeResponse.Outcome.LOCK_CONFLICT) {
                continue;
            }
            Map<String, Integer> firstReasons = new TreeMap<>();
            for (TemporalCandidateIn candidate : fixture.request().candidates()) {
                LocalTime at = candidate.effectiveStartTime(target.startTime());
                LockChecks.Result result =
                        LockChecks.evaluate(locks, candidate.date(), at, target.durationMinutes());
                assertThat(result.satisfied())
                        .as("%s: candidate %s %s must be blocked by a lock", fixture.id(), candidate.date(), at)
                        .isFalse();
                firstReasons.merge(result.reasonCodes().get(0), 1, Integer::sum);
            }
            assertThat(firstReasons).as("%s first lock reason per candidate", fixture.id())
                    .isEqualTo(new TreeMap<>(fixture.expected().rejectedByReason()));
            checked.add(fixture.id());
        }
        assertThat(checked).as("LOCK_CONFLICT fixtures evaluated").isNotEmpty();
    }

    @Test
    void everyProposedSlotOfEveryFixtureSatisfiesTheLocks() {
        List<String> checked = new ArrayList<>();
        for (ItemFixture fixture : fixtures) {
            if (!fixture.expected().expectProposals()) {
                continue;
            }
            List<ItemLock> locks = fixture.request().locks().stream().map(LockIn::toItemLock).toList();
            TargetItemIn target = fixture.request().target();
            for (ItemFixtureLoader.ExpectedProposal proposal : fixture.expected().proposals()) {
                assertThat(LockChecks.evaluate(locks, proposal.date(), proposal.startTime(),
                        target.durationMinutes()).satisfied())
                        .as("%s: proposed slot %s %s must satisfy every lock", fixture.id(), proposal.date(),
                                proposal.startTime())
                        .isTrue();
            }
            checked.add(fixture.id());
        }
        assertThat(checked).as("fixtures with proposals evaluated").isNotEmpty();
    }

    @Test
    void noLocksPassesAndReportsNothing() {
        LockChecks.Result result = LockChecks.evaluate(List.of(), D13, LocalTime.of(10, 0), 60);
        assertThat(result.satisfied()).isTrue();
        assertThat(result.passed()).isEmpty();
        assertThat(result.reasonCodes()).isEmpty();
    }

    @Test
    void mustVisitAlwaysPassesForATemporalMove() {
        LockChecks.Result result = LockChecks.evaluate(List.of(new ItemLock.MustVisit()), D13, null, null);
        assertThat(result.satisfied()).isTrue();
        assertThat(result.passed()).containsEntry(LockType.MUST_VISIT, true);
    }

    @Test
    void dateLockBlocksOtherDatesOnly() {
        List<ItemLock> locks = List.of(new ItemLock.Date(D12));
        assertThat(LockChecks.evaluate(locks, D13, null, 60).reasonCodes()).containsExactly(LockChecks.DATE_LOCKED);
        assertThat(LockChecks.evaluate(locks, D12, LocalTime.of(15, 0), 60).satisfied()).isTrue();
    }

    @Test
    void timeLockAllowsWithinToleranceAndRejectsUnknownTime() {
        List<ItemLock> locks = List.of(new ItemLock.Time(LocalTime.of(10, 0), 30));
        assertThat(LockChecks.evaluate(locks, D13, LocalTime.of(10, 30), 60).satisfied()).isTrue();
        assertThat(LockChecks.evaluate(locks, D13, LocalTime.of(9, 30), 60).satisfied())
                .as("the tolerance is symmetric around the locked time").isTrue();
        assertThat(LockChecks.evaluate(locks, D13, LocalTime.of(10, 31), 60).reasonCodes())
                .containsExactly(LockChecks.TIME_LOCKED);
        assertThat(LockChecks.evaluate(locks, D13, null, 60).reasonCodes()).containsExactly(LockChecks.TIME_LOCKED);
    }

    @Test
    void reservationPinsDateAndStartTimeAndTheStayMustFitTheWindow() {
        List<ItemLock> locks = List.of(new ItemLock.Reservation(D12, LocalTime.of(18, 0), LocalTime.of(20, 0)));
        assertThat(LockChecks.evaluate(locks, D12, LocalTime.of(18, 0), 90).satisfied()).isTrue();
        assertThat(LockChecks.evaluate(locks, D12, LocalTime.of(19, 0), 30).reasonCodes())
                .as("reservation start is pinned (D-REC-8)").containsExactly(LockChecks.RESERVATION_LOCKED);
        assertThat(LockChecks.evaluate(locks, D12, LocalTime.of(18, 0), 150).reasonCodes())
                .as("stay ends after the reservation window").containsExactly(LockChecks.RESERVATION_LOCKED);
        assertThat(LockChecks.evaluate(locks, D12, LocalTime.of(18, 0), null).satisfied())
                .as("unknown duration is judged by the opening/duration filter, not by the lock").isTrue();
        assertThat(LockChecks.evaluate(locks, D13, LocalTime.of(18, 0), 30).reasonCodes())
                .containsExactly(LockChecks.RESERVATION_LOCKED);
    }

    @Test
    void aStayThatWrapsPastMidnightNeverFitsAReservationWindow() {
        List<ItemLock> locks = List.of(new ItemLock.Reservation(D12, LocalTime.of(23, 0), LocalTime.of(23, 30)));
        assertThat(LockChecks.evaluate(locks, D12, LocalTime.of(23, 0), 120).reasonCodes())
                .containsExactly(LockChecks.RESERVATION_LOCKED);
    }

    @Test
    void twoBrokenLocksAlwaysReportTheFixedOrderFirstAndNotTheCallersOrder() {
        ItemLock date = new ItemLock.Date(D12);
        ItemLock reservation = new ItemLock.Reservation(D12, LocalTime.of(10, 0), LocalTime.of(11, 30));
        LockChecks.Result stored = LockChecks.evaluate(List.of(reservation, date), D13, LocalTime.of(10, 0), 90);
        LockChecks.Result reversed = LockChecks.evaluate(List.of(date, reservation), D13, LocalTime.of(10, 0), 90);
        assertThat(stored.reasonCodes())
                .containsExactly(LockChecks.DATE_LOCKED, LockChecks.RESERVATION_LOCKED);
        assertThat(reversed).isEqualTo(stored);
    }

    @Test
    void twoLocksOfOneTypeAreARejectedInputRatherThanAnAmbiguousVerdict() {
        List<ItemLock> duplicated = List.of(new ItemLock.Date(D12), new ItemLock.Date(D13));
        assertThatThrownBy(() -> LockChecks.evaluate(duplicated, D12, LocalTime.of(10, 0), 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at most one lock per type");
    }

    @Test
    void locksAreIndependentAndOrderFreeForAThousandSeededCases() {
        Random rng = new Random(SEED);
        for (int index = 0; index < 1_000; index++) {
            List<ItemLock> locks = randomLocks(rng);
            LocalDate proposedDate = D12.plusDays(rng.nextInt(5) - 1);
            LocalTime proposedTime = rng.nextInt(4) == 0 ? null : LocalTime.of(rng.nextInt(24), rng.nextInt(60));
            Integer duration = rng.nextInt(4) == 0 ? null : 15 + rng.nextInt(586);
            String where = "seed=" + SEED + " case=" + index + " locks=" + locks + " date=" + proposedDate
                    + " time=" + proposedTime + " duration=" + duration;

            LockChecks.Result result = LockChecks.evaluate(locks, proposedDate, proposedTime, duration);

            List<ItemLock> shuffled = new ArrayList<>(locks);
            Collections.shuffle(shuffled, rng);
            assertThat(LockChecks.evaluate(shuffled, proposedDate, proposedTime, duration))
                    .as("%s: the caller's lock order never changes the result", where).isEqualTo(result);

            for (ItemLock lock : locks) {
                assertThat(LockChecks.evaluate(List.of(lock), proposedDate, proposedTime, duration)
                        .passed().get(lock.type()))
                        .as("%s: %s is judged by its own rule alone", where, lock.type())
                        .isEqualTo(result.passed().get(lock.type()));
            }

            List<String> expectedReasons = new ArrayList<>();
            for (LockType type : LockType.values()) {
                if (Boolean.FALSE.equals(result.passed().get(type))) {
                    expectedReasons.add(LockChecks.reasonCodeOf(type));
                }
            }
            assertThat(result.reasonCodes()).as("%s: failing locks in the fixed order", where)
                    .isEqualTo(expectedReasons);
            assertThat(result.satisfied()).as("%s: satisfied means nothing failed", where)
                    .isEqualTo(expectedReasons.isEmpty());
        }
    }

    private static List<ItemLock> randomLocks(Random rng) {
        List<ItemLock> locks = new ArrayList<>();
        if (rng.nextBoolean()) {
            locks.add(new ItemLock.MustVisit());
        }
        if (rng.nextBoolean()) {
            locks.add(new ItemLock.Date(D12.plusDays(rng.nextInt(4))));
        }
        if (rng.nextBoolean()) {
            locks.add(new ItemLock.Time(LocalTime.of(rng.nextInt(24), rng.nextInt(60)), rng.nextInt(181)));
        }
        if (rng.nextBoolean()) {
            int startMinute = rng.nextInt(20 * 60);
            int endMinute = Math.min(1439, startMinute + 30 + rng.nextInt(180));
            locks.add(new ItemLock.Reservation(D12.plusDays(rng.nextInt(4)),
                    LocalTime.ofSecondOfDay(startMinute * 60L),
                    rng.nextBoolean() ? null : LocalTime.ofSecondOfDay(endMinute * 60L)));
        }
        Collections.shuffle(locks, rng);
        return List.copyOf(locks);
    }
}
