package io.nullnull.social.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.FeedService.FeedPageView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-032 the shape of a feed page, which the HTTP tests can no longer assert.
 *
 * <p>{@code FeedIT} used to prove "the last page carries no cursor" by reading to the end of the
 * feed - which it could do only while it emptied the shared {@code posts} table first, and that
 * blanket delete is what AGENTS.md rule 6 is about. The claim did not stop being true; the place it
 * was made stopped being able to make it, because in the gate's one database another class's post
 * always follows.
 *
 * <p>So it moves here, where "this is the last page" is a value rather than a state of the world.
 */
@DisplayName("BA-032 feed page shape")
class FeedPageViewTest {

    @Test
    @DisplayName("a last page must not carry a cursor to a page that does not exist")
    void aLastPageCarriesNoCursor() {
        assertThatThrownBy(() -> new FeedPageView(List.of(), "not-the-end", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FeedPageView(List.of(), null, false).nextCursor()).isNull();
        assertThat(new FeedPageView(List.of(), "more-to-come", true).nextCursor()).isEqualTo("more-to-come");
    }

    /** The items are copied, so a caller cannot reach into a page it has already been handed. */
    @Test
    @DisplayName("a page does not share its item list with the caller")
    void itemsAreCopied() {
        List<FeedService.FeedCardView> mutable = new java.util.ArrayList<>();
        FeedPageView page = new FeedPageView(mutable, null, false);
        mutable.add(null);
        assertThat(page.items()).isEmpty();
    }
}
