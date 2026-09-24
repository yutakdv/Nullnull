package io.nullnull.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-090 Seoul collection run")
class SeoulLiveAreaGatewayTest {

    private static final String SOURCE = SeoulLiveAreaObservation.SOURCE_CODE;
    private static final String AREA = "광화문·덕수궁";
    private static final String TOKEN = "fake-proxy-token-for-the-stub";
    /**
     * Three minutes after the reading below, which is inside the source's 300-second window. The
     * first version of this file sat exactly ON the boundary - observed 06:15, clock 06:20, window
     * 300s - and the reading came out STALE. That is correct behaviour and a terrible fixture: the
     * accepted case would have been asserting the expired branch by accident.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T06:18:00Z"), ZoneOffset.UTC);
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
                RecordingAreas areas = new RecordingAreas();
                RecordingSnapshots snapshots = new RecordingSnapshots();
                SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                        new SourceRegistryStore.SourceCondition(false, false), areas, snapshots);

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
                // Which refusal decides how the run closes (A-065): the provider's own error is a FAILED
                // run the next tick retries, anything else still quarantines. BA-090-T19 holds that split.
                assertThat(finish.status())
                        .isEqualTo(example.accepted() ? IngestAudit.RunStatus.COMPLETED
                                : example.expected() == IngestAudit.ValidationResult.PROVIDER_ERROR
                                        ? IngestAudit.RunStatus.FAILED : IngestAudit.RunStatus.QUARANTINED);
                // The reason an operator reads is the same word the validator said, not a generic one.
                assertThat(finish.errorCode())
                        .isEqualTo(example.accepted() ? null : example.expected().name());
                assertThat(finish.rejected()).isEqualTo(example.accepted() ? 0 : 1);

                assertThat(collection.accepted()).isEqualTo(example.accepted());
                assertThat(collection.observation().isPresent()).isEqualTo(example.accepted());
                // A refused run leaves nothing behind. THIS PAIR IS HELD BY THE VALIDATOR'S
                // SHAPE, not by an ordering this case could break: a non-OK verdict carries a null
                // observation, so there is nothing for the gateway to store even if it tried. It is
                // asserted because the pairing is the property, not because a gateway change alone
                // could break it - the ordering claim is measured separately, below.
                assertThat(snapshots.saved).as("%s: stored readings", example.label())
                        .hasSize(example.accepted() ? 1 : 0);
                assertThat(areas.upserted).as("%s: upserted areas", example.label())
                        .hasSize(example.accepted() ? 1 : 0);
                if (example.accepted()) {
                    SeoulLiveSnapshotStore.Reading stored = snapshots.saved.get(0);
                    assertThat(stored.collectorRunId()).isEqualTo(collection.runId());
                    assertThat(stored.liveAreaId()).isEqualTo(RecordingAreas.AREA_ID);
                    assertThat(stored.sourceState()).isEqualTo(io.nullnull.crowd.domain.SourceState.LIVE);
                    assertThat(stored.observedAt()).isEqualTo(Instant.parse("2026-09-20T06:15:00Z"));
                    assertThat(stored.staleAt()).isEqualTo(stored.observedAt().plusSeconds(300));
                    assertThat(areas.upserted.get(0).externalId()).isEqualTo("POI009");
                    // 보통 is cell 2 of the product scale (A-060). The stage travels from the
                    // provider's word to a reviewed digit here and nowhere else.
                    assertThat(stored.ordinalLevel()).isEqualTo("2");
                }
            }
        }
    }

    /**
     * The provider saying so itself is not drift: its own error code, or its own "this reading is a
     * substitute" flag, tells us nothing about whether we still understand its shape. Quarantining on
     * one of them shut SEOUL_CITYDATA for about ten hours on 2026-09-23, because a QUARANTINED latest
     * run stops every later tick before it asks again (A-065). Such a run now closes FAILED, which the
     * next tick does not stop at; BA-090-T20 measures that against the real database.
     *
     * <p>The drift case is the control. Without it, a gateway that closed every refusal FAILED - which
     * would turn the quarantine off - satisfies both provider cases.
     */
    @Test
    @DisplayName("BA-090-T19 서울 제공자가 스스로 선언한 오류는 run 을 FAILED 로 닫는다")
    void aProviderDeclaredErrorClosesTheRunFailed() throws Exception {
        record Case(String label, String body, IngestAudit.RunStatus status, String errorCode) {
        }
        List<Case> cases = List.of(
                new Case("the provider's own error code", payload("ERROR-300", "보통"),
                        IngestAudit.RunStatus.FAILED, "PROVIDER_ERROR"),
                new Case("the provider's own substitution flag",
                        payload("INFO-000", "보통").replace("\"REPLACE_YN\":\"N\"", "\"REPLACE_YN\":\"Y\""),
                        IngestAudit.RunStatus.FAILED, "PROVIDER_ERROR"),
                new Case("a fifth congestion step", payload("INFO-000", "매우 붐빔"),
                        IngestAudit.RunStatus.QUARANTINED, "ENUM_DRIFT"));

        for (Case example : cases) {
            try (StubProviderServer stub = new StubProviderServer()
                    .enqueue(new StubProviderServer.Response(200, example.body()))) {
                RecordingAudit audit = new RecordingAudit();
                RecordingSnapshots snapshots = new RecordingSnapshots();
                SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                        new SourceRegistryStore.SourceCondition(false, false), new RecordingAreas(), snapshots);

                SeoulLiveAreaGateway.Collection collection = gateway.collect(AREA).join();

                assertThat(audit.finishes).as("%s: the run is closed", example.label()).hasSize(1);
                assertThat(audit.finishes.get(0).status()).as("%s: how it closed", example.label())
                        .isEqualTo(example.status());
                assertThat(audit.finishes.get(0).errorCode()).as("%s: with the verdict's own word", example.label())
                        .isEqualTo(example.errorCode());
                assertThat(collection.accepted()).as("%s: refused either way", example.label()).isFalse();
                assertThat(snapshots.saved).as("%s: and nothing stored", example.label()).isEmpty();
            }
        }
    }

    /**
     * BA-090-T20 at the gateway: after a provider-declared error the next collection reaches the provider again; after
     * drift it is stopped before the call. The drift case is the control - without it a gateway that never stopped
     * would pass the first half.
     */
    @Test
    @DisplayName("BA-090-T20 제공자 오류로 닫힌 run 뒤의 다음 수집은 제공자까지 간다")
    void theNextCollectionAfterAProviderErrorReachesTheProvider() throws Exception {
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, payload("ERROR-300", "보통")))
                .enqueue(new StubProviderServer.Response(200, payload("INFO-000", "보통")))) {
            RecordingAudit audit = new RecordingAudit();
            SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                    new LedgerRegistry(registration(), audit), new RecordingAreas(), new RecordingSnapshots());

            assertThat(gateway.collect(AREA).join().accepted()).as("the provider's own error").isFalse();
            assertThat(gateway.collect(AREA).join().accepted()).as("the next tick asked again and got a reading").isTrue();
            assertThat(stub.calls()).isEqualTo(2);
        }
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, payload("INFO-000", "매우 붐빔")))
                .enqueue(new StubProviderServer.Response(200, payload("INFO-000", "보통")))) {
            RecordingAudit audit = new RecordingAudit();
            SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                    new LedgerRegistry(registration(), audit), new RecordingAreas(), new RecordingSnapshots());

            assertThat(gateway.collect(AREA).join().accepted()).as("drift").isFalse();
            assertThatThrownBy(() -> gateway.collect(AREA))
                    .isInstanceOf(SeoulGatewayException.class).hasMessage("SEOUL_SOURCE_QUARANTINED");
            assertThat(stub.calls()).as("the quarantine stopped the second call before it went out").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("BA-090 관측은 run 이 닫힌 뒤에 저장된다")
    void theReadingIsWrittenAfterTheRunIsFinalized() throws Exception {
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, payload("INFO-000", "보통")))) {
            // One counter, two fakes: the step each side was called at is comparable, which is the
            // only way a single-threaded ordering claim can be measured at all. Asserting "both
            // happened" would be true in either order.
            AtomicInteger order = new AtomicInteger();
            RecordingAudit audit = new RecordingAudit(order);
            RecordingSnapshots snapshots = new RecordingSnapshots(order);
            SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                    new SourceRegistryStore.SourceCondition(false, false), new RecordingAreas(), snapshots);

            assertThat(gateway.collect(AREA).join().accepted()).isTrue();

            // A snapshot whose run says QUARANTINED is a reading the ledger disowns, and no reader
            // knows to ignore it. A finalized run with no snapshot is the other way round and is
            // recoverable: the next collection writes one and the ledger still says what happened.
            assertThat(snapshots.savedAtStep).as("the reading is written after the run is closed")
                    .isGreaterThan(audit.finishedAtStep);
            assertThat(audit.finishedAtStep).isPositive();
        }
    }

    @Test
    @DisplayName("BA-090 창 밖에서 도착한 관측은 LIVE 가 아니라 STALE 로 저장된다")
    void aReadingOlderThanTheSourceWindowIsStoredStale() throws Exception {
        // Observed at 05:15 KST-converted, which is an hour before this test's clock and far outside
        // the 300-second window. It is still a true statement about the past, so it is kept - and it
        // is kept as STALE with NO expiry, because crowd_snapshots_staleness_check requires
        // stale_at > fetched_at and there is no honest value that satisfies that. Dropping it would
        // lose an observation; moving its expiry forward would claim it is current.
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, expiredPayload()))) {
            RecordingAudit audit = new RecordingAudit();
            RecordingSnapshots snapshots = new RecordingSnapshots();
            SeoulLiveAreaGateway gateway = gateway(stub, audit, new FixedQuota(false),
                    new SourceRegistryStore.SourceCondition(false, false), new RecordingAreas(), snapshots);

            assertThat(gateway.collect(AREA).join().accepted()).isTrue();

            assertThat(snapshots.saved).hasSize(1);
            SeoulLiveSnapshotStore.Reading stored = snapshots.saved.get(0);
            assertThat(stored.sourceState()).isEqualTo(io.nullnull.crowd.domain.SourceState.STALE);
            assertThat(stored.staleAt()).isNull();
            assertThat(stored.observedAt()).isEqualTo(Instant.parse("2026-09-20T05:15:00Z"));
            // The run itself is a normal accepted one: being old is not being wrong.
            assertThat(audit.finishes.get(0).status()).isEqualTo(IngestAudit.RunStatus.COMPLETED);
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
        return gateway(stub, audit, quota, condition, new RecordingAreas(), new RecordingSnapshots());
    }

    private static SeoulLiveAreaGateway gateway(StubProviderServer stub, RecordingAudit audit,
            SourceQuotaStore quota, SourceRegistryStore.SourceCondition condition,
            RecordingAreas areas, RecordingSnapshots snapshots) {
        return gateway(stub, audit, quota, new FixedRegistry(registration(), condition), areas, snapshots);
    }

    private static SeoulLiveAreaGateway gateway(StubProviderServer stub, RecordingAudit audit,
            SourceQuotaStore quota, SourceRegistryStore registryStore,
            RecordingAreas areas, RecordingSnapshots snapshots) {
        SeoulCityDataProperties properties = new SeoulCityDataProperties();
        // The stub's context is "/provider" and HttpServer matches contexts by prefix, so the
        // adapter's "/citydata/<area>" lands inside it.
        properties.setBaseUrl(stub.uri("").toString().replace("?", ""));
        properties.setProxyToken(TOKEN);
        return new SeoulLiveAreaGateway(new SourceRegistryQuery(registryStore), registryStore,
                new CollectorRunRecorder(audit, new SourceQuotaGuard(quota, CLOCK)),
                new SeoulCityDataClient(providerClient(), properties, "test"), areas, snapshots, CLOCK);
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

    /** The same area, reported an hour before this test's clock: outside the source's window. */
    private static String expiredPayload() {
        return payload("INFO-000", "보통").replace("2026-09-20 15:15", "2026-09-20 14:15");
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
        private final AtomicInteger order;
        private int finishedAtStep;

        RecordingAudit() {
            this(new AtomicInteger());
        }

        RecordingAudit(AtomicInteger order) {
            this.order = order;
        }

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
            finishedAtStep = order.incrementAndGet();
        }

        @Override
        public String toString() {
            return starts + " " + calls + " " + finishes;
        }
    }

    /**
     * Answers {@code conditionAt} the way JdbcSourceRegistryStore does - is the latest run QUARANTINED - from the runs
     * the gateway under test closed itself, so two collections in a row see what the first one left. That the SQL
     * answers the same way is SeoulProviderErrorRetryIT's measurement, not this fake's claim.
     */
    private record LedgerRegistry(SourceRegistration registration, RecordingAudit audit) implements SourceRegistryStore {

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
            boolean quarantined = !audit.finishes.isEmpty()
                    && audit.finishes.get(audit.finishes.size() - 1).status() == IngestAudit.RunStatus.QUARANTINED;
            return new SourceCondition(false, quarantined);
        }

        @Override
        public Optional<ReleasedRun> releaseLatestQuarantine(String code, String incidentCode, Instant reviewedAt) {
            throw new UnsupportedOperationException("the collection path must never release a quarantine");
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

        /**
         * Throws rather than answering, because nothing on the gateway's path may release a quarantine -
         * only the operator command does. If the gateway ever started calling this, every test here would
         * go red instead of a fake quietly saying "nothing to release".
         */
        @Override
        public Optional<ReleasedRun> releaseLatestQuarantine(String code, String incidentCode, Instant reviewedAt) {
            throw new UnsupportedOperationException("the collection path must never release a quarantine");
        }
    }

    private static final class RecordingAreas implements io.nullnull.live.application.LiveAreaStore {
        private static final UUID AREA_ID = UUID.fromString("0199a1f0-0000-7000-8000-00000000a4ea");
        private final List<AreaUpsert> upserted = new ArrayList<>();

        @Override
        public List<StoredArea> replaceAreas(String sourceCode, List<AreaUpsert> published) {
            throw new UnsupportedOperationException("the gateway upserts one area, never a list");
        }

        @Override
        public StoredArea upsertArea(String sourceCode, AreaUpsert area) {
            upserted.add(area);
            return new StoredArea(AREA_ID, area.externalId(), area.name(), "ACTIVE");
        }

        @Override
        public List<StoredArea> activeAreas(String sourceCode) {
            return List.of();
        }
    }

    private static final class RecordingSnapshots implements SeoulLiveSnapshotStore {
        private final List<Reading> saved = new ArrayList<>();
        private final AtomicInteger order;
        private int savedAtStep;

        RecordingSnapshots() {
            this(new AtomicInteger());
        }

        RecordingSnapshots(AtomicInteger order) {
            this.order = order;
        }

        @Override
        public void save(Reading reading) {
            saved.add(reading);
            savedAtStep = order.incrementAndGet();
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
