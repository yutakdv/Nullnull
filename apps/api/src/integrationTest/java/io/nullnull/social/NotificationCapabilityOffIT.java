package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.nullnull.identity.application.SessionService;
import io.nullnull.operations.application.DemoCapabilities;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-085 with the notification flag in its shipped state, which is OFF.
 *
 * <p>Deliberately a file of its own, for the reason {@code OptimizationCapabilityOffIT} exists:
 * every other notification test turns the flag on to have something to measure, so without this one
 * nothing looks at the configuration the submission build actually runs.
 *
 * <p>It also pins what this flag is NOT. {@code getDemoReadiness} publishes exactly three
 * capabilities and {@code DemoCapabilities} says in as many words that the set is "nothing invented
 * beyond them"; adding a fourth is a FE-facing contract change owned by BA-003, because the
 * Frontend maps those names to screens. A server-side feature flag does not have to be published to
 * be real, and this asserts that it was not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-085 notifications are off by default")
class NotificationCapabilityOffIT {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;

    @Test
    @DisplayName("BA-085-T3 with the notification flag off every notification operation refuses")
    void everyNotificationOperationRefusesWhileTheFlagIsOff() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        Cookie session = new Cookie("__Host-nullnull_session", owner.cookie);

        // No property is set on this class on purpose: this is application.yaml's default.
        mvc.perform(get("/api/v1/notifications").cookie(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());
        mvc.perform(put("/api/v1/notifications/read-all").cookie(session)
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "off-" + UUID.randomUUID()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());
        // A random id, and the answer is still the feature refusal rather than a 404. A disabled
        // server must not be usable to probe which notification ids exist.
        mvc.perform(put("/api/v1/notifications/{id}/read", UUID.randomUUID()).cookie(session)
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());
    }

    @Test
    @DisplayName("BA-085-T11 the notification feature flag is not in getDemoReadiness'"
            + " capability list")
    void theFlagIsNotAFourthCapability() {
        // The clause this id carries is the second assertion only. The first - that the set is
        // exactly those three - is already pinned by DemoCapabilityQueryTest and belongs to BA-003;
        // carrying it here too would make BA-085 look better covered than it is, which is the
        // mirror of the borrowed-id problem #195 closed. It stays as context, not as the clause.
        assertThat(DemoCapabilities.NAMES)
                .as("context: FR-OPS-02 names three, and BA-003 owns that set")
                .containsExactly(DemoCapabilities.LIVE, DemoCapabilities.REPLAY,
                        DemoCapabilities.OPTIMIZATION);
        assertThat(DemoCapabilities.NAMES)
                .as("the clause: the notification flag was not published as a fourth capability")
                .doesNotContain("notifications");
    }
}
