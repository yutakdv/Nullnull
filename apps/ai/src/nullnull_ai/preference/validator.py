"""REC-LLM-05: an interpretation is read only when it says nothing the vocabulary cannot say.

The counterpart of `explain.validator` on the input-interpretation side. That one asks whether a
sentence adds a fact; this one asks whether a selection adds a code, a strength or a repetition the
caller never offered. Both answer with a bool and neither repairs anything: a caller that gets False
keeps what the traveller chose, exactly as an unacceptable sentence keeps the template.

Nothing here is normalised. Trimming whitespace, upper-casing a code or clamping a weight into range
would turn a model's near-miss into an answer the traveller never made - a silent fallback wearing
the clothes of a fix.
"""

from __future__ import annotations

from collections.abc import Sequence

from nullnull_ai.preference.types import BoundedPreference, PreferenceVocabulary


def accepts(vocabulary: PreferenceVocabulary, selection: Sequence[BoundedPreference] | None) -> bool:
    """True when every item is a distinct code of `vocabulary` with a weight inside its bounds.

    An empty selection is refused rather than read as "no preferences": a model that interpreted
    nothing and a traveller who chose nothing are different states, and only the caller knows which
    of the two it is holding.
    """
    if not selection:
        return False
    seen: set[str] = set()
    for item in selection:
        if item.code not in vocabulary.codes:
            return False
        if item.code in seen:
            return False
        seen.add(item.code)
        if isinstance(item.weight, bool) or not isinstance(item.weight, int):
            return False
        if not vocabulary.min_weight <= item.weight <= vocabulary.max_weight:
            return False
    return True
