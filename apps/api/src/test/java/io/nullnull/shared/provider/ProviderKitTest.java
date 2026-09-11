package io.nullnull.shared.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.StubProviderServer;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("BA-020 provider adapter kit")
class ProviderKitTest {

    private static final String SOURCE = "KTO_KOR_SERVICE_2";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);
    private static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["state"],"properties":{"state":{"enum":["A","B"]}},
             "additionalProperties":false}
            """;

    @Test
    @DisplayName("BA-020-T1 429 and timeout follow bounded retry and sanitized circuit rules")
    void retriesAndCircuitAreBounded() throws Exception {
        String canary = "fake-secret-key-never-retain";
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(429, "{}", Duration.ZERO,
                        Map.of("Retry-After", "0")))
                .enqueue(new StubProviderServer.Response(200, "{\"state\":\"A\"}"));
             ClientFixture fixture = fixture(3, Duration.ofMillis(200), 5)) {
            assertThat(fixture.client.get(SOURCE, stub.uri("serviceKey=" + canary)).join().status()).isEqualTo(200);
            assertThat(stub.calls()).isEqualTo(2);
        }

        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, "{}", Duration.ofMillis(200), Map.of()));
             ClientFixture fixture = fixture(1, Duration.ofMillis(25), 5)) {
            assertThatThrownBy(() -> fixture.client.get(SOURCE, stub.uri("serviceKey=" + canary)).join())
                    .hasRootCauseInstanceOf(ProviderException.class)
                    .rootCause().extracting(Throwable::getMessage).isEqualTo("TIMEOUT");
        }

        try (StubProviderServer stub = new StubProviderServer(); ClientFixture fixture = fixture(1,
                Duration.ofMillis(200), 5)) {
            for (int index = 0; index < 5; index++) {
                stub.enqueue(new StubProviderServer.Response(500, "provider says " + canary));
                assertThatThrownBy(() -> fixture.client.get(SOURCE, stub.uri("serviceKey=" + canary)).join())
                        .hasRootCauseInstanceOf(ProviderException.class)
                        .rootCause().satisfies(failure -> {
                            assertThat(failure.getMessage()).isEqualTo("HTTP_STATUS");
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.toString()).doesNotContain(canary, "serviceKey", "provider says");
                        });
            }
            assertThatThrownBy(() -> fixture.client.get(SOURCE, stub.uri("serviceKey=" + canary)).join())
                    .hasRootCauseMessage("CIRCUIT_OPEN");
            assertThat(stub.calls()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("BA-020-T1 malformed enum semantic and incident responses are quarantined before writes")
    void responseDriftIsClassified() {
        ProviderResponseValidator validator = new ProviderResponseValidator(JsonMapper.builder().build(), SCHEMA);
        assertThat(validator.validate("{".getBytes(), false, List.of()).outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        assertThat(validator.validate("{\"state\":\"NEW\"}".getBytes(), false, List.of()).outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        assertThat(validator.validate("{\"state\":\"A\"}".getBytes(), false,
                List.of(body -> java.util.Optional.of(ProviderResponseValidator.Outcome.RANGE))).outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.RANGE);
        assertThat(validator.validate("{\"state\":\"A\"}".getBytes(), true, List.of()).outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        assertThat(validator.validate("{\"response\":{\"header\":{\"resultCode\":\"22\"}}}".getBytes(),
                false, List.of()).outcome()).isEqualTo(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        assertThat(validator.validate("{\"state\":\"A\"}".getBytes(), false, List.of()))
                .matches(ProviderResponseValidator.Verdict::permitsCanonicalWrite);
    }

    @Test
    @DisplayName("BA-020-T2 SingleFlight makes one provider call for concurrent identical work")
    void singleFlightCoalescesConcurrentCalls() throws Exception {
        SingleFlight<String, Integer> flights = new SingleFlight<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> follower = new CompletableFuture<>();
        Thread followerThread = null;
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Integer> leader = CompletableFuture.supplyAsync(() -> flights.execute("same", () -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return 42;
            }), callers);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            followerThread = Thread.ofPlatform().start(() -> {
                try {
                    follower.complete(flights.execute("same", () -> {
                        calls.incrementAndGet();
                        return 43;
                    }));
                } catch (Throwable failure) {
                    follower.completeExceptionally(failure);
                }
            });
            assertThat(waiting(followerThread, Duration.ofSeconds(1))).isTrue();
            release.countDown();
            assertThat(leader.join()).isEqualTo(42);
            assertThat(follower.join()).isEqualTo(42);
            followerThread.join(1_000);
            assertThat(followerThread.isAlive()).isFalse();
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            if (followerThread != null) {
                followerThread.join(1_000);
            }
        }
    }

    private static boolean waiting(Thread thread, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return true;
            }
            if (state == Thread.State.TERMINATED) {
                return false;
            }
            Thread.sleep(1);
        }
        return false;
    }

    @Test
    @DisplayName("REC-DATA-05 redirect and non-allowlisted hosts never reach transport")
    void exactHostPolicyFailsClosed() {
        try (ClientFixture fixture = fixture(1, Duration.ofMillis(100), 5)) {
            assertThatThrownBy(() -> fixture.client.get(SOURCE,
                    java.net.URI.create("https://apis.data.go.kr.evil.example/provider?serviceKey=canary")))
                    .isInstanceOf(ProviderException.class).hasMessage("HOST_NOT_ALLOWED");
            assertThatThrownBy(() -> fixture.client.get(SOURCE,
                    java.net.URI.create("http://apis.data.go.kr/provider")))
                    .isInstanceOf(ProviderException.class).hasMessage("HOST_NOT_ALLOWED");
        }
    }

    @Test
    @DisplayName("BA-020-T1 production source hosts cannot drift while test stubs remain isolated")
    void productionHostPolicyIsExact() {
        Map<String, Set<String>> reviewed = Map.of(
                "KTO_KOR_SERVICE_2", Set.of("apis.data.go.kr"),
                "KTO_CONCENTRATION_FORECAST", Set.of("apis.data.go.kr"),
                "KTO_RELATED_PLACES", Set.of("apis.data.go.kr"),
                "SEOUL_CITYDATA", Set.of("openapi.seoul.go.kr"));
        assertThatCode(() -> new ProviderHostPolicy("production").validate(reviewed))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ProviderHostPolicy("production").validate(Map.of(
                "KTO_KOR_SERVICE_2", Set.of("127.0.0.1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("production provider hosts must match the reviewed exact allowlist");
        assertThatCode(() -> new ProviderHostPolicy("test").validate(Map.of(
                SOURCE, Set.of("127.0.0.1")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-020-T1 provider retry and circuit configuration fail closed on invalid limits")
    void providerPropertiesRejectInvalidLimits() {
        assertThatThrownBy(() -> new ProviderClientProperties.Retry(0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nullnull.provider.retry limits are invalid");
        assertThatThrownBy(() -> new ProviderClientProperties.Retry(1, Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderClientProperties.Circuit(0, Duration.ofSeconds(1),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nullnull.provider.circuit limits are invalid");
        assertThatThrownBy(() -> new ProviderClientProperties.Circuit(1, Duration.ZERO,
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ClientFixture fixture(int attempts, Duration timeout, int circuitThreshold) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16));
        RetryPolicy retry = new RetryPolicy(attempts, Duration.ZERO, Duration.ZERO, CLOCK, () -> 0.5,
                ignored -> { });
        CircuitBreaker circuit = new CircuitBreaker(CLOCK, circuitThreshold, Duration.ofSeconds(30),
                Duration.ofSeconds(60));
        ProviderHttpClient client = new ProviderHttpClient(
                HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(),
                timeout, 1024 * 1024, executor, retry, Map.of(SOURCE, Set.of("127.0.0.1")),
                Map.of(SOURCE, circuit), 4);
        return new ClientFixture(client, executor);
    }

    private record ClientFixture(ProviderHttpClient client, ThreadPoolExecutor executor) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
