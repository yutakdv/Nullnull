package io.nullnull.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock a test moves by hand.
 *
 * <p>Job leases, back-off and retention are all compared against the injected clock and never against
 * the database's {@code now()}, so a test can expire a lease or reach a retention cutoff by moving
 * time instead of by sleeping or by editing rows. That keeps the timing tests deterministic and keeps
 * them exercising the production comparison rather than a hand-written one.
 *
 * <p>Time only moves forward: a test that moved it backwards would create a state the running system
 * cannot reach, and the assertions written against it would prove nothing.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    private MutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    public static MutableClock at(Instant start) {
        return new MutableClock(start, ZoneOffset.UTC);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new MutableClock(now.get(), other);
    }

    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a test clock only moves forward: " + amount);
        }
        now.updateAndGet(current -> current.plus(amount));
    }

    /** Moves to an exact instant, for example the {@code next_attempt_at} a back-off just wrote. */
    public void set(Instant target) {
        now.updateAndGet(current -> {
            if (target.isBefore(current)) {
                throw new IllegalArgumentException("a test clock only moves forward");
            }
            return target;
        });
    }
}
