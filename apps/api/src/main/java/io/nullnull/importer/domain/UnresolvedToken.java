package io.nullnull.importer.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Something the parser would not decide: a date with no year, an ambiguous time, a name that matched
 * nothing. A token is a question for the traveller, and a draft holding one cannot be READY.
 *
 * <p>{@code suggestionPlaceIds} are ids rather than places for the same reason the items carry ids:
 * the summaries are hydrated behind the publication gate when the draft is read.
 *
 * <p>What {@code label} may carry is the open question of #223 and is deliberately not settled here.
 * The contract makes it a required free string, which is where a pasted free-text line would land -
 * the card's safety boundary names exactly that - so nothing in this module writes a token yet. The
 * bound below is the one thing that can be stated without the answer: whatever a label ends up
 * holding, it is a token and not a paste.
 */
public record UnresolvedToken(String clientKey, Kind kind, int line, String label,
        List<UUID> suggestionPlaceIds) {

    public enum Kind { PLACE, DATE, TIME }

    /** The contract's UnresolvedImportToken.suggestions cap. */
    public static final int MAX_SUGGESTIONS = 10;
    /** The contract's UnresolvedImportToken.label bound, as #223 settled it. */
    public static final int MAX_LABEL = 40;

    public UnresolvedToken {
        Objects.requireNonNull(clientKey, "clientKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        suggestionPlaceIds = List.copyOf(Objects.requireNonNull(suggestionPlaceIds, "suggestionPlaceIds"));
        if (line < 1) {
            throw new IllegalArgumentException("lines are 1-based");
        }
        if (label.length() > MAX_LABEL) {
            throw new IllegalArgumentException("a token label is a token, not a paste");
        }
        if (suggestionPlaceIds.size() > MAX_SUGGESTIONS) {
            throw new IllegalArgumentException("at most " + MAX_SUGGESTIONS + " suggestions per token");
        }
    }
}
