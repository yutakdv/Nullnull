package io.nullnull.social.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-033: the minute bucket the impression dedup is keyed on.
 *
 * <p>It lives in Java rather than in the database because {@code date_trunc('minute', timestamptz)}
 * is STABLE, not IMMUTABLE, so PostgreSQL will not put it in a generated column, an index expression
 * or a CHECK. That makes the application the only place the rule exists, and this is where it is
 * pinned.
 */
@DisplayName("BA-033 the feedback minute bucket")
class FeedFeedbackBucketTest {

    @Test
    @DisplayName("BA-033-T2 every second of a minute maps to that minute, and the next second does not")
    void theBucketIsTheMinuteTheInstantFallsIn() {
        long bucket = FeedService.minuteBucket(Instant.parse("2026-09-12T04:05:00Z"));
        assertThat(FeedService.minuteBucket(Instant.parse("2026-09-12T04:05:59Z"))).isEqualTo(bucket);
        assertThat(FeedService.minuteBucket(Instant.parse("2026-09-12T04:05:59.999Z"))).isEqualTo(bucket);
        assertThat(FeedService.minuteBucket(Instant.parse("2026-09-12T04:06:00Z"))).isEqualTo(bucket + 1);
        assertThat(FeedService.minuteBucket(Instant.parse("2026-09-12T04:04:59Z"))).isEqualTo(bucket - 1);
    }

    @Test
    @DisplayName("BA-033-T2 an instant before 1970 floors towards the past, not towards zero")
    void thebucketFloorsRatherThanTruncates() {
        // Integer division truncates towards zero, so -61 / 60 is -1 and -1 / 60 is 0: two instants
        // a minute apart would share a bucket, and one of them would be the epoch minute itself.
        // No device sends a 1969 timestamp, which is exactly why the version that only works for
        // positive values would never be noticed - occurredAt is whatever the client's clock says.
        assertThat(FeedService.minuteBucket(Instant.ofEpochSecond(-1))).isEqualTo(-1);
        assertThat(FeedService.minuteBucket(Instant.ofEpochSecond(-60))).isEqualTo(-1);
        assertThat(FeedService.minuteBucket(Instant.ofEpochSecond(-61))).isEqualTo(-2);
        assertThat(FeedService.minuteBucket(Instant.ofEpochSecond(0))).isZero();
    }
}
