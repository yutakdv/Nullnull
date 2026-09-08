"""Stage types of the candidate pipeline, following the structure of xai-org/x-algorithm
candidate-pipeline (commit 902a06f, structure only; no code or model is reused):
source -> hydrator -> filter -> scorer -> selector -> post-selection filter.

Stages are pure callables over immutable candidates. A scorer must be independent per candidate:
the score of one candidate never depends on which other candidates are present (§6).
"""

from __future__ import annotations

from collections.abc import Hashable, Sequence
from dataclasses import dataclass
from typing import Protocol, TypeVar

from nullnull_ai.domain.types import Eligibility, ScoreBreakdown

C = TypeVar("C")  # invariant: appears in both parameter and return positions
Q_contra = TypeVar("Q_contra", contravariant=True)
C_contra = TypeVar("C_contra", contravariant=True)
C_co = TypeVar("C_co", covariant=True)
K_co = TypeVar("K_co", bound=Hashable, covariant=True)


class Source(Protocol[Q_contra, C_co]):
    name: str

    def fetch(self, query: Q_contra) -> Sequence[C_co]: ...


class Hydrator(Protocol[Q_contra, C]):
    name: str

    def hydrate(self, query: Q_contra, candidates: Sequence[C]) -> Sequence[C]: ...


class Filter(Protocol[Q_contra, C_contra]):
    name: str

    def evaluate(self, query: Q_contra, candidate: C_contra) -> Eligibility: ...


class Scorer(Protocol[Q_contra, C_contra]):
    name: str

    def score(self, query: Q_contra, candidate: C_contra) -> ScoreBreakdown: ...


@dataclass(frozen=True, slots=True)
class Scored[C]:
    candidate: C
    breakdown: ScoreBreakdown


class Selector(Protocol[Q_contra, C]):
    name: str

    def select(self, query: Q_contra, scored: Sequence[Scored[C]]) -> Sequence[Scored[C]]: ...


class KeyFunction(Protocol[C_contra, K_co]):
    def __call__(self, candidate: C_contra) -> K_co: ...
