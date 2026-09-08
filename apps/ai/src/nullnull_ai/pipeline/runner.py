"""Deterministic pipeline runner.

Order of operations is fixed: sources in declared order, canonical dedup (first occurrence wins
after a stable sort by key), hydrators, filters that stop at the first non-eligible stage, one or
more independent scorers, a selector, then post-selection filters. Rejection counts per reason are
observable without exposing inputs.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Callable, Hashable, Sequence
from dataclasses import dataclass, field
from decimal import Decimal

from nullnull_ai.domain.types import Eligibility, EligibilityState, ScoreBreakdown
from nullnull_ai.pipeline.stages import Filter, Hydrator, Scored, Scorer, Selector, Source

DUPLICATE_CONFLICT = "DUPLICATE_CONFLICT"


@dataclass(frozen=True, slots=True)
class StageCount:
    stage: str
    input_count: int
    output_count: int


@dataclass(frozen=True, slots=True)
class PipelineResult[C]:
    selected: tuple[Scored[C], ...]
    evaluated: int
    rejected_by_reason: dict[str, int] = field(default_factory=dict)
    unknown_by_reason: dict[str, int] = field(default_factory=dict)
    stage_counts: tuple[StageCount, ...] = ()


class CandidatePipeline[Q, C, K: Hashable]:
    def __init__(
        self,
        *,
        name: str,
        key: Callable[[C], K],
        sources: Sequence[Source[Q, C]],
        hydrators: Sequence[Hydrator[Q, C]] = (),
        filters: Sequence[Filter[Q, C]] = (),
        scorers: Sequence[Scorer[Q, C]],
        selector: Selector[Q, C],
        post_filters: Sequence[Filter[Q, C]] = (),
    ) -> None:
        if not sources:
            raise ValueError("a pipeline needs at least one source")
        if not scorers:
            raise ValueError("a pipeline needs at least one scorer")
        self.name = name
        self._key = key
        self._sources = tuple(sources)
        self._hydrators = tuple(hydrators)
        self._filters = tuple(filters)
        self._scorers = tuple(scorers)
        self._selector = selector
        self._post_filters = tuple(post_filters)

    def run(self, query: Q) -> PipelineResult[C]:
        counts: list[StageCount] = []
        rejected: Counter[str] = Counter()
        unknown: Counter[str] = Counter()

        collected: list[C] = []
        for source in self._sources:
            fetched = list(source.fetch(query))
            counts.append(StageCount(f"source:{source.name}", 0, len(fetched)))
            collected.extend(fetched)

        deduped = self._dedup(collected, rejected)
        counts.append(StageCount("dedup", len(collected), len(deduped)))

        candidates: Sequence[C] = deduped
        for hydrator in self._hydrators:
            hydrated = list(hydrator.hydrate(query, candidates))
            counts.append(StageCount(f"hydrator:{hydrator.name}", len(candidates), len(hydrated)))
            candidates = hydrated

        passing: list[C] = []
        for candidate in candidates:
            verdict = self._first_block(query, candidate, self._filters)
            if verdict is None:
                passing.append(candidate)
            else:
                self._count(verdict, rejected, unknown)
        counts.append(StageCount("filters", len(candidates), len(passing)))

        scored = [Scored(candidate, self._combine(query, candidate)) for candidate in passing]
        counts.append(StageCount("scorers", len(passing), len(scored)))

        selected = list(self._selector.select(query, scored))
        counts.append(StageCount(f"selector:{self._selector.name}", len(scored), len(selected)))

        final: list[Scored[C]] = []
        for item in selected:
            verdict = self._first_block(query, item.candidate, self._post_filters)
            if verdict is None:
                final.append(item)
            else:
                self._count(verdict, rejected, unknown)
        counts.append(StageCount("post_filters", len(selected), len(final)))

        return PipelineResult(
            selected=tuple(final),
            evaluated=len(candidates),
            rejected_by_reason=dict(sorted(rejected.items())),
            unknown_by_reason=dict(sorted(unknown.items())),
            stage_counts=tuple(counts),
        )

    def _dedup(self, candidates: Sequence[C], rejected: Counter[str]) -> list[C]:
        """Identical duplicates collapse to one; duplicates with different payloads are quarantined
        (§3.1 dedup: conflicts are isolated, never resolved by arrival order)."""
        groups: dict[str, list[C]] = {}
        for candidate in candidates:
            groups.setdefault(self._sort_key(candidate), []).append(candidate)
        unique: list[C] = []
        for key in sorted(groups):
            members = groups[key]
            if all(member == members[0] for member in members):
                unique.append(members[0])
            else:
                rejected[DUPLICATE_CONFLICT] += 1
        return unique

    def _sort_key(self, candidate: C) -> str:
        return repr(self._key(candidate))

    @staticmethod
    def _first_block(query: Q, candidate: C, filters: Sequence[Filter[Q, C]]) -> Eligibility | None:
        for stage in filters:
            verdict = stage.evaluate(query, candidate)
            if not verdict.is_eligible:
                return verdict
        return None

    def _combine(self, query: Q, candidate: C) -> ScoreBreakdown:
        total = Decimal(0)
        contributions: list[tuple[str, Decimal]] = []
        for scorer in self._scorers:
            breakdown = scorer.score(query, candidate)
            total += breakdown.score
            contributions.append((scorer.name, breakdown.score))
            contributions.extend((f"{scorer.name}.{name}", value) for name, value in breakdown.contributions)
        return ScoreBreakdown(score=total, contributions=tuple(contributions))

    @staticmethod
    def _count(verdict: Eligibility, rejected: Counter[str], unknown: Counter[str]) -> None:
        bucket = unknown if verdict.state is EligibilityState.UNKNOWN else rejected
        bucket[verdict.reasons[0].code] += 1
