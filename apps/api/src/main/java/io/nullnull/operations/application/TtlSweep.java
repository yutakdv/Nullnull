package io.nullnull.operations.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs every {@link TtlEraser} once. The worker schedules it; a test calls it directly.
 *
 * <p>One eraser failing must not stop the others - a stuck sweep in one module would silently extend
 * every other module's retention - so each runs on its own and the failures are reported together at
 * the end. Nothing is swallowed: the caller gets an exception naming the erasers that failed, and the
 * scheduled wrapper logs it and tries again on the next tick.
 */
@Service
public class TtlSweep {

    private static final Logger log = LoggerFactory.getLogger(TtlSweep.class);

    /** Rows removed per eraser name, in registration order. */
    public record SweepReport(Map<String, Integer> deleted) {
    }

    private final List<TtlEraser> erasers;
    private final Clock clock;

    public TtlSweep(List<TtlEraser> erasers, Clock clock) {
        this.erasers = List.copyOf(erasers);
        this.clock = clock;
    }

    public SweepReport sweep() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        RuntimeException first = null;
        for (TtlEraser eraser : erasers) {
            try {
                int rows = eraser.erase(clock.instant());
                deleted.put(eraser.name(), rows);
                if (rows > 0) {
                    log.info("retention sweep name={} deleted={}", eraser.name(), rows);
                }
            } catch (RuntimeException failure) {
                failed.add(eraser.name());
                log.error("retention sweep failed name={}", eraser.name(), failure);
                if (first == null) {
                    first = failure;
                }
            }
        }
        if (first != null) {
            throw new IllegalStateException("retention sweep failed for " + failed, first);
        }
        return new SweepReport(deleted);
    }
}
