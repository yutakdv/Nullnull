package io.nullnull.optimization.application;

import io.nullnull.operations.application.DemoCapabilities;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.domain.TripValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether optimization can be asked for at all, and for which scope.
 *
 * <p>Two refusals live here and they are not the same refusal.
 *
 * <p><strong>The feature flag.</strong> {@code nullnull.capabilities.optimization}
 * (FEATURE_OPTIMIZATION_ITEM) is the same value getDemoReadiness publishes, and the endpoint is bound
 * to it on purpose. ENVIRONMENT.md §6 says the capability response is the source of truth the
 * Frontend believes; an endpoint that worked while that response said UNAVAILABLE would not be a
 * disabled feature but a live path nobody is watching. Default OFF in every environment, so in a
 * normal deployment this refuses everything.
 *
 * <p><strong>The scope.</strong> DAY and TRIP are P1 and are refused as an invalid value for this
 * build, not as a missing capability. No new Problem code and no new flag: a code needs an FE CTA and
 * a Figma state that P0 does not have, and a FEATURE_OPTIMIZATION_DAY property would be a flag whose
 * ON state means nothing, because there is no DAY implementation for it to turn on. VALIDATION_FAILED
 * on {@code scope} is what the field actually is - a value this server does not accept.
 *
 * <p>The order matters and is deliberate: the flag is checked first, so a disabled server does not
 * spend its answer telling a caller which scopes it would have accepted.
 */
@Component
public class OptimizationCapability {

    private final boolean enabled;

    public OptimizationCapability(
            @Value("${nullnull.capabilities." + DemoCapabilities.OPTIMIZATION + "}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void require(OptimizationScope scope) {
        if (!enabled) {
            throw new ApiException(ProblemCode.FORBIDDEN,
                    "Optimization is not enabled on this server.");
        }
        if (!scope.availableInP0()) {
            throw new TripValidationException("scope", "Unsupported",
                    scope + " optimization is not available in this release");
        }
    }
}
