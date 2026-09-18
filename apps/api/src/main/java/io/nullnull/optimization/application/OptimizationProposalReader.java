package io.nullnull.optimization.application;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.optimization.domain.OptimizationChange;
import io.nullnull.optimization.domain.OptimizationProposal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Stored proposals, read back as a traveller is shown them (getOptimization).
 *
 * <p>The changes and the validation summary are stored as JSON, and {@link ItemProposalMapper} - which
 * wrote them - reads them back. The evidence is the forecast pair the proposal compared, which V034
 * stores with the proposal: the ids apps/ai answered with and ProposalRevalidator required to be the
 * pair of the candidate this API hydrated. It is read by id, not found again by day, so no timezone is
 * involved - a trip whose zone was edited after the run still shows what the run compared.
 */
@Component
public class OptimizationProposalReader {

    private final ItemProposalMapper mapper;
    private final CrowdForecastQuery forecasts;
    private final CrowdProvenanceProjection provenance;

    public OptimizationProposalReader(ItemProposalMapper mapper, CrowdForecastQuery forecasts,
            CrowdProvenanceProjection provenance) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.forecasts = Objects.requireNonNull(forecasts, "forecasts");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    /**
     * @param now the instant the whole response is read at: a stored point's freshness is reported as
     *     of now, the way the crowd endpoints report it.
     */
    public List<OptimizationProposalView> read(List<OptimizationProposal> stored, Instant now) {
        if (stored.isEmpty()) {
            return List.of();
        }
        List<UUID> named = new ArrayList<>();
        for (OptimizationProposal proposal : stored) {
            if (proposal.beforeSnapshotId() == null) {
                // A row written before V034 recorded no pair. Finding one again by day is exactly what
                // V034 exists to stop, so the row is refused rather than shown with a guess.
                throw new IllegalStateException("proposal " + proposal.id()
                        + " was stored without the pair it compared (written before V034)");
            }
            named.add(proposal.beforeSnapshotId());
            named.add(proposal.afterSnapshotId());
        }
        Map<UUID, CrowdForecastQuery.Snapshot> points = forecasts.points(named.stream().distinct().toList())
                .stream()
                .collect(Collectors.toMap(CrowdForecastQuery.Snapshot::id, Function.identity()));
        List<OptimizationProposalView> views = new ArrayList<>(stored.size());
        for (OptimizationProposal proposal : stored) {
            views.add(new OptimizationProposalView(proposal,
                    proposal.changes().stream().map(this::changeView).toList(),
                    mapper.readValidation(proposal.validationSummary()),
                    List.of(project(proposal, points, proposal.beforeSnapshotId(), now),
                            project(proposal, points, proposal.afterSnapshotId(), now))));
        }
        return List.copyOf(views);
    }

    private OptimizationProposalView.ChangeView changeView(OptimizationChange change) {
        return new OptimizationProposalView.ChangeView(change.tripItemId(), change.operation(),
                mapper.readState(change.beforeValue()), mapper.readState(change.afterValue()));
    }

    /**
     * One side of the pair as the contract's DataProvenance, or a refusal if the point is gone.
     *
     * <p>Nothing deletes a crowd point today. When something does (a retention sweep), a proposal that
     * names the point can no longer show what it compared, and serving it with one side missing would
     * present the comparison as resting on evidence the response does not carry (V034's comment).
     */
    private CrowdProvenanceProjection.DataProvenance project(OptimizationProposal proposal,
            Map<UUID, CrowdForecastQuery.Snapshot> points, UUID pointId, Instant now) {
        CrowdForecastQuery.Snapshot point = points.get(pointId);
        if (point == null) {
            throw new IllegalStateException("proposal " + proposal.id() + " compared point " + pointId
                    + ", which is no longer stored");
        }
        return provenance.project(point, now, false).provenance();
    }
}
