package io.nullnull.live.application;

import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.QuotaExhaustedException;
import io.nullnull.crowd.application.SeoulCityDataFetcher;
import io.nullnull.crowd.application.SeoulCityDataValidator;
import io.nullnull.crowd.application.SeoulGatewayException;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One trip from the registry to a normalized Seoul observation, with the run recorded either way.
 *
 * <p><strong>A refused response is a recorded run, not a silent nothing.</strong> The validator's
 * verdict goes to {@link CollectorRunRecorder#finalizeSingleCall}, which maps it onto the matching
 * {@code IngestAudit.ValidationResult} and finishes the run QUARANTINED - so a provider that starts
 * sending a shape we do not accept leaves a trail with the reason on it. Returning empty and moving
 * on would make "the city is quiet" and "we stopped understanding the provider" the same answer.
 *
 * <p><strong>This is not where a reviewed incident goes.</strong> {@code source_quality_incidents}
 * needs a {@code reviewed_at} and is unique per (source, incident code): it holds the curated
 * quarantine decisions an operator makes about a published provider notice, one row per incident.
 * Writing a rejection there would invent a review nobody did and collapse repeated rejections into
 * one row. That table is read at projection time; this one is written at collection time.
 */
@Component
public class SeoulLiveAreaGateway {

    private static final String SOURCE_CODE = SeoulLiveAreaObservation.SOURCE_CODE;
    /** The one operation this adapter may call; the proxy accepts no other path. */
    private static final String ENDPOINT_KEY = "CITYDATA";

    private final SourceRegistryQuery registry;
    private final SourceRegistryStore registryStore;
    private final CollectorRunRecorder collector;
    private final SeoulCityDataFetcher fetcher;
    private final SeoulCityDataValidator validator;
    private final Clock clock;

    // Two constructors, so Spring cannot pick one implicitly: the package-private one exists to
    // hand a test its own validator. The public one is the bean.
    @Autowired
    public SeoulLiveAreaGateway(SourceRegistryQuery registry, SourceRegistryStore registryStore,
            CollectorRunRecorder collector, SeoulCityDataFetcher fetcher, Clock clock) {
        this(registry, registryStore, collector, fetcher, new SeoulCityDataValidator(), clock);
    }

    SeoulLiveAreaGateway(SourceRegistryQuery registry, SourceRegistryStore registryStore,
            CollectorRunRecorder collector, SeoulCityDataFetcher fetcher, SeoulCityDataValidator validator,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.registryStore = Objects.requireNonNull(registryStore, "registryStore");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What one collection attempt produced: an observation, or nothing with the run already closed. */
    public record Collection(UUID runId, Optional<SeoulLiveAreaObservation> observation) {
        public boolean accepted() {
            return observation.isPresent();
        }
    }

    public CompletableFuture<Collection> collect(String areaName) {
        Objects.requireNonNull(areaName, "areaName");
        Instant startedAt = clock.instant();
        SourceRegistration source = requireHealthySource(startedAt);
        fetcher.requireConfigured();

        UUID runId = collector.start(SOURCE_CODE, IngestAudit.TriggerType.SCHEDULED,
                source.providerSchemaVersion(), startedAt);
        SourceQuotaStore.Reservation reservation;
        try {
            reservation = collector.reserve(runId, SOURCE_CODE, ENDPOINT_KEY,
                    "seoul-citydata-" + UUID.randomUUID(), source.providerSchemaVersion());
        } catch (QuotaExhaustedException failure) {
            // The quota is the provider's, and Seoul's multi-destination allowance is the lowest one
            // we use. A refused reservation closes the run without a call rather than borrowing.
            collector.failRun(runId, "QUOTA_EXHAUSTED", clock.instant());
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_QUOTA_EXHAUSTED);
        }

        long startedNanos = System.nanoTime();
        return fetcher.fetch(areaName).handle((response, failure) -> {
            int duration = elapsedMillis(startedNanos);
            if (failure != null) {
                // No body, no URI, no token: the run records that the call failed and nothing about
                // what was sent. A 429 lands here, which is why it produces no observation.
                // The outcome names the shape of the failure, mapped from the transport's own
                // category so a 429 is HTTP_ERROR rather than a generic one. Nothing else about the
                // request is recorded.
                collector.failSingleCall(runId, reservation.ingestLogId(), outcomeOf(failure), null,
                        duration, "PROVIDER_FAILED", clock.instant());
                throw unwrap(failure);
            }
            return acceptOrQuarantine(runId, reservation, response, areaName, duration);
        });
    }

    private Collection acceptOrQuarantine(UUID runId, SourceQuotaStore.Reservation reservation,
            ProviderResponse response, String areaName, int duration) {
        SeoulCityDataValidator.Validation validation = validator.validate(response.body(), areaName);
        // One call, one observation, so the count is 1 whether it was kept or refused - "how many the
        // provider sent" is not "how many we accepted", and finalizeSingleCall splits those itself.
        boolean accepted = collector.finalizeSingleCall(runId, reservation.ingestLogId(), response.status(),
                duration, 1, null, validation.verdict(), clock.instant());
        return new Collection(runId,
                accepted ? Optional.of(validation.observation()) : Optional.empty());
    }

    private SourceRegistration requireHealthySource(Instant at) {
        SourceRegistration source = registry.find(SOURCE_CODE).filter(SourceRegistration::collectionEnabled)
                .orElseThrow(() -> new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_SOURCE_DISABLED));
        SourceRegistryStore.SourceCondition condition = registryStore.conditionAt(SOURCE_CODE, at);
        // A reviewed incident window, or a previous run already quarantined, stops the next call
        // before it happens: a source under review does not get to add readings while it is there.
        if (condition.incidentActive() || condition.latestRunQuarantined()) {
            throw new SeoulGatewayException(SeoulGatewayException.Code.SEOUL_SOURCE_QUARANTINED);
        }
        return source;
    }

    private static IngestAudit.CallOutcome outcomeOf(Throwable failure) {
        Throwable current = unwrap(failure);
        if (current instanceof io.nullnull.shared.provider.ProviderException provider) {
            return switch (provider.category()) {
                case HTTP_STATUS -> IngestAudit.CallOutcome.HTTP_ERROR;
                case TIMEOUT -> IngestAudit.CallOutcome.TIMEOUT;
                case CIRCUIT_OPEN -> IngestAudit.CallOutcome.CIRCUIT_OPEN;
                default -> IngestAudit.CallOutcome.IO_ERROR;
            };
        }
        return IngestAudit.CallOutcome.IO_ERROR;
    }

    private static int elapsedMillis(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static RuntimeException unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof RuntimeException runtime ? runtime : new CompletionException(current);
    }
}
