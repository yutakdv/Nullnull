package io.nullnull.catalog.application;

import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Found;
import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Gone;
import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Rejected;
import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Result;
import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderException;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collects the English name and address of one owner-linked EngService record and decides, in one
 * transaction, whether the place serves it (BA-086). The provider call is made outside any database
 * transaction, with the same collector run, quota reservation and ingest log the Korean gateway keeps.
 *
 * <p>What stops stale English text being shown:
 * <ul>
 * <li>the record is gone (a well-formed zero-item answer) - the text is removed, and the source is not
 *     quarantined, because a dropped record is an answer, not drift (T3, T24);</li>
 * <li>the record no longer satisfies the owner's link rule - the text is removed (T25);</li>
 * <li>the owner replaced the link, or the source moved to another reviewed revision, while the call was
 *     out - nothing from this answer is written (T26);</li>
 * <li>the source revision the text was written under is superseded or disabled - the existing read gate
 *     withholds it (T7, T8).</li>
 * </ul>
 */
@Service
public class KtoEngTextRefresh {

    public static final String SOURCE_CODE = KtoEngRecord.SOURCE_CODE;
    static final String ENDPOINT_KEY = "ENG_SERVICE_2_DETAIL_COMMON_2";

    public enum Outcome {
        UPDATED, WITHDRAWN_GONE, WITHDRAWN_RULE, KEPT_OTHER_SOURCE_TEXT, DISCARDED_LINK_CHANGED,
        DISCARDED_REVISION_CHANGED
    }

    private final EngTextStore store;
    private final SourceRegistryQuery registry;
    private final SourceRegistryStore registryStore;
    private final CollectorRunRecorder collector;
    private final KtoEngDetailFetcher fetcher;
    private final KtoEngDetailResponseValidator validator;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Autowired
    public KtoEngTextRefresh(EngTextStore store, SourceRegistryQuery registry, SourceRegistryStore registryStore,
            CollectorRunRecorder collector, KtoEngDetailFetcher fetcher, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.validator = new KtoEngDetailResponseValidator();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public List<EngTextStore.Link> links() {
        return store.links();
    }

    public Outcome refresh(EngTextStore.Link link) {
        Objects.requireNonNull(link, "link");
        Instant startedAt = clock.instant();
        SourceRegistration source = requireHealthySource(startedAt);
        try {
            fetcher.requireConfigured();
        } catch (KtoGatewayException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_INTERNAL_FAILURE, failure.getClass());
        }
        UUID runId = collector.start(SOURCE_CODE, IngestAudit.TriggerType.MANUAL, source.providerSchemaVersion(),
                startedAt);
        SourceQuotaStore.Reservation reservation;
        try {
            reservation = collector.reserve(runId, SOURCE_CODE, ENDPOINT_KEY, "kto-eng-detail-" + UUID.randomUUID(),
                    fetcher.releaseVersion());
        } catch (QuotaExhaustedException failure) {
            finishWithoutCall(runId, "QUOTA_EXHAUSTED");
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_QUOTA_EXHAUSTED);
        } catch (RuntimeException failure) {
            try {
                finishWithoutCall(runId, "RESERVATION_FAILED");
            } catch (RuntimeException ignored) {
                // The stable code below is all a caller may see.
            }
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
        }

        long transportStarted = System.nanoTime();
        ProviderResponse response;
        try {
            response = fetcher.fetch(link.request()).join();
        } catch (RuntimeException failure) {
            throw recordTransportFailure(runId, reservation, failure, elapsedMillis(transportStarted));
        }
        int duration = elapsedMillis(transportStarted);
        Result result = validator.validate(response.body(), link.request());
        if (result instanceof Rejected) {
            try {
                transactions.executeWithoutResult(status -> collector.finalizeSingleCall(runId,
                        reservation.ingestLogId(), response.status(), duration, result.responseCount(), null,
                        result.verdict(), clock.instant()));
            } catch (RuntimeException failure) {
                throw new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
            }
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_RESPONSE_REJECTED);
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                boolean accepted = collector.finalizeSingleCall(runId, reservation.ingestLogId(), response.status(),
                        duration, result.responseCount(), payloadHash(result), result.verdict(), clock.instant());
                if (!accepted) {
                    throw new IllegalStateException("an accepted English answer was not recorded as accepted");
                }
                return apply(link, result, source.currentRevision(), startedAt);
            }));
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> collector.failSingleCall(runId,
                        reservation.ingestLogId(), IngestAudit.CallOutcome.IO_ERROR, response.status(), duration,
                        "PERSISTENCE_FAILED", clock.instant()));
            } catch (RuntimeException ignored) {
                // The stable code below is all a caller may see.
            }
            throw new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
        }
    }

    private Outcome apply(EngTextStore.Link fetched, Result result, long fetchedUnderRevision, Instant observedAt) {
        Optional<EngTextStore.Link> current = store.lockLink(fetched.placeId());
        if (current.isEmpty() || !sameDecision(current.get(), fetched)) {
            // The import already removed the text of the record it replaced (BA-086-T23).
            return Outcome.DISCARDED_LINK_CHANGED;
        }
        long revision = registry.find(SOURCE_CODE).map(SourceRegistration::currentRevision).orElse(-1L);
        if (revision != fetchedUnderRevision) {
            // Pinning this answer to the new revision would claim it was collected under a contract it
            // was not; the next refresh collects under the new one. Text already written under the old
            // revision is withheld by the read gate (BA-086-T7).
            return Outcome.DISCARDED_REVISION_CHANGED;
        }
        Optional<EngTextStore.PlaceSide> place = store.lockActivePlace(fetched.placeId());
        if (result instanceof Found found && place.isPresent()
                && EngLinkRule.holds(found.record(), place.get().facts())) {
            return store.writeText(fetched.placeId(), found.record(), revision, observedAt, clock.instant())
                    ? Outcome.UPDATED : Outcome.KEPT_OTHER_SOURCE_TEXT;
        }
        store.removeText(fetched.placeId());
        return result instanceof Gone ? Outcome.WITHDRAWN_GONE : Outcome.WITHDRAWN_RULE;
    }

    private static boolean sameDecision(EngTextStore.Link current, EngTextStore.Link fetched) {
        return current.externalId().equals(fetched.externalId())
                && current.contentTypeId().equals(fetched.contentTypeId())
                && current.reviewedAt().equals(fetched.reviewedAt());
    }

    private static String payloadHash(Result result) {
        return result instanceof Found found ? found.record().payloadHash() : null;
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

    private void finishWithoutCall(UUID runId, String errorCode) {
        transactions.executeWithoutResult(status -> collector.failRun(runId, errorCode, clock.instant()));
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
            transactions.executeWithoutResult(transaction -> collector.failSingleCall(runId,
                    reservation.ingestLogId(), outcome, status, duration, errorCode, clock.instant()));
        } catch (RuntimeException ignored) {
            return new KtoGatewayException(KtoGatewayException.Code.KTO_PERSISTENCE_FAILED);
        }
        return new KtoGatewayException(KtoGatewayException.Code.KTO_TRANSPORT_FAILED);
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
