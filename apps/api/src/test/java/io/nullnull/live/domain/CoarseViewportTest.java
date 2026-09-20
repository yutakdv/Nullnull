package io.nullnull.live.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-091 coarse viewport")
class CoarseViewportTest {

    private static CoarseViewport of(String west, String south, String east, String north) {
        return new CoarseViewport(new BigDecimal(west), new BigDecimal(south),
                new BigDecimal(east), new BigDecimal(north));
    }

    @Test
    @DisplayName("a viewport a person panned to is accepted, including exactly at the floor")
    void coarseBoundsAreAccepted() {
        // A box around 광화문, three decimals, comfortably above the floor.
        assertThat(of("126.970", "37.550", "127.000", "37.580").north()).isEqualByComparingTo("37.58");
        // Exactly 0.01 on each axis: the floor is inclusive, so the smallest legal box is legal.
        // Without this the refusals below would also be satisfied by a type that refuses everything.
        assertThat(of("126.970", "37.550", "126.980", "37.560")).isNotNull();
        // 126.970 and 126.97 are the same number. A client that pads is not sending a finer reading,
        // and refusing it would reject a compliant client.
        assertThat(of("126.9700", "37.5500", "127.0000", "37.5800")).isNotNull();
    }

    @Test
    @DisplayName("BA-091-T1 viewport 는 소수점 3자리를 넘으면 거절된다")
    void aFourthDecimalIsRefused() {
        assertThatThrownBy(() -> of("126.9701", "37.550", "127.000", "37.580"))
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_TOO_PRECISE");
        assertThatThrownBy(() -> of("126.970", "37.5501", "127.000", "37.580"))
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_TOO_PRECISE");
    }

    @Test
    @DisplayName("BA-091-T8 viewport 는 축별 0.01도 미만이거나 역전된 box 를 거절한다")
    void aBoxThatIsNotCoarseIsRefused() {
        // 0.009 wide: one thousandth under the floor. The boundary case above is 0.010.
        assertThatThrownBy(() -> of("126.970", "37.550", "126.979", "37.580"))
                .as("longitude below the floor")
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_TOO_SMALL");
        assertThatThrownBy(() -> of("126.970", "37.550", "127.000", "37.559"))
                .as("latitude below the floor")
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_TOO_SMALL");
        // Inside out is the same refusal: a reversed box has a negative span, which is below a floor
        // of 0.01 for the same reason a zero-width one is.
        assertThatThrownBy(() -> of("127.000", "37.550", "126.970", "37.580"))
                .as("east west of west")
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_TOO_SMALL");
        // A point is the degenerate case of both.
        assertThatThrownBy(() -> of("126.970", "37.550", "126.970", "37.550"))
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_TOO_SMALL");
    }

    @Test
    @DisplayName("BA-091-T9 BA-091-T4 세계 밖 좌표를 담은 viewport 를 거절하고, 그 거절이 좌표를 로그에 남기지 않는다")
    void outOfRangeIsRefusedAndNothingLeaks() {
        assertThatThrownBy(() -> of("180.001", "37.550", "180.002", "37.580"))
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_OUT_OF_RANGE");
        assertThatThrownBy(() -> of("126.970", "90.001", "127.000", "90.002"))
                .isInstanceOf(LiveViewportException.class).hasMessage("VIEWPORT_OUT_OF_RANGE");

        // The message is the code and nothing else. This type exists to keep bounds coarse; a
        // message quoting the rejected ones would put them into the log line that records the
        // refusal, which is the same place PRIVACY_REQUIREMENTS says they must not be.
        assertThatThrownBy(() -> of("126.9701", "37.5502", "127.0003", "37.5804"))
                .satisfies(failure -> assertThat(failure.toString())
                        .doesNotContain("126.9701", "37.5502", "127.0003", "37.5804"));
    }
}
