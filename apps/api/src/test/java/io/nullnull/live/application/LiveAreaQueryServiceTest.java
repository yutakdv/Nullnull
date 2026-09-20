package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The enabled Live path, which no integration test can reach today.
 *
 * <p>{@code DemoCapabilityQuery} refuses to start with FEATURE_LIVE_DATA ON while nothing stores a
 * reading, so a {@code @SpringBootTest} cannot turn the flag on - see {@code LiveAreaApiIT}. Without
 * this file the enabled branch of the service would ship having never run.
 */
@DisplayName("BA-091 live area query with the capability on")
class LiveAreaQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static LiveAreaQueryService service(boolean enabled) {
        // An empty store: this file is about validation order and the capability, and an empty page
        // is what an enabled server with nothing collected answers. The stored-reading path is
        // LiveAreaReadIT, which needs a database to hold a reading at all.
        return new LiveAreaQueryService(new LiveCapability(enabled), new LiveAreaProjection(),
                new EmptyAreas(), (source, ids, now) -> List.of(), CLOCK);
    }

    private static final class EmptyAreas implements io.nullnull.live.application.LiveAreaStore {
        @Override
        public List<StoredArea> replaceAreas(String sourceCode, List<AreaUpsert> published) {
            throw new UnsupportedOperationException("not this file's question");
        }

        @Override
        public StoredArea upsertArea(String sourceCode, AreaUpsert area) {
            throw new UnsupportedOperationException("not this file's question");
        }

        @Override
        public List<StoredArea> activeAreas(String sourceCode) {
            return List.of();
        }
    }

    @Test
    @DisplayName("BA-091 켜진 서버에 관측이 없으면 빈 UNAVAILABLE 페이지다")
    void anEnabledServerWithNoReadingAnswersUnavailable() {
        LiveAreaProjection.LiveAreaResultResponse result = service(true).query("AUTO", "KR-11",
                new BigDecimal("126.977"), new BigDecimal("37.579"),
                new BigDecimal("127.007"), new BigDecimal("37.609"));

        // UNAVAILABLE, not LIVE: an empty page is not a live reading of an empty city, and it is not
        // a refusal either - the feature IS on here. Those are the two things LiveCapability exists
        // to keep apart.
        assertThat(result.mode()).isEqualTo("UNAVAILABLE");
        assertThat(result.areas()).isEmpty();
        assertThat(result.generatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("BA-091 꺼진 서버는 같은 요청에 403 으로 답한다")
    void aDisabledServerRefusesTheSameRequest() {
        assertThatThrownBy(() -> service(false).query("AUTO", null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ProblemCode.FORBIDDEN));
    }

    @Test
    @DisplayName("BA-091 거절 사유에 좌표가 들어가지 않는다")
    void aRefusalNamesTheRuleAndNotTheCoordinates() {
        // The same absence LiveAreaApiIT asserts on the HTTP body, at the place the text is made -
        // so a future detail message that quotes the value fails here first, with a smaller test.
        assertThatThrownBy(() -> service(true).query("AUTO", null,
                new BigDecimal("126.9770411"), new BigDecimal("37.5796171"),
                new BigDecimal("126.9880411"), new BigDecimal("37.5896171")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("VIEWPORT_TOO_PRECISE")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("126.977", "37.579", "126.988", "37.589"));
    }

    @Test
    @DisplayName("BA-091 mode 와 viewport 의 모양은 capability 보다 먼저 판정된다")
    void shapeIsJudgedBeforeTheCapability() {
        // With the flag OFF a bad shape still answers 400, which is the ordering CapabilityOffCoverageIT
        // depends on in the other direction: it sends a WELL-FORMED body so that its 403 is the
        // capability's and not the parser's.
        assertThatThrownBy(() -> service(false).query("EVERYTHING", null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ProblemCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service(false).query("AUTO", null,
                new BigDecimal("126.977"), new BigDecimal("37.579"), new BigDecimal("127.007"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(failure -> assertThat(((ApiException) failure).code())
                        .isEqualTo(ProblemCode.VALIDATION_FAILED));
    }
}
