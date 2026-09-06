from __future__ import annotations

import random
from datetime import date, time
from decimal import Decimal
from uuid import UUID

from nullnull_ai.domain.types import CandidateKey, ScoreBreakdown
from nullnull_ai.item.score import Admitted, ScoredCandidate, proposal_sort_key

P1 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
P2 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a02")


def scored(place: UUID, day: str, at: time | None, score: str, cost: str) -> ScoredCandidate:
    admitted = Admitted(ScoreBreakdown(Decimal(score)), Decimal(10), Decimal(1), Decimal(cost))
    return ScoredCandidate(CandidateKey(place, date.fromisoformat(day), at), at, admitted)


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
