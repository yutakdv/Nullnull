package io.nullnull.crowd.application;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderException;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import io.nullnull.shared.provider.SingleFlight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator/background-only C4 collector. Public reads use only persisted, validated snapshots, so
 * a browser request cannot turn an unvalidated provider body into a displayed forecast.
 */
@Service
public class KtoCrowdForecastGateway {

    private static final String SOURCE_CODE = KtoForecastSnapshotSet.SOURCE_CODE;
    private static final String ENDPOINT_KEY = "TATS_CNCTR_RATE_LIST";

    private final KtoForecastSnapshotStore snapshots;
    private final SourceRegistryQuery registry;
    private final SourceRegistryStore registryStore;
    private final CollectorRunRecorder collector;
    private final KtoForecastFetcher fetcher;
    private final KtoForecastResponseValidator validator;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SingleFlight<String, RefreshResult> flights = new SingleFlight<>();

    @Autowired
    public KtoCrowdForecastGateway(KtoForecastSnapshotStore snapshots, SourceRegistryQuery registry,
            SourceRegistryStore registryStore, CollectorRunRecorder collector, KtoForecastFetcher fetcher,
            Clock clock, PlatformTransactionManager transactionManager) {
        this(snapshots, registry, registryStore, collector, fetcher, new KtoForecastResponseValidator(), clock,
                transactionManager);
    }

    KtoCrowdForecastGateway(KtoForecastSnapshotStore snapshots, SourceRegistryQuery registry,
            SourceRegistryStore registryStore, CollectorRunRecorder collector, KtoForecastFetcher fetcher,
            KtoForecastResponseValidator validator, Clock clock, PlatformTransactionManager transactionManager) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Refreshes a canonical place mapping once; concurrent calls for the same canonical UUID coalesce. */
    public CompletableFuture<RefreshResult> refresh(KtoForecastRequest request) {
        Objects.requireNonNull(request, "request");
        requireHealthySource(clock.instant());
        return flights.executeAsync(request.cacheKey(), () -> refreshIfStillNeeded(request));
    }

    /** Future read-through callers may ask only for a fresh, verified C2 mapping. */
    public CompletableFuture<RefreshResult> refreshFromKnownPlace(UUID placeId) {
        Instant now = clock.instant();
        requireHealthySource(now);
        return snapshots.findFreshRequest(placeId, now).map(this::refresh)
                .orElseGet(() -> CompletableFuture.completedFuture(RefreshResult.noCoverage()));
    }

    private CompletableFuture<RefreshResult> refreshIfStillNeeded(KtoForecastRequest request) {
        Instant startedAt = clock.instant();
        SourceRegistration source = requireHealthySource(startedAt);
        try {
            fetcher.requireConfigured();
        } catch (KtoGatewayException failure) {
            return CompletableFuture.failedFuture(failure);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new KtoGatewayException(KtoGatewayException.Code.KTO_NOT_CONFIGURED));
        }

        UUID runId = collector.start(SOURCE_CODE, IngestAudit.TriggerType.READ_THROUGH,
                source.providerSchemaVersion(), startedAt);
        SourceQuotaStore.Reservation reservation;
        try {
            reservation = collector.reserve(runId, SOURCE_CODE, ENDPOINT_KEY, "kto-forecast-" + UUID.randomUUID(),
                    fetcher.releaseVersion());
        } catch (QuotaExhaustedException failure) {
            finishWithoutCall(runId, "QUOTA_EXHAUSTED");
            return CompletableFuture.failedFuture(new KtoGatewayException(KtoGatewayException.Code.KTO_QUOTA_EXHAUSTED));
        } catch (RuntimeException failure) {
            try {
                finishWithoutCall(runId, "RESERVATION_FAILED");
            } catch (RuntimeException ignored) {
                return CompletableFuture.failedFuture(new KtoGatewayException(
                        KtoGatewayException.Code.KTO_PERSISTENCE_FAILED));
            }
            return CompletableFuture.failedFuture(new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED));
        }

        long transportStarted = System.nanoTime();
        CompletableFuture<ProviderResponse> response;
        try {
            response = fetcher.fetch(request);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(recordTransportFailure(runId, reservation, failure,
                    elapsedMillis(transportStarted)));
        }
        return response.handle((providerResponse, failure) -> {
            int duration = elapsedMillis(transportStarted);
            if (failure != null) {
                throw recordTransportFailure(runId, reservation, failure, duration);
            }
            return acceptOrQuarantine(runId, reservation, providerResponse, request, source, startedAt, duration);
        });
    }

    private RefreshResult acceptOrQuarantine(UUID runId, SourceQuotaStore.Reservation reservation,
            ProviderResponse response, KtoForecastRequest request, SourceRegistration source, Instant fetchedAt,
            int duration) {
        Duration staleAfter = Duration.ofSeconds(source.staleAfterSeconds());
        KtoForecastResponseValidator.Validation validation = validator.validate(response.body(), request,
                source.currentRevision(), runId, fetchedAt, staleAfter);
        if (!validation.accepted()) {
            try {
                transactions.executeWithoutResult(status -> collector.finalizeSingleCall(runId, reservation.ingestLogId(),
                        response.status(), duration, validation.responseCount(), null, validation.verdict(),
                        clock.instant()));
            } catch (RuntimeException failure) {
                throw new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
            }
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_RESPONSE_REJECTED);
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                if (validation.hasCoverage()) {
                    snapshots.save(request, validation.snapshotSet());
                }
                String payloadHash = validation.hasCoverage() ? validation.snapshotSet().payloadHash() : null;
                boolean accepted = collector.finalizeSingleCall(runId, reservation.ingestLogId(), response.status(),
                        duration, validation.responseCount(), payloadHash, validation.verdict(), clock.instant());
                if (!accepted) {
                    throw new IllegalStateException("valid KTO forecast was not accepted");
                }
                return new RefreshResult(Optional.ofNullable(validation.snapshotSet()));
            }));
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> collector.failSingleCall(runId, reservation.ingestLogId(),
                        IngestAudit.CallOutcome.IO_ERROR, response.status(), duration, "PERSISTENCE_FAILED",
                        clock.instant()));
            } catch (RuntimeException ignored) {
                // The caller receives only a stable code; JDBC/provider details never cross this boundary.
            }
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
        }
    }

    private KtoGatewayException recordTransportFailure(UUID runId, SourceQuotaStore.Reservation reservation,
            Throwable failure, int duration) {
        ProviderException providerFailure = providerFailure(failure);
        IngestAudit.CallOutcome outcome = providerFailure == null ? IngestAudit.CallOutcome.IO_ERROR
                : switch (providerFailure.category()) {
                    case HTTP_STATUS -> IngestAudit.CallOutcome.HTTP_ERROR;
                    case TIMEOUT -> IngestAudit.CallOutcome.TIMEOUT;
                    case CIRCUIT_OPEN -> IngestAudit.CallOutcome.CIRCUIT_OPEN;
                    default -> IngestAudit.CallOutcome.IO_ERROR;
                };
        Integer status = providerFailure == null ? null : providerFailure.httpStatus();
        String errorCode = providerFailure == null ? "UNCLASSIFIED_TRANSPORT" : providerFailure.category().name();
        try {
            transactions.executeWithoutResult(transaction -> collector.failSingleCall(runId, reservation.ingestLogId(),
                    outcome, status, duration, errorCode, clock.instant()));
        } catch (RuntimeException ignored) {
            return new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
        }
        return new KtoGatewayException(KtoGatewayException.Code.KTO_TRANSPORT_FAILED);
    }

    private void finishWithoutCall(UUID runId, String errorCode) {
        transactions.executeWithoutResult(status -> collector.failRun(runId, errorCode, clock.instant()));
    }

    private SourceRegistration requireHealthySource(Instant at) {
        SourceRegistration source = registry.find(SOURCE_CODE).filter(SourceRegistration::collectionEnabled)
                .orElseThrow(() -> new KtoGatewayException(KtoGatewayException.Code.SOURCE_DISABLED));
        SourceRegistryStore.SourceCondition condition = registryStore.conditionAt(SOURCE_CODE, at);
        if (condition.incidentActive() || condition.latestRunQuarantined()) {
            throw new KtoGatewayException(KtoGatewayException.Code.SOURCE_QUARANTINED);
        }
        return source;
    }

    private static ProviderException providerFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof ProviderException provider ? provider : null;
    }

    private static int elapsedMillis(long startedNanos) {
        long elapsed = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    public record RefreshResult(Optional<KtoForecastSnapshotSet> snapshotSet) {
        public RefreshResult {
            snapshotSet = snapshotSet == null ? Optional.empty() : snapshotSet;
        }

        static RefreshResult noCoverage() {
            return new RefreshResult(Optional.empty());
        }

        public boolean hasCoverage() {
            return snapshotSet.isPresent();
        }
    }
}
