package io.nullnull.optimization.application;

import io.nullnull.optimization.application.OptimizationHistoryQuery.HistoryRow;
import java.util.List;
import java.util.Objects;

/**
 * One page of the owner's optimization history.
 *
 * <p>The same shape {@code TripPageView} has, including the invariant: a last page must not hand out
 * a cursor. A cursor on a last page costs the reader a round trip that can only come back empty, and
 * it is the kind of thing that stays correct by accident until a caller trusts it.
 */
public record OptimizationHistoryPageView(List<HistoryRow> items, String nextCursor, boolean hasMore) {

    public OptimizationHistoryPageView {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("a last page must not carry a next cursor");
        }
    }
}
