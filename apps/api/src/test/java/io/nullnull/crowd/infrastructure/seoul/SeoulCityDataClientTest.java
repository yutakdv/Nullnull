package io.nullnull.crowd.infrastructure.seoul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.shared.provider.CircuitBreaker;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.RetryPolicy;
import io.nullnull.testsupport.StubProviderServer;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-090 Seoul adapter through the proxy")
class SeoulCityDataClientTest {

    private static final String SOURCE = SeoulLiveAreaObservation.SOURCE_CODE;
    private static final String TOKEN = "fake-proxy-token-for-the-stub";
    private static final String AREA = "광화문·덕수궁";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T06:20:00Z"), ZoneOffset.UTC);

    private static final String PAYLOAD = """
            {"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."},
             "CITYDATA":{"AREA_NM":"광화문·덕수궁","AREA_CD":"POI009",
              "LIVE_PPLTN_STTS":[{"AREA_NM":"광화문·덕수궁","AREA_CD":"POI009",
                "AREA_CONGEST_LVL":"보통","AREA_PPLTN_MIN":"42000","AREA_PPLTN_MAX":"44000",
                "REPLACE_YN":"N","PPLTN_TIME":"2026-09-20 15:15","FCST_YN":"Y",
                "FCST_PPLTN":[{"FCST_TIME":"2026-09-20 16:00","FCST_CONGEST_LVL":"약간 붐빔",
                  "FCST_PPLTN_MIN":"40000","FCST_PPLTN_MAX":"42000"}]}]}}
            """;

    @Test
    @DisplayName("BA-090-T11 adapter 는 proxy 에 토큰을 헤더로 내고 받은 응답을 관측으로 정규화한다")
    void tokenTravelsAsAHeaderAndTheResponseNormalizes() throws Exception {
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, PAYLOAD))) {
            SeoulCityDataProperties properties = new SeoulCityDataProperties();
            // The stub's context is "/provider", and HttpServer matches contexts by prefix, so the
            // adapter's "/citydata/<area>" lands inside it.
            properties.setBaseUrl(stub.uri("").toString().replace("?", ""));
            properties.setProxyToken(TOKEN);

            ProviderHttpClient.ProviderResponse response =
                    new SeoulCityDataClient(client(), properties, "test").fetch(AREA).join();
            assertThat(response.status()).isEqualTo(200);

            // The token reached the server, and it reached it as a HEADER. Recorded present-with-empty
            // because StubProviderServer refuses to retain a credential - see CREDENTIAL_HEADERS.
            assertThat(stub.observedHeaders(0))
                    .containsEntry("x-nullnull-proxy-token", "")
                    .doesNotContainValue(TOKEN);
            // And nothing credential-shaped travelled in the query, which is empty here by design:
            // everything this request carries is a path segment the proxy will read.
            assertThat(stub.observedQuery(0)).isEmpty();

            // End to end: what the proxy returned becomes an observation, so properties, transport
            // and validator agree on the same request. Each is tested alone; this is the only place
            // that proves the three fit together.
            SeoulCityDataValidator.Validation validation =
                    new SeoulCityDataValidator().validate(response.body(), AREA);
            assertThat(validation.accepted()).isTrue();
            assertThat(validation.observation().areaCode()).isEqualTo("POI009");
            assertThat(validation.observation().observedAt()).isEqualTo(Instant.parse("2026-09-20T06:15:00Z"));
            assertThat(validation.observation().forecastPoints()).hasSize(1);
        }
    }

    @Test
    @DisplayName("BA-090-T12 서울 upstream 의 429 는 관측을 만들지 않는다")
    void rateLimitEndsAsAProviderFailureWithNoObservation() throws Exception {
        String canary = "should-never-become-an-observation";
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(429, "{\"RESULT\":{\"CODE\":\"" + canary + "\"}}",
                        java.time.Duration.ZERO, java.util.Map.of("Retry-After", "0")))) {
            SeoulCityDataProperties properties = new SeoulCityDataProperties();
            properties.setBaseUrl(stub.uri("").toString().replace("?", ""));
            properties.setProxyToken(TOKEN);
            SeoulCityDataClient adapter = new SeoulCityDataClient(client(), properties, "test");

            // The transport refuses a non-2xx before anything downstream sees it, so the body never
            // reaches the validator and there is nothing for it to normalize. A "rate limited" answer
            // is not a reading of an empty city.
            assertThatThrownBy(() -> adapter.fetch(AREA).join())
                    .hasRootCauseInstanceOf(io.nullnull.shared.provider.ProviderException.class)
                    .rootCause().satisfies(failure -> {
                        assertThat(failure.getMessage()).isEqualTo("HTTP_STATUS");
                        // And the refusal carries neither the body nor the token it was sent with.
                        assertThat(failure.toString()).doesNotContain(canary, TOKEN);
                    });
            assertThat(stub.calls()).as("one attempt, not a retry storm").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("BA-090 an adapter with no endpoint or no token refuses before it calls anything")
    void unconfiguredAdapterFailsClosed() {
        SeoulCityDataProperties blank = new SeoulCityDataProperties();
        SeoulCityDataClient adapter = new SeoulCityDataClient(client(), blank, "test");
        assertThatThrownBy(adapter::requireConfigured)
                .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_NOT_CONFIGURED");
        assertThatThrownBy(() -> adapter.fetch(AREA))
                .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_NOT_CONFIGURED");
    }

    private static ProviderHttpClient client() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8));
        RetryPolicy retry = new RetryPolicy(1, Duration.ZERO, Duration.ZERO, CLOCK, () -> 0.5, ignored -> { });
        return new ProviderHttpClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(2), 1024 * 1024, executor, retry,
                Map.of(SOURCE, Set.of("127.0.0.1")),
                Map.of(SOURCE, new CircuitBreaker(CLOCK, 5, Duration.ofSeconds(30), Duration.ofSeconds(60))), 4);
    }
}
