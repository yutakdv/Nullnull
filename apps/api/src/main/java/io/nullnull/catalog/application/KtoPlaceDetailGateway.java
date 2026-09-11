package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderException;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import io.nullnull.shared.provider.SingleFlight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** C2 read-through cache for the one approved KTO detail operation. It has no public HTTP endpoint yet. */
@Service
public class KtoPlaceDetailGateway {

    private static final String SOURCE_CODE = KtoPlaceSnapshot.SOURCE_CODE;
    private static final String ENDPOINT_KEY = "KOR_SERVICE_2_DETAIL_COMMON_2";

    private final KtoPlaceSnapshotStore snapshots;
    private final SourceRegistryQuery registry;
    private final SourceRegistryStore registryStore;
    private final CollectorRunRecorder collector;
    private final KtoPlaceDetailFetcher fetcher;
    private final KtoDetailResponseValidator validator;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SingleFlight<String, KtoPlaceSnapshot> flights = new SingleFlight<>();

    @Autowired
    public KtoPlaceDetailGateway(KtoPlaceSnapshotStore snapshots, SourceRegistryQuery registry,
            SourceRegistryStore registryStore, CollectorRunRecorder collector, KtoPlaceDetailFetcher fetcher,
            Clock clock, PlatformTransactionManager transactionManager) {
        this(snapshots, registry, registryStore, collector, fetcher, new KtoDetailResponseValidator(), clock,
                transactionManager);
    }

    KtoPlaceDetailGateway(KtoPlaceSnapshotStore snapshots, SourceRegistryQuery registry,
            SourceRegistryStore registryStore, CollectorRunRecorder collector, KtoPlaceDetailFetcher fetcher,
            KtoDetailResponseValidator validator, Clock clock, PlatformTransactionManager transactionManager) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public CompletableFuture<KtoPlaceSnapshot> detail(String contentId, String contentTypeId) {
        return detail(new KtoPlaceRequest(contentId, contentTypeId));
    }

    public CompletableFuture<KtoPlaceSnapshot> detail(KtoPlaceRequest request) {
        Instant now = clock.instant();
        requireHealthySource(now);
        return snapshots.findFresh(request, now).map(CompletableFuture::completedFuture)
                .orElseGet(() -> flights.executeAsync(request.cacheKey(), () -> refreshIfStillNeeded(request)));
    }

    private CompletableFuture<KtoPlaceSnapshot> refreshIfStillNeeded(KtoPlaceRequest request) {
        Instant now = clock.instant();
        SourceRegistration source = requireHealthySource(now);
        return snapshots.findFresh(request, now).map(CompletableFuture::completedFuture)
                .orElseGet(() -> refresh(request, source, now));
    }

    private CompletableFuture<KtoPlaceSnapshot> refresh(KtoPlaceRequest request, SourceRegistration source,
            Instant startedAt) {
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
            reservation = collector.reserve(runId, SOURCE_CODE, ENDPOINT_KEY, "kto-detail-" + UUID.randomUUID(),
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

    private KtoPlaceSnapshot acceptOrQuarantine(UUID runId, SourceQuotaStore.Reservation reservation,
            ProviderResponse response, KtoPlaceRequest request, SourceRegistration source, Instant fetchedAt,
            int duration) {
        KtoDetailResponseValidator.Validation validation = validator.validate(response.body(), request,
                source.currentRevision(), runId, fetchedAt, Duration.ofSeconds(source.staleAfterSeconds()));
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
                snapshots.save(validation.snapshot());
                boolean accepted = collector.finalizeSingleCall(runId, reservation.ingestLogId(), response.status(),
                        duration, validation.responseCount(), validation.snapshot().payloadHash(), validation.verdict(),
                        clock.instant());
                if (!accepted) {
                    throw new IllegalStateException("valid KTO detail was not accepted");
                }
                return validation.snapshot();
            }));
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> collector.failSingleCall(runId, reservation.ingestLogId(),
                        IngestAudit.CallOutcome.IO_ERROR, response.status(), duration, "PERSISTENCE_FAILED",
                        clock.instant()));
            } catch (RuntimeException ignored) {
                // The only safe caller-visible result remains a stable code; never surface a JDBC/provider message.
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
}
