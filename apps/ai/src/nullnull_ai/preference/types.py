"""The bounded shape this service will READ as an interpreted preference (BA-084).

Not the shape a model emits. What a provider can produce is the provider's to decide and the
adapter's to parse, and the two must not be one definition: fused, a provider that answers in a new
shape would force this type to change, and the boundary of what we accept would move with it. The
adapter's job is to turn whatever arrived into the type below or to raise
`ProviderMalformedOutputError`; this package's job is to judge what it turned it into.

A model that writes preferences freely can invent an interest, a strength, or a whole taxonomy. So
prose is never read here: the only answer this service accepts is a subset of a closed set of codes
the caller supplied, each with an integer strength inside bounds the caller supplied.

**This package holds no vocabulary of its own, and that is the design, not an omission.** The canon
of the interest codes is the Figma chip list Frontend owns, published to servers as the contract's
`x-nullnull-interest-codes` extension and pinned on the Spring side by `InterestVocabularyContractTest`.
A third copy here would be the only one no test could compare against the contract, because the
purity rules forbid this package from reading `openapi.yaml` - or any file. So the vocabulary arrives
as an argument, every run, and a bound this service cannot see is a bound it cannot get wrong.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class PreferenceVocabulary:
    """What the caller permits for one interpretation: which codes exist and how strong they may be.

    `codes` is compared exactly. A model that answers `food` when the vocabulary says `FOOD` has not
    answered in the vocabulary, and normalising the case here would be this service deciding that two
    codes are the same - a judgement that belongs to whoever owns the list.
    """

    codes: frozenset[str]
    min_weight: int
    max_weight: int

    def __post_init__(self) -> None:
        if not self.codes:
            raise ValueError("a vocabulary with no codes permits nothing; the caller must supply the set")
        if any(not code.strip() for code in self.codes):
            raise ValueError("a vocabulary code must not be blank")
        if self.min_weight > self.max_weight:
            raise ValueError("min_weight must not exceed max_weight")


@dataclass(frozen=True, slots=True)
class BoundedPreference:
    """One interpreted preference: a code from the vocabulary and how strongly it applies."""

    code: str
    weight: int
