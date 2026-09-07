from __future__ import annotations

import random
from datetime import date, time
from decimal import Decimal
from uuid import UUID

from nullnull_ai.domain.types import CandidateKey, ScoreBreakdown
from nullnull_ai.item.score import Admitted, ScoredCandidate, proposal_sort_key

P1 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
P2 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a02")
SNAP_A = UUID("018f3f8e-9b67-7a21-8d31-31d315b9d701")
SNAP_B = UUID("018f3f8e-9b67-7a21-8d31-31d315b9d702")
SNAP_C = UUID("018f3f8e-9b67-7a21-8d31-31d315b9d703")


def scored(
    place: UUID,
    day: str,
    at: time | None,
    score: str,
    cost: str,
    before: UUID = SNAP_A,
    after: UUID = SNAP_B,
) -> ScoredCandidate:
    admitted = Admitted(ScoreBreakdown(Decimal(score)), Decimal(10), Decimal(1), Decimal(cost))
    return ScoredCandidate(CandidateKey(place, date.fromisoformat(day), at), at, admitted, before, after)


def test_orders_by_score_cost_date_time_place() -> None:
    """Every comparator gets a discriminating pair; the 09-11 row is the one only `date ASC` separates."""
    expected = [
        scored(P1, "2026-09-13", time(10, 0), "0.140000", "0.500000"),
        scored(P1, "2026-09-13", time(10, 0), "0.110000", "0.250000"),
        scored(P1, "2026-09-13", time(10, 0), "0.110000", "0.300000"),
        scored(P1, "2026-09-11", time(9, 0), "0.100000", "0.250000"),
        scored(P1, "2026-09-12", None, "0.100000", "0.250000"),
        scored(P1, "2026-09-12", time(9, 0), "0.100000", "0.250000"),
        scored(P2, "2026-09-12", time(9, 0), "0.100000", "0.250000"),
    ]
    assert len({proposal_sort_key(candidate) for candidate in expected}) == len(expected), "not a strict total order"

    shuffled = list(expected)
    random.Random(20260906).shuffle(shuffled)
    assert shuffled != expected
    # Both directions: the reversed input is what forces `changeCost ASC` and `placeId ASC` to do the
    # work, since the seeded shuffle happens to leave those two tied pairs already in order.
    assert sorted(shuffled, key=proposal_sort_key) == expected
    assert sorted(reversed(shuffled), key=proposal_sort_key) == expected


def test_candidates_identical_in_every_spec_key_are_separated_by_their_snapshot_pair() -> None:
    """The five spec keys are all equal here, so only the snapshot pair can order these three.

    Without a last key the sort is merely stable, and the winner of a merged slot would depend on the
    order the caller happened to send the pairs in.
    """
    expected = [
        scored(P1, "2026-09-12", time(9, 0), "0.100000", "0.250000", SNAP_A, SNAP_B),
        scored(P1, "2026-09-12", time(9, 0), "0.100000", "0.250000", SNAP_A, SNAP_C),
        scored(P1, "2026-09-12", time(9, 0), "0.100000", "0.250000", SNAP_B, SNAP_A),
    ]
    assert len({proposal_sort_key(candidate) for candidate in expected}) == len(expected), "not a strict total order"

    shuffled = list(expected)
    random.Random(20260906).shuffle(shuffled)
    assert sorted(shuffled, key=proposal_sort_key) == expected
    assert sorted(reversed(shuffled), key=proposal_sort_key) == expected
