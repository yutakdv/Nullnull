package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What ktoDemoRefresh decides before any context starts or any call is made, and how it ends. */
@DisplayName("ktoDemoRefresh command")
class KtoDemoRefreshCommandTest {

    /** scripts/aws/staging_operator.py OPS_LOG_LINE, the failure half: what an ops task may echo. */
    private static final Pattern FAILURE_LINE =
            Pattern.compile("^.*Exception: KTO [a-z ]+ failed: [A-Za-z_ ()]{1,80}$");

    @Test
    @DisplayName("a list of contentId:contentTypeId entries is read in order, spaces around entries ignored")
    void aListIsReadInOrder() {
        assertThat(KtoDemoRefresh.places("126508:12, 2733967:14"))
                .containsExactly(new KtoPlaceRequest("126508", "12"), new KtoPlaceRequest("2733967", "14"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "126508", "126508:", ":12", "126508:12,", ",126508:12", "126508:12,,2733967:14",
            "0:12", "126508:x", "126508:12:1", "126508;12"})
    @DisplayName("an empty list or any malformed entry refuses the whole list")
    void aMalformedListIsRefusedWhole(String list) {
        assertThatThrownBy(() -> KtoDemoRefresh.places(list)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an absent list is refused")
    void anAbsentListIsRefused() {
        assertThatThrownBy(() -> KtoDemoRefresh.places(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the place list is empty");
    }

    @Test
    @DisplayName("a repeated contentId is refused, even with another content type")
    void aRepeatedContentIdIsRefused() {
        assertThatThrownBy(() -> KtoDemoRefresh.places("126508:12,126508:14"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("a contentId appears more than once");
    }

    @Test
    @DisplayName("each mode needs its own approval, and the other mode's does not stand in for it")
    void eachModeNeedsItsOwnApproval() {
        Map<String, String> detailOnly = Map.of(KtoDemoRefreshCommand.DETAIL_APPROVAL, "true",
                KtoDemoRefreshCommand.PLACES, "126508:12");
        assertThatNoException().isThrownBy(() -> KtoDemoRefreshCommand.before(KtoDemoRefresh.Mode.DETAIL, detailOnly));
        assertThatThrownBy(() -> KtoDemoRefreshCommand.before(KtoDemoRefresh.Mode.FORECAST, detailOnly))
                .hasMessage("KTO demo refresh failed: APPROVAL_NOT_SET");
        assertThatThrownBy(() -> KtoDemoRefreshCommand.before(KtoDemoRefresh.Mode.DETAIL,
                Map.of(KtoDemoRefreshCommand.DETAIL_APPROVAL, "yes", KtoDemoRefreshCommand.PLACES, "126508:12")))
                .hasMessage("KTO demo refresh failed: APPROVAL_NOT_SET");
    }

    @Test
    @DisplayName("an approved run with a bad list fails before it starts")
    void aBadListFailsBeforeTheRun() {
        assertThatThrownBy(() -> KtoDemoRefreshCommand.before(KtoDemoRefresh.Mode.FORECAST,
                Map.of(KtoDemoRefreshCommand.FORECAST_APPROVAL, "true", KtoDemoRefreshCommand.PLACES, "126508:12,")))
                .hasMessage("KTO demo refresh failed: INVALID_PLACE_LIST");
        assertThatThrownBy(() -> KtoDemoRefreshCommand.before(KtoDemoRefresh.Mode.FORECAST,
                Map.of(KtoDemoRefreshCommand.FORECAST_APPROVAL, "true")))
                .hasMessage("KTO demo refresh failed: INVALID_PLACE_LIST");
    }

    @Test
    @DisplayName("one failed place fails the run, and a run with none does not")
    void oneFailedPlaceFailsTheRun() {
        KtoPlaceRequest place = new KtoPlaceRequest("126508", "12");
        KtoDemoRefresh.Outcome refreshed = new KtoDemoRefresh.Outcome(place, KtoDemoRefresh.Status.REFRESHED,
                UUID.randomUUID(), "coverage=30", null, true);
        KtoDemoRefresh.Outcome failed = KtoDemoRefresh.Outcome.failed(new KtoPlaceRequest("2733967", "14"),
                "NO_CANONICAL_PLACE", false);

        assertThatNoException().isThrownBy(() -> KtoDemoRefreshCommand.finish(
                new KtoDemoRefresh.Report(KtoDemoRefresh.Mode.FORECAST, "KTO_CONCENTRATION_FORECAST", 1000,
                        List.of(refreshed))));
        assertThatThrownBy(() -> KtoDemoRefreshCommand.finish(
                new KtoDemoRefresh.Report(KtoDemoRefresh.Mode.FORECAST, "KTO_CONCENTRATION_FORECAST", 1000,
                        List.of(failed, refreshed))))
                .hasMessage("KTO demo refresh failed: PLACE_FAILED");
    }

    @Test
    @DisplayName("every way the command fails is a line the operator log allowlist passes")
    void everyFailureIsALineTheAllowlistPasses() {
        for (String message : List.of("KTO demo refresh failed: APPROVAL_NOT_SET",
                "KTO demo refresh failed: INVALID_PLACE_LIST", "KTO demo refresh failed: PLACE_FAILED",
                "KTO demo refresh failed: " + KtoDemoRefreshCommand.code(
                        new KtoGatewayException(KtoGatewayException.Code.KTO_QUOTA_EXHAUSTED)),
                "KTO demo refresh failed: " + KtoDemoRefreshCommand.code(new IllegalStateException("x9")))) {
            assertThat(FAILURE_LINE.matcher("java.lang.IllegalStateException: " + message).matches())
                    .as(message).isTrue();
        }
        // A gateway failure keeps its code; anything else becomes one code without digits.
        assertThat(KtoDemoRefreshCommand.code(new KtoGatewayException(KtoGatewayException.Code.KTO_QUOTA_EXHAUSTED)))
                .isEqualTo("KTO_QUOTA_EXHAUSTED");
        assertThat(KtoDemoRefreshCommand.code(new java.util.concurrent.CompletionException(
                new KtoGatewayException(KtoGatewayException.Code.KTO_NOT_CONFIGURED))))
                .isEqualTo("KTO_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("the ratio is written with four decimals and no percent sign")
    void theRatioHasNoPercentSign() {
        assertThat(KtoDemoRefresh.ratio(5, 1000)).isEqualTo("0.0050");
        assertThat(KtoDemoRefresh.ratio(0, 1000)).isEqualTo("0.0000");
    }
}
