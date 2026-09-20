package io.nullnull.social.application;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the notification centre answers at all (BA-085).
 *
 * <p>Default OFF, so a normal deployment - including the submission build - serves no notification
 * surface. The three operations ask this before they do anything else, which is what makes "the
 * list exists but is not switched on" a state the server actually enforces rather than a claim.
 *
 * <p><strong>This is deliberately NOT a {@code DemoCapabilities} entry.</strong> That vocabulary is
 * pinned to exactly {@code live}, {@code replay} and {@code optimization}: its only documentary
 * source is {@code FR-OPS-02} ("live/replay/optimization별 상태"), the class says in as many words
 * that the set is "nothing invented beyond them", and {@code DemoCapabilityQueryTest} fixes it.
 * Adding a fourth is a FE-facing contract change belonging to the BA-003 card, because
 * {@code getDemoReadiness} publishes the list and the Frontend maps those names to screens. A
 * server-side feature flag does not need to be published to be real, so this one is not.
 */
@Component
public class NotificationCapability {

    private final boolean enabled;

    public NotificationCapability(@Value("${nullnull.notifications.enabled}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void require() {
        if (!enabled) {
            // FORBIDDEN rather than a 404: the refusal is about this server, not about whether the
            // caller's notifications exist, and it must read the same for every caller.
            throw new ApiException(ProblemCode.FORBIDDEN,
                    "Notifications are not enabled on this server.");
        }
    }
}
