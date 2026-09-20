package io.nullnull.live.application;

import io.nullnull.live.domain.CoarseViewport;
import io.nullnull.live.domain.LiveQueryMode;
import io.nullnull.live.domain.LiveViewportException;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * The read side of the Live tab: validate what the caller sent, then answer with what is stored.
 *
 * <p><strong>Today that is nothing, and the response says so rather than pretending.</strong> The
 * Seoul collector writes no snapshot yet, so this returns an empty page whose mode is UNAVAILABLE -
 * the answer {@link LiveCapability} documents for "the feature is on and the provider gave us
 * nothing". It is not a placeholder and it is not a stub: an empty page IS the correct projection of
 * an empty store, and the alternative - seeding the screen with anything at all - is the one thing
 * invariant 6 forbids outright. When the collector's writer lands, the row source changes and this
 * class's contract with the caller does not.
 *
 * <p><strong>The viewport is validated and then not used as a filter.</strong> With no rows there is
 * nothing to filter, but the validation is NOT deferred with them: refusing precise coordinates is
 * the privacy boundary (invariant 10), and a boundary that only starts working once there is data to
 * protect is a boundary that was open for exactly as long as nobody looked.
 */
@Service
public class LiveAreaQueryService {

    /** LiveAreaQuery.regionCode maxLength in docs/api/openapi.yaml. */
    private static final int REGION_CODE_MAX = 100;

    private final LiveCapability capability;
    private final LiveAreaProjection projection;
    private final Clock clock;

    public LiveAreaQueryService(LiveCapability capability, LiveAreaProjection projection, Clock clock) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LiveAreaProjection.LiveAreaResultResponse query(String mode, String regionCode,
            BigDecimal west, BigDecimal south, BigDecimal east, BigDecimal north) {
        // SHAPE FIRST, CAPABILITY SECOND - the order createOptimization uses, and the reason is
        // written into CapabilityOffCoverageIT: a malformed body there answers 400 and never reaches
        // the gate, so that register sends a well-formed one on purpose. Keeping the same order here
        // means one rule for both, and it costs nothing a caller can use: these three checks are
        // pure functions of the request and reveal nothing about what the server holds. The probing
        // risk invariant 11 guards against is the opposite shape - an answer that varies with stored
        // state - and none of these do.
        requireMode(mode);
        requireRegionCode(regionCode);
        requireViewport(west, south, east, north);
        capability.require();
        return projection.project(List.of(), clock.instant());
    }

    private static void requireMode(String mode) {
        if (mode == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "mode is required.");
        }
        try {
            LiveQueryMode.valueOf(mode);
        } catch (IllegalArgumentException unknown) {
            // The rejected value is not quoted back. It is caller-supplied text on a path whose
            // problem details are logged, and the three accepted values are in the contract.
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "mode must be one of AUTO, LIVE_ONLY, REPLAY_ALLOWED.");
        }
    }

    private static void requireRegionCode(String regionCode) {
        if (regionCode != null && regionCode.length() > REGION_CODE_MAX) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "regionCode must be at most " + REGION_CODE_MAX + " characters.");
        }
    }

    private static void requireViewport(BigDecimal west, BigDecimal south, BigDecimal east,
            BigDecimal north) {
        boolean anyPresent = west != null || south != null || east != null || north != null;
        if (!anyPresent) {
            return;
        }
        if (west == null || south == null || east == null || north == null) {
            // Half a viewport is not a viewport, and it must not fall through as "no viewport": a
            // box missing one edge would then be served as an unbounded query.
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "viewport needs west, south, east and north together.");
        }
        try {
            new CoarseViewport(west, south, east, north);
        } catch (LiveViewportException refused) {
            // The code travels, the coordinates do not - which is the whole point of refusing.
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "viewport refused: " + refused.code());
        }
    }
}
