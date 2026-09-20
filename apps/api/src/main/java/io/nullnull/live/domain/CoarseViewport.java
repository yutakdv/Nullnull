package io.nullnull.live.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The bounds a person panned to, deliberately too coarse to be a position (BA-091, CMP-LOC-001).
 *
 * <p>This is the ONE registered exception to "no coordinate reaches the server in a request"
 * ({@code LocationInputContractTest}), and it is an exception only while it stays coarse. Two rules
 * make it so, and both come from PRIVACY_REQUIREMENTS.md section on location:
 *
 * <ul>
 *   <li>three decimals, because more is a device reading rather than a map the person moved;
 *   <li>at least 0.01 degrees on each axis, because a smaller box is a point with extra steps.
 * </ul>
 *
 * <p>THERE IS NO UPPER BOUND HERE, and that absence is deliberate. The card's wording mentions an
 * oversized viewport, but no document in this repository states a maximum span - the privacy rule is
 * a floor, not a window. Inventing a ceiling would put a number nobody reviewed into a refusal, and
 * a large box is not a privacy problem: it is a wider read of a bounded set of areas.
 *
 * <p>Values are BigDecimal, not double, because the three-decimal rule is a decimal fact. 37.5719
 * has no exact binary form, so a double can only be asked whether it is CLOSE to three decimals.
 */
public record CoarseViewport(BigDecimal west, BigDecimal south, BigDecimal east, BigDecimal north) {

    /** Each axis must span at least this much; below it the box stops being a neighbourhood. */
    public static final BigDecimal MINIMUM_SPAN = new BigDecimal("0.01");

    /** The rounding the browser is required to apply before the bounds are sent. */
    public static final int MAXIMUM_SCALE = 3;

    public CoarseViewport {
        Objects.requireNonNull(west, "west");
        Objects.requireNonNull(south, "south");
        Objects.requireNonNull(east, "east");
        Objects.requireNonNull(north, "north");
        inRange(west, 180, "west");
        inRange(east, 180, "east");
        inRange(south, 90, "south");
        inRange(north, 90, "north");
        coarse(west, "west");
        coarse(south, "south");
        coarse(east, "east");
        coarse(north, "north");
        span(west, east, "longitude");
        span(south, north, "latitude");
    }

    private static void inRange(BigDecimal value, int limit, String name) {
        if (value.abs().compareTo(BigDecimal.valueOf(limit)) > 0) {
            throw new LiveViewportException(LiveViewportException.Code.VIEWPORT_OUT_OF_RANGE);
        }
    }

    /**
     * stripTrailingZeros first: 37.570 and 37.57 are the same number, and a client that sends the
     * padded form is not sending a finer reading. Refusing it would reject a compliant client.
     */
    private static void coarse(BigDecimal value, String name) {
        if (value.stripTrailingZeros().scale() > MAXIMUM_SCALE) {
            throw new LiveViewportException(LiveViewportException.Code.VIEWPORT_TOO_PRECISE);
        }
    }

    /** Ordering and floor in one place: a reversed box and a tiny box are the same refusal here. */
    private static void span(BigDecimal low, BigDecimal high, String axis) {
        if (high.subtract(low).compareTo(MINIMUM_SPAN) < 0) {
            throw new LiveViewportException(LiveViewportException.Code.VIEWPORT_TOO_SMALL);
        }
    }
}
