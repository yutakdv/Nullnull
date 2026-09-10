package io.nullnull.shared.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Small per-source breaker with a rolling failure window and an injected clock. */
public final class CircuitBreaker {

    private final Clock clock;
    private final int failureThreshold;
    private final Duration failureWindow;
    private final Duration openDuration;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant openUntil;

    public CircuitBreaker(Clock clock, int failureThreshold, Duration failureWindow, Duration openDuration) {
        if (failureThreshold < 1 || failureWindow.isNegative() || failureWindow.isZero()
                || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("circuit breaker limits must be positive");
        }
        this.clock = clock;
        this.failureThreshold = failureThreshold;
        this.failureWindow = failureWindow;
        this.openDuration = openDuration;
    }

    public synchronized boolean allowRequest() {
        Instant now = clock.instant();
        if (openUntil == null) {
            return true;
        }
        if (now.isBefore(openUntil)) {
            return false;
        }
        openUntil = null;
        failures.clear();
        return true;
    }

    public synchronized void success() {
        failures.clear();
        openUntil = null;
    }

    public synchronized void failure() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(failureWindow);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
        failures.addLast(now);
        if (failures.size() >= failureThreshold) {
            openUntil = now.plus(openDuration);
            failures.clear();
        }
    }

    public synchronized boolean isOpen() {
        return !allowRequest();
    }
}
