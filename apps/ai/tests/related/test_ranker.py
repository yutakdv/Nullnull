"""REC-REL-01/02: verified relations only - merge, order, expire, quarantine (§5.2).

Ported from the Java plan's `RelatedPlaceRankerTest`: the rules and the expected values are the same.
No crowd, popularity or exposure signal takes part in the ordering, and nothing is inferred from a
missing fact - a place without a comparable category ranks after the ones that have one, never at 0.
"""

from __future__ import annotations

import json
import random
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from uuid import UUID

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.domain.types import Reason, RecommendationContext
from nullnull_ai.related.ranker import (
    LookupOutcome,
    MappingCertainty,
    PlaceCategory,
    RankedRelated,
    RelatedPlaceRanker,
    RelationCandidate,
    RelationState,
    RelationTier,
)

SRC = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
T1 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a11")
T2 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a12")
T3 = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a13")
NOW = datetime(2026, 9, 6, tzinfo=UTC)
TAXONOMY = "taxonomy-test-1"

POLICY = load_default()
CONTEXT = RecommendationContext(NOW, POLICY.version, POLICY.hash, "catalog-test-1")
SRC_CATEGORY = PlaceCategory(SRC, "PALACE", "HERITAGE", TAXONOMY)
RANKER = RelatedPlaceRanker(POLICY)

_MANIFEST = json.loads(
    (Path(__file__).resolve().parents[1] / "recommendation" / "manifest.json").read_text(encoding="utf-8")
)
SEED: int = _MANIFEST["randomSeeds"][0]
"""The property case below reruns one input 1,000 times; the seed is the manifest's, so a failure replays."""


def rel(
    target: UUID,
    tier: RelationTier,
    channel: str,
    expires_at: datetime | None = None,
    mapping: MappingCertainty = MappingCertainty.CERTAIN,
    effective_at: datetime = NOW - timedelta(days=1),
) -> RelationCandidate:
    return RelationCandidate(
        source_place_id=SRC,
        target_place_id=target,
        tier=tier,
        source_code="KTO_RELATED_PLACES",
        channel=channel,
        confidence=Decimal("0.7"),
        effective_at=effective_at,
        expires_at=expires_at,
        mapping=mapping,
    )


def categories(first: str | None, second: str | None, third: str | None) -> dict[UUID, PlaceCategory]:
    return {
        T1: PlaceCategory(T1, first, "HERITAGE", TAXONOMY),
        T2: PlaceCategory(T2, second, None if second is None else "HERITAGE", TAXONOMY),
        T3: PlaceCategory(T3, third, "FOOD", TAXONOMY),
    }


def codes(reasons: tuple[Reason, ...]) -> set[str]:
    return {reason.code for reason in reasons}


def test_a_ranked_place_always_keeps_its_evidence() -> None:
    """The item exists because evidence survived; an empty one would claim a relation with no source."""
    with pytest.raises(ValueError, match="at least one evidence row"):
        RankedRelated(T1, RelationTier.EXACT, None, ())


def test_the_row_order_relies_on_the_enum_value_order() -> None:
    """`(tier, placeId)` sorts on the tier's own value, so EXACT must precede SIMILAR as a string."""
    assert RelationTier.EXACT.value < RelationTier.SIMILAR.value


def test_merges_canonical_duplicates_keeps_evidence_and_orders_by_tier_category_place() -> None:
    candidates = [
        rel(T3, RelationTier.SIMILAR, "category"),
        rel(T1, RelationTier.SIMILAR, "category"),
        rel(T1, RelationTier.EXACT, "kto-direct"),
        rel(T2, RelationTier.EXACT, "kto-direct"),
    ]
    result = RANKER.rank(
        CONTEXT, SRC, SRC_CATEGORY, candidates, categories("PALACE", None, "RESTAURANT"), LookupOutcome.COMPLETE
    )
    assert result.state is RelationState.EXACT
    assert [item.place_id for item in result.items] == [T1, T2, T3]
    assert result.items[0].tier is RelationTier.EXACT
    assert len(result.items[0].evidence) == 2
    assert result.items[0].category_match == Decimal(1)
    assert result.items[1].category_match is None, "missing category sorts after known within tier"
    assert result.items[2].category_match == Decimal(0)


def test_parent_category_scores_half() -> None:
    result = RANKER.rank(
        CONTEXT,
        SRC,
        SRC_CATEGORY,
        [rel(T1, RelationTier.SIMILAR, "category")],
        {T1: PlaceCategory(T1, "MUSEUM", "HERITAGE", TAXONOMY)},
        LookupOutcome.COMPLETE,
    )
    assert result.state is RelationState.SIMILAR
    assert result.items[0].category_match == Decimal("0.5")


def test_a_higher_category_match_outranks_a_lower_one_inside_one_tier() -> None:
    """The three rows share a tier, so only `categoryMatch DESC, missing last` can order them.

    `placeId ASC` would answer T1, T2, T3: the expected order is the opposite for the two known
    matches, so an ordering that compared the wrong way round (or ignored the match) fails here.
    """
    candidates = [
        rel(T1, RelationTier.SIMILAR, "category"),
        rel(T2, RelationTier.SIMILAR, "category"),
        rel(T3, RelationTier.SIMILAR, "category"),
    ]
    result = RANKER.rank(
        CONTEXT,
        SRC,
        SRC_CATEGORY,
        candidates,
        {T1: PlaceCategory(T1, "MUSEUM", "HERITAGE", TAXONOMY), T2: PlaceCategory(T2, "PALACE", "HERITAGE", TAXONOMY)},
        LookupOutcome.COMPLETE,
    )
    assert [item.place_id for item in result.items] == [T2, T1, T3]
    assert [item.category_match for item in result.items] == [Decimal(1), Decimal("0.5"), None]


def test_a_category_from_another_taxonomy_version_is_not_compared() -> None:
    result = RANKER.rank(
        CONTEXT,
        SRC,
        SRC_CATEGORY,
        [rel(T1, RelationTier.SIMILAR, "category")],
        {T1: PlaceCategory(T1, "PALACE", "HERITAGE", "taxonomy-test-2")},
        LookupOutcome.COMPLETE,
    )
    assert result.items[0].category_match is None
    assert codes(result.reasons) == {"TAXONOMY_MISMATCH"}


def test_expired_evidence_is_dropped_and_uncertain_mapping_is_quarantined() -> None:
    candidates = [
        rel(T1, RelationTier.EXACT, "kto-direct", NOW - timedelta(seconds=1)),
        rel(T2, RelationTier.EXACT, "kto-direct", None, MappingCertainty.UNCERTAIN),
    ]
    result = RANKER.rank(
        CONTEXT, SRC, SRC_CATEGORY, candidates, categories("PALACE", "PALACE", "X"), LookupOutcome.COMPLETE
    )
    assert result.items == ()
    assert result.state is RelationState.UNKNOWN, "uncertain mapping means we cannot judge"
    assert {"EVIDENCE_EXPIRED", "MAPPING_UNCERTAIN"} <= codes(result.reasons)

    only_expired = RANKER.rank(
        CONTEXT, SRC, SRC_CATEGORY, [candidates[0]], categories("PALACE", "PALACE", "X"), LookupOutcome.COMPLETE
    )
    assert only_expired.state is RelationState.NONE


def test_evidence_that_is_not_effective_yet_is_dropped_too() -> None:
    future = rel(T1, RelationTier.EXACT, "kto-direct", effective_at=NOW + timedelta(seconds=1))
    result = RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, [future], {}, LookupOutcome.COMPLETE)
    assert result.items == () and result.state is RelationState.NONE
    assert codes(result.reasons) == {"EVIDENCE_EXPIRED"}


def test_the_evidence_window_is_half_open_at_the_evaluated_instant() -> None:
    """`[effective_at, expires_at)`: a row that starts exactly now counts, one that ends now does not."""
    expiring = RANKER.rank(
        CONTEXT,
        SRC,
        SRC_CATEGORY,
        [rel(T1, RelationTier.EXACT, "kto-direct", expires_at=NOW)],
        {},
        LookupOutcome.COMPLETE,
    )
    assert expiring.items == () and codes(expiring.reasons) == {"EVIDENCE_EXPIRED"}

    starting = RANKER.rank(
        CONTEXT,
        SRC,
        SRC_CATEGORY,
        [rel(T1, RelationTier.EXACT, "kto-direct", effective_at=NOW)],
        {},
        LookupOutcome.COMPLETE,
    )
    assert [item.place_id for item in starting.items] == [T1] and starting.reasons == ()


def test_lookup_outcome_drives_checking_unknown_and_none() -> None:
    assert RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, [], {}, LookupOutcome.COMPLETE).state is RelationState.NONE
    assert RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, [], {}, LookupOutcome.SOURCE_FAILED).state is RelationState.UNKNOWN
    assert RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, [], {}, LookupOutcome.JOB_RUNNING).state is RelationState.CHECKING


def test_input_order_does_not_change_the_result() -> None:
    base = [
        rel(
            UUID(int=(0x018F3F8E9B677A21 << 64) | (0x8D3131D315B90000 + index)),
            RelationTier.EXACT if index % 3 == 0 else RelationTier.SIMILAR,
            f"ch{index % 4}",
        )
        for index in range(40)
    ]
    cats = {
        candidate.target_place_id: PlaceCategory(candidate.target_place_id, "PALACE", "HERITAGE", TAXONOMY)
        for candidate in base
    }
    ranked = RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, base, cats, LookupOutcome.COMPLETE)
    expected = [item.place_id for item in ranked.items]
    assert len(expected) == 40
    rng = random.Random(SEED)
    for _ in range(1_000):
        shuffled = list(base)
        rng.shuffle(shuffled)
        result = RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, shuffled, cats, LookupOutcome.COMPLETE)
        assert [item.place_id for item in result.items] == expected


def test_self_reference_and_channel_caps_are_applied() -> None:
    per_channel = POLICY.candidate_caps.related_per_channel
    candidates = [rel(SRC, RelationTier.EXACT, "kto-direct")]
    candidates += [
        rel(UUID(int=(1 << 64) | index), RelationTier.SIMILAR, "category") for index in range(per_channel + 10)
    ]
    result = RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, candidates, {}, LookupOutcome.COMPLETE)
    assert len(result.items) == per_channel
    assert SRC not in {item.place_id for item in result.items}
    assert {"SELF_REFERENCE", "CHANNEL_CAP_EXCEEDED"} <= codes(result.reasons)
    assert result.state is RelationState.SIMILAR


def test_the_merged_cap_truncates_and_reports() -> None:
    """policy-v1 candidateCaps.relatedMerged = 300: four full channels merge to 400 distinct places."""
    merged_cap = POLICY.candidate_caps.related_merged
    per_channel = POLICY.candidate_caps.related_per_channel
    candidates = [
        rel(UUID(int=(2 << 64) | (channel * 1000 + index)), RelationTier.SIMILAR, f"ch{channel}")
        for channel in range(4)
        for index in range(per_channel)
    ]
    result = RANKER.rank(CONTEXT, SRC, SRC_CATEGORY, candidates, {}, LookupOutcome.COMPLETE)
    assert len(result.items) == merged_cap
    assert codes(result.reasons) == {"MERGED_CAP_EXCEEDED"}
