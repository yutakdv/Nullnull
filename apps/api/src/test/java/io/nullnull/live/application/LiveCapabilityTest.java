package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-091 Live capability gate")
class LiveCapabilityTest {

    @Test
    @DisplayName("a server with the flag off refuses, and says so as a capability rather than as missing data")
    void offRefuses() {
        LiveCapability off = new LiveCapability(false);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(off::require)
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ProblemCode.FORBIDDEN));
    }

    @Test
    @DisplayName("a server with the flag on does not refuse here, so the gate is not a constant")
    void onDoesNotRefuse() {
        // Without this the refusal above is also satisfied by a gate that refuses unconditionally,
        // and the flag would be decoration. FEATURE_LIVE_DATA is OFF in every environment today, so
        // this is the only place the ON branch is exercised at all.
        LiveCapability on = new LiveCapability(true);
        assertThat(on.enabled()).isTrue();
        on.require();
    }
}
