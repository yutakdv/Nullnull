package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.crowd.domain.ApprovalState;
import io.nullnull.crowd.domain.LicenseReviewState;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.crowd.infrastructure.seoul.SeoulCityDataClient;
import io.nullnull.crowd.infrastructure.seoul.SeoulCityDataProperties;
import io.nullnull.crowd.application.SeoulGatewayException;
import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.CircuitBreaker;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.RetryPolicy;
import io.nullnull.testsupport.StubProviderServer;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-090 Seoul collection run")
class SeoulLiveAreaGatewayTest {

    private static final String SOURCE = SeoulLiveAreaObservation.SOURCE_CODE;
    private static final String AREA = "광화문·덕수궁";
    private static final String TOKEN = "fake-proxy-token-for-the-stub";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T06:20:00Z"), ZoneOffset.UTC);
    private static final UUID INGEST_LOG_ID = UUID.fromString("0199a1f0-0000-7000-8000-00000000c0de");

    /**
     * Four responses the provider can actually send, each landing on a different verdict. Three are
     * refusals and they are NOT interchangeable: if the run recorded one constant reason for all of
     * them, an operator reading the ledger would see "Seoul was refused" and never learn whether the
     * proxy answered for the wrong area, the provider reported its own error, or the vocabulary moved.
     * The accepted one is here for the opposite reason - without it "every response is quarantined"
     * would satisfy every assertion below.
     */
    @Test
    @DisplayName("BA-090-T13 거절된 서울 응답은 해당 ValidationResult 로 collector run 에 기록된다")
    void aRefusedResponseIsRecordedWithItsOwnValidationResult() throws Exception {
        record Case(String label, String body, IngestAudit.ValidationResult expected) {
            boolean accepted() {
                return expected == IngestAudit.ValidationResult.OK;
            }
        }
        List<Case> cases = List.of(
                new Case("a reading we accept", payload("INFO-000", "보통"),
                        IngestAudit.ValidationResult.OK),
                new Case("a fifth congestion step", payload("INFO-000", "매우 붐빔"),
                        IngestAudit.ValidationResult.ENUM_DRIFT),
                new Case("the provider's own error envelope", payload("ERROR-300", "보통"),
                        IngestAudit.ValidationResult.PROVIDER_ERROR),
                new Case("someone else's area", otherAreaPayload(),
                        IngestAudit.ValidationResult.SCHEMA_DRIFT));

        for (Case example : cases) {
            try (StubProviderServer stub = new StubProviderServer()
                    .enqueue(new StubProviderServer.Response(200, example.body()))) {
                RecordingAudit audit = new RecordingAudit();
                SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                        new SourceRegistryStore.SourceCondition(false, false));

                SeoulLiveAreaGateway.Collection collection = gateway.collect(AREA).join();

                assertThat(audit.calls).as("%s: one call, recorded once", example.label()).hasSize(1);
                assertThat(audit.calls.get(0).validationResult())
                        .as("%s: the run carries the validator's own verdict", example.label())
                        .isEqualTo(example.expected());
                assertThat(audit.calls.get(0).outcome())
                        .isEqualTo(example.accepted() ? IngestAudit.CallOutcome.OK
                                : IngestAudit.CallOutcome.VALIDATION_FAILED);
                assertThat(audit.calls.get(0).httpStatus()).as("the provider did answer 200").isEqualTo(200);

                assertThat(audit.finishes).as("%s: the run is closed", example.label()).hasSize(1);
                IngestAudit.FinishRun finish = audit.finishes.get(0);
                assertThat(finish.runId()).isEqualTo(collection.runId());
                assertThat(finish.status())
                        .isEqualTo(example.accepted() ? IngestAudit.RunStatus.COMPLETED
                                : IngestAudit.RunStatus.QUARANTINED);
                // The reason an operator reads is the same word the validator said, not a generic one.
                assertThat(finish.errorCode())
                        .isEqualTo(example.accepted() ? null : example.expected().name());
                assertThat(finish.rejected()).isEqualTo(example.accepted() ? 0 : 1);

                assertThat(collection.accepted()).isEqualTo(example.accepted());
                assertThat(collection.observation().isPresent()).isEqualTo(example.accepted());
            }
        }
    }

    @Test
    @DisplayName("BA-090 upstream 이 거절하면 그 실패의 모양이 run 에 남고 관측은 없다")
    void aTransportFailureIsRecordedAsThatFailureWithNoObservation() throws Exception {
        String canary = "should-never-reach-the-ledger";
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(429, "{\"RESULT\":{\"CODE\":\"" + canary + "\"}}",
                        Duration.ZERO, Map.of("Retry-After", "0")))) {
            RecordingAudit audit = new RecordingAudit();
            SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                    new SourceRegistryStore.SourceCondition(false, false));

            assertThatThrownBy(() -> gateway.collect(AREA).join())
                    .hasRootCauseInstanceOf(io.nullnull.shared.provider.ProviderException.class);

            // HTTP_ERROR rather than a catch-all: a 429 and a socket timeout are different operational
            // stories and outcomeOf keeps them apart.
            assertThat(audit.calls).hasSize(1);
            assertThat(audit.calls.get(0).outcome()).isEqualTo(IngestAudit.CallOutcome.HTTP_ERROR);
            assertThat(audit.calls.get(0).responseCount()).isZero();
            assertThat(audit.finishes).hasSize(1);
            assertThat(audit.finishes.get(0).status()).isEqualTo(IngestAudit.RunStatus.FAILED);
            assertThat(audit.finishes.get(0).errorCode()).isEqualTo("PROVIDER_FAILED");
            // Nothing the provider said travels into the ledger - not its body, not its status text.
            assertThat(audit.toString()).doesNotContain(canary, TOKEN);
        }
    }

    @Test
    @DisplayName("BA-090 quota 가 거절하면 호출 없이 run 이 FAILED 로 닫힌다")
    void anExhaustedQuotaClosesTheRunWithoutCallingTheProvider() throws Exception {
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, payload("INFO-000", "보통")))) {
            RecordingAudit audit = new RecordingAudit();
            SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(true),
                    new SourceRegistryStore.SourceCondition(false, false));

            assertThatThrownBy(() -> gateway.collect(AREA))
                    .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_QUOTA_EXHAUSTED");

            assertThat(stub.calls()).as("the provider's allowance is not borrowed against").isZero();
            assertThat(audit.calls).as("no call was reserved, so none is recorded").isEmpty();
            assertThat(audit.finishes).hasSize(1);
            assertThat(audit.finishes.get(0).status()).isEqualTo(IngestAudit.RunStatus.FAILED);
            assertThat(audit.finishes.get(0).errorCode()).isEqualTo("QUOTA_EXHAUSTED");
        }
    }

    /**
     * Collection-time refusal, which is a different clause from BA-090-T17: that one is about an
     * observation already stored being withheld at projection time. This is about not adding a
     * reading while the source is under review at all.
     */
    @Test
    @DisplayName("BA-090 검토된 사건 window 나 격리된 직전 run 은 다음 호출을 시작시키지 않는다")
    void aSourceUnderReviewNeverStartsARun() throws Exception {
        List<SourceRegistryStore.SourceCondition> stopped = List.of(
                new SourceRegistryStore.SourceCondition(true, false),
                new SourceRegistryStore.SourceCondition(false, true));
        for (SourceRegistryStore.SourceCondition condition : stopped) {
            try (StubProviderServer stub = new StubProviderServer()
                    .enqueue(new StubProviderServer.Response(200, payload("INFO-000", "보통")))) {
                RecordingAudit audit = new RecordingAudit();
                SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false), condition);

                assertThatThrownBy(() -> gateway.collect(AREA))
                        .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_SOURCE_QUARANTINED");

                assertThat(stub.calls()).as("%s", condition).isZero();
                assertThat(audit.starts).as("%s: no run is opened either", condition).isEmpty();
            }
        }
    }

    private static SeoulLiveAreaGateway gateway(StubProviderServer stub, RecordingAudit audit,
            SourceQuotaStore quota, SourceRegistryStore.SourceCondition condition) {
        SeoulCityDataProperties properties = new SeoulCityDataProperties();
        // The stub's context is "/provider" and HttpServer matches contexts by prefix, so the
        // adapter's "/citydata/<area>" lands inside it.
        properties.setBaseUrl(stub.uri("").toString().replace("?", ""));
        properties.setProxyToken(TOKEN);
        SourceRegistryStore registryStore = new FixedRegistry(registration(), condition);
        return new SeoulLiveAreaGateway(new SourceRegistryQuery(registryStore), registryStore,
                new CollectorRunRecorder(audit, new SourceQuotaGuard(quota, CLOCK)),
                new SeoulCityDataClient(providerClient(), properties, "test"), CLOCK);
    }

    private static SourceRegistration registration() {
        return new SourceRegistration(SOURCE, "서울 실시간 도시데이터", SourceState.LIVE,
                ApprovalState.DEV_APPROVED, LicenseReviewState.RECORD_LEVEL_REVIEW_REQUIRED,
                1000, 300L, true, 2L, "seoul-citydata-v8.5", Instant.parse("2026-09-20T00:00:00Z"));
    }

    private static String payload(String resultCode, String congestionLevel) {
        return """
                {"RESULT":{"CODE":"%s","MESSAGE":"응답"},
                 "CITYDATA":{"AREA_NM":"광화문·덕수궁","AREA_CD":"POI009",
                  "LIVE_PPLTN_STTS":[{"AREA_CONGEST_LVL":"%s","REPLACE_YN":"N",
                    "PPLTN_TIME":"2026-09-20 15:15","FCST_YN":"N"}]}}
                """.formatted(resultCode, congestionLevel);
    }

    /** A well-formed answer about a different place: the proxy routed our path somewhere else. */
    private static String otherAreaPayload() {
        return """
                {"RESULT":{"CODE":"INFO-000","MESSAGE":"응답"},
                 "CITYDATA":{"AREA_NM":"강남역","AREA_CD":"POI001",
                  "LIVE_PPLTN_STTS":[{"AREA_CONGEST_LVL":"보통","REPLACE_YN":"N",
                    "PPLTN_TIME":"2026-09-20 15:15","FCST_YN":"N"}]}}
                """;
    }

    private static ProviderHttpClient providerClient() {
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

    private static final class RecordingAudit implements IngestAudit {
        private final List<StartRun> starts = new ArrayList<>();
        private final List<CallRecord> calls = new ArrayList<>();
        private final List<FinishRun> finishes = new ArrayList<>();

        @Override
        public UUID startRun(StartRun command) {
            starts.add(command);
            return command.runId();
        }

        @Override
        public void record(CallRecord record) {
            calls.add(record);
        }

        @Override
        public void finishRun(FinishRun command) {
            finishes.add(command);
        }

        @Override
        public String toString() {
            return starts + " " + calls + " " + finishes;
        }
    }

    private record FixedRegistry(SourceRegistration registration, SourceCondition condition)
            implements SourceRegistryStore {

        @Override
        public List<SourceRegistration> findAll() {
            return List.of(registration);
        }

        @Override
        public Optional<SourceRegistration> findByCode(String code) {
            return SOURCE.equals(code) ? Optional.of(registration) : Optional.empty();
        }

        @Override
        public SourceCondition conditionAt(String code, Instant at) {
            return condition;
        }
    }

    private record FixedQuota(boolean exhausted) implements SourceQuotaStore {

        @Override
        public Reservation reserve(ReservationRequest request, Instant dayStart, Instant nextDayStart) {
            if (exhausted) {
                throw new QuotaExhaustedException();
            }
            return new Reservation(INGEST_LOG_ID, 1, 1000, List.of());
        }
    }
}
