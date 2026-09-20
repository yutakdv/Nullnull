"""REC-LLM-05: an interpreted preference is read only when the caller's vocabulary can say it.

The vocabulary in this module is deliberately NOT the product's interest codes. The real canon is
the Figma chip list Frontend owns; if the corpus repeated it, a validator that consulted a hardcoded
copy of that list would pass every case here and nobody would notice until the list grew. Codes
nothing else in the system uses, and weight bounds that are not the contract's 1..5, are what make
`test_the_same_selection_is_read_against_the_vocabulary_it_was_given` able to fail.
"""

from __future__ import annotations

import pytest

from nullnull_ai.preference.types import BoundedPreference, PreferenceVocabulary
from nullnull_ai.preference.validator import accepts

VOCABULARY = PreferenceVocabulary(codes=frozenset({"AURORA", "BASALT", "CINNABAR"}), min_weight=2, max_weight=4)


def test_a_subset_with_weights_inside_the_bounds_is_read() -> None:
    assert accepts(VOCABULARY, [BoundedPreference("AURORA", 2), BoundedPreference("CINNABAR", 4)])


def test_every_code_at_once_is_still_a_subset() -> None:
    assert accepts(VOCABULARY, [BoundedPreference(code, 3) for code in sorted(VOCABULARY.codes)])


@pytest.mark.parametrize(
    ("selection", "note"),
    [
        ([BoundedPreference("DIORITE", 3)], "a code the vocabulary never offered"),
        ([BoundedPreference("aurora", 3)], "the same code in another case; nothing here normalises"),
        ([BoundedPreference(" AURORA", 3)], "padded with whitespace, which is not the code either"),
        ([BoundedPreference("AURORA", 3), BoundedPreference("AURORA", 4)], "one code claimed twice"),
        ([BoundedPreference("AURORA", 1)], "a weight below the caller's floor"),
        ([BoundedPreference("AURORA", 5)], "a weight above the caller's ceiling"),
        ([BoundedPreference("AURORA", 0)], "no strength at all, which the bounds do not permit"),
        ([BoundedPreference("AURORA", 3), BoundedPreference("DIORITE", 3)], "one good item does not carry a bad one"),
    ],
    ids=lambda value: value if isinstance(value, str) else "",
)
def test_a_selection_the_vocabulary_cannot_say_is_refused(selection: list[BoundedPreference], note: str) -> None:
    assert not accepts(VOCABULARY, selection), note


def test_a_boolean_is_not_a_weight() -> None:
    """`True` is an `int` in Python: without the explicit check it would pass as the weight 1."""
    permissive = PreferenceVocabulary(codes=frozenset({"AURORA"}), min_weight=0, max_weight=4)
    assert not accepts(permissive, [BoundedPreference("AURORA", True)])  # type: ignore[arg-type]


@pytest.mark.parametrize("selection", [None, []], ids=["missing", "empty"])
def test_an_empty_interpretation_is_refused_rather_than_read_as_no_preferences(
    selection: list[BoundedPreference] | None,
) -> None:
    """A model that interpreted nothing and a traveller who chose nothing are different states."""
    assert not accepts(VOCABULARY, selection)


def test_the_same_selection_is_read_against_the_vocabulary_it_was_given() -> None:
    """The guard against a second canon: this package may not know any code list of its own.

    A validator that consulted a built-in allowlist would answer the same way for both vocabularies
    below. Reading the one it was handed is what makes the two answers differ.
    """
    selection = [BoundedPreference("DIORITE", 7)]
    narrow = VOCABULARY
    other = PreferenceVocabulary(codes=frozenset({"DIORITE", "GNEISS"}), min_weight=5, max_weight=9)
    assert not accepts(narrow, selection)
    assert accepts(other, selection)


@pytest.mark.parametrize(
    ("codes", "low", "high", "message"),
    [
        (frozenset[str](), 1, 5, "permits nothing"),
        (frozenset({"AURORA", "  "}), 1, 5, "must not be blank"),
        (frozenset({"AURORA"}), 5, 1, "must not exceed"),
    ],
    ids=["no codes", "blank code", "inverted bounds"],
)
def test_a_vocabulary_that_bounds_nothing_is_refused_at_construction(
    codes: frozenset[str], low: int, high: int, message: str
) -> None:
    """An empty or inverted vocabulary would make every later answer vacuous, one way or the other."""
    with pytest.raises(ValueError, match=message):
        PreferenceVocabulary(codes=codes, min_weight=low, max_weight=high)
