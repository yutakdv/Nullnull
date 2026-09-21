package io.nullnull.live.application;

import io.nullnull.operations.application.DemoCapabilities;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the Live tab may be asked for anything at all.
 *
 * <p>Bound to {@code nullnull.capabilities.live} (FEATURE_LIVE_DATA), the same value
 * {@code getDemoReadiness} publishes, for the reason {@code OptimizationCapability} gives: ENVIRONMENT
 * §6 makes the capability response the truth the Frontend believes, so an operation that answered
 * while that response said the feature was off would be a live path nobody is watching.
 *
 * <p><strong>Refusing is not the same as having no data, and Live has both.</strong> This throws
 * FORBIDDEN, because the feature is not enabled - the caller is not owed a payload. A server with the
 * flag ON whose provider did not answer is the other case, and it returns 200 with
 * {@code LiveAreaResult.mode = UNAVAILABLE} and no areas, because there the feature IS enabled and
 * "nothing to report" is the honest answer. Collapsing the two would make an operator unable to tell
 * a disabled tab from a broken provider by looking at a response.
 *
 * <p>The flag defaults OFF locally; staging opts in after wiring the Seoul collector.
 */
@Component
public class LiveCapability {

    private final boolean enabled;

    public LiveCapability(@Value("${nullnull.capabilities." + DemoCapabilities.LIVE + "}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void require() {
        if (!enabled) {
            throw new ApiException(ProblemCode.FORBIDDEN, "Live data is not enabled on this server.");
        }
    }
}
