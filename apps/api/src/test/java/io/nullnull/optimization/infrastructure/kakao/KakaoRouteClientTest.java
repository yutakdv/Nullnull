package io.nullnull.optimization.infrastructure.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.optimization.application.RouteMatrixGateway.RouteLeg;
import io.nullnull.optimization.application.RouteMatrixGateway.RouteWaypoint;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Leg;
import io.nullnull.shared.provider.CircuitBreaker;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.RetryPolicy;
import io.nullnull.testsupport.StubProviderServer;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-083: what the Kakao Mobility adapter turns a response into.
 *
 * <p>The shapes here are the ones a live call established on 2026-09-20 - {@code routes[0]} carries
 * {@code result_code} and {@code summary.duration}, and success is {@code 0}. The response body
 * itself is not kept anywhere: A-055 forbids storing a Kakao response and the official answers it
 * rests on refuse even a development sample, so these fixtures are the two fields this adapter reads,
 * written by hand, and not a captured payload.
 */
@DisplayName("BA-083 Kakao Mobility route adapter")
class KakaoRouteClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private static final RouteWaypoint A =
            new RouteWaypoint("a", new BigDecimal("37.5796"), new BigDecimal("126.9770"));
    private static final RouteWaypoint B =
            new RouteWaypoint("b", new BigDecimal("37.5512"), new BigDecimal("126.9882"));

    private static String routed(int resultCode, String summary) {
        return "{\"trans_id\":\"t\",\"routes\":[{\"result_code\":" + resultCode
                + ",\"result_msg\":\"m\"" + summary + "}]}";
    }

    private static String succeeded(long durationSeconds) {
        return routed(0, ",\"summary\":{\"distance\":6308,\"duration\":" + durationSeconds + "}");
    }

    @Test
    @DisplayName("BA-083-T22 only a success result code becomes a travel time; every other answer is Absent")
    void onlySuccessBecomesATravelTime() throws Exception {
        // Each enqueued response is one leg, asked for in order. The success case is first and is
        // what makes the rest non-vacuous: without it, an adapter that returned an empty matrix for
        // everything would satisfy all the Absent assertions below.
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, succeeded(1114)))
                // A refusal that still carries a number. The fixture is deliberately this shape and
                // not a bare refusal: with no summary, the missing-duration check below would be what
                // rejected it and the result code would be asserting nothing. Measured - dropping the
                // result_code check left this whole test green until the summary was put back in.
                //
                // We cannot yet tell which non-zero codes mean "no road" (see
                // RouteLegStateCoverageTest), so it is Absent rather than Unavailable, and the number
                // beside the refusal is not a travel time we may use.
                .enqueue(new StubProviderServer.Response(200,
                        routed(104, ",\"summary\":{\"distance\":6308,\"duration\":1114}")))
                // Transport failure: the provider never answered at all.
                .enqueue(new StubProviderServer.Response(500, "{}"))
                // Well-formed JSON that is not this API's shape.
                .enqueue(new StubProviderServer.Response(200, "{\"routes\":[]}"))
                // Success code, but the field the duration would be in is missing.
                .enqueue(new StubProviderServer.Response(200, routed(0, ",\"summary\":{\"distance\":6308}")))
                // Success code and a duration no clock can produce.
                .enqueue(new StubProviderServer.Response(200, succeeded(-1)));
             Fixture fixture = fixture(stub)) {

            List<RouteLeg> legs = List.of(leg(A, B), leg(B, A), leg(A, C), leg(C, A), leg(B, C), leg(C, B));
            DirectedRouteMatrix matrix = fixture.client.legsFor(legs);

            assertThat(matrix.leg("a", "b")).isEqualTo(new Leg.Available(Duration.ofSeconds(1114)));
            assertThat(matrix.leg("b", "a")).isInstanceOf(Leg.Absent.class);
            assertThat(matrix.leg("a", "c")).isInstanceOf(Leg.Absent.class);
            assertThat(matrix.leg("c", "a")).isInstanceOf(Leg.Absent.class);
            assertThat(matrix.leg("b", "c")).isInstanceOf(Leg.Absent.class);
            assertThat(matrix.leg("c", "b")).isInstanceOf(Leg.Absent.class);
            // One request per ordered pair: there is no matrix operation to batch them into.
            assertThat(stub.calls()).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("BA-083-T24 the request carries longitude before latitude and the credential as a header")
    void theRequestIsShapedTheWayTheProviderReadsIt() throws Exception {
        // Both halves are silent when wrong. Swapped coordinates address a different point on the
        // earth, and this provider answers that with a refusal rather than an error - which this
        // adapter maps to Absent, so a swap would read as "no route exists" forever. A credential in
        // the query string instead of the header would work and would put a secret where an access
        // log records it.
        String key = "fake-kakao-key-never-retained";
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, succeeded(600)));
             Fixture fixture = fixture(stub, key)) {

            fixture.client.legsFor(List.of(leg(A, B)));

            // x,y - longitude first. A is 37.5796 N, 126.9770 E.
            assertThat(stub.observedQuery(0)).containsEntry("origin", "126.9770,37.5796");
            assertThat(stub.observedQuery(0)).containsEntry("destination", "126.9882,37.5512");
            // radius belongs to the multi-destination and multi-origin operations; the 2026-09-20 call
            // omitted it and was answered result_code 0.
            assertThat(stub.observedQuery(0)).doesNotContainKey("radius");
            // Present, and empty: the harness records that the header was sent and keeps nothing of
            // it. That is also why the key cannot be asserted positively - and must not be.
            assertThat(stub.observedHeaders(0)).containsEntry("authorization", "");
            assertThat(stub.observedQuery(0).toString()).doesNotContain(key);
            assertThat(stub.observedHeaders(0).toString()).doesNotContain(key);
        }
    }

    @Test
    @DisplayName("BA-083-T25 a gateway with no credential refuses instead of reporting every leg as unanswered")
    void missingCredentialIsNotAnOutage() throws Exception {
        // Both outcomes stop the optimizer. Only one of them tells an operator which one happened:
        // swallowing this would make a deployment error indistinguishable from the provider being
        // down, and Absent already means "the answer never arrived".
        try (StubProviderServer stub = new StubProviderServer(); Fixture fixture = fixture(stub, "")) {
            assertThatThrownBy(() -> fixture.client.legsFor(List.of(leg(A, B))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KAKAO_REST_API_KEY");
            assertThat(stub.calls()).isZero();
        }
    }

    private static final RouteWaypoint C =
            new RouteWaypoint("c", new BigDecimal("37.5133"), new BigDecimal("127.1028"));

    private static RouteLeg leg(RouteWaypoint from, RouteWaypoint to) {
        return new RouteLeg(from, to);
    }

    private static Fixture fixture(StubProviderServer stub) {
        return fixture(stub, "fake-kakao-key-never-retained");
    }

    private static Fixture fixture(StubProviderServer stub, String key) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16));
        RetryPolicy retry = new RetryPolicy(1, Duration.ZERO, Duration.ZERO, CLOCK, () -> 0.5, ignored -> { });
        ProviderHttpClient transport = new ProviderHttpClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofMillis(500), 1024 * 1024, executor, retry,
                Map.of(KakaoRouteClient.SOURCE_CODE, Set.of("127.0.0.1")),
                Map.of(KakaoRouteClient.SOURCE_CODE,
                        new CircuitBreaker(CLOCK, 50, Duration.ofSeconds(30), Duration.ofSeconds(60))), 2);
        KakaoRouteProperties properties = new KakaoRouteProperties();
        properties.setRestApiKey(key);
        properties.setBaseUrl(stub.uri("").toString());
        return new Fixture(new KakaoRouteClient(transport, properties, "test"), executor);
    }

    private record Fixture(KakaoRouteClient client, ThreadPoolExecutor executor) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
