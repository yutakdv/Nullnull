from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta, timezone
from decimal import Decimal
from uuid import UUID

import pytest

from nullnull_ai.domain.types import (
    CandidateKey,
    Eligibility,
    EligibilityState,
    Reason,
    RecommendationContext,
    ScoreBreakdown,
)

PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")


def test_unknown_is_never_promoted_and_ineligible_dominates() -> None:
    unknown = Eligibility.unknown(Reason("OPENING_HOURS_UNKNOWN", "unverified"))
    ineligible = Eligibility.ineligible(Reason("DATE_LOCKED", "date lock"))
    assert Eligibility.eligible().and_(unknown).state is EligibilityState.UNKNOWN
    assert unknown.and_(ineligible).state is EligibilityState.INELIGIBLE
    assert unknown.and_(ineligible).reasons == unknown.reasons + ineligible.reasons
    with pytest.raises(ValueError):
        Eligibility(EligibilityState.UNKNOWN)


def test_reason_codes_are_stable_upper_snake_case() -> None:
    with pytest.raises(ValueError):
        Reason("dateLocked", "x")
    with pytest.raises(ValueError):
        Reason("DATE_LOCKED", "")


def test_score_breakdown_rejects_non_finite() -> None:
    with pytest.raises(ValueError):
        ScoreBreakdown(Decimal("NaN"))
    with pytest.raises(ValueError):
        ScoreBreakdown(Decimal(1), (("x", Decimal("Infinity")),))


def test_candidate_key_requires_date_for_time() -> None:
    with pytest.raises(ValueError):
        CandidateKey(PLACE, None, time(10, 0))
    assert CandidateKey(PLACE, date(2026, 9, 12), time(10, 0)) == CandidateKey(PLACE, date(2026, 9, 12), time(10, 0))


def test_context_normalizes_to_utc_and_rejects_naive_datetimes() -> None:
    seoul = timezone(timedelta(hours=9))
    context = RecommendationContext(datetime(2026, 9, 12, 9, 0, tzinfo=seoul), "policy-v1", "a" * 64, "catalog-1")
    assert context.evaluated_at == datetime(2026, 9, 12, 0, 0, tzinfo=UTC)
    with pytest.raises(ValueError):
        RecommendationContext(datetime(2026, 9, 12, 9, 0), "policy-v1", "a" * 64, "catalog-1")
    with pytest.raises(ValueError):
        RecommendationContext(datetime(2026, 9, 12, 9, 0, tzinfo=UTC), "", "a" * 64, "catalog-1")
