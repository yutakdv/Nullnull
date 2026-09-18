package io.nullnull.optimization.application;

import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationProposal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored proposal as a reader sees it: the row, its stored JSON read back, and the evidence it
 * compared.
 *
 * <p>{@code provenance} is the pair the proposal compared (V034), before then after, projected as of
 * the read.
 */
public record OptimizationProposalView(OptimizationProposal proposal, List<ChangeView> changes,
        ItemProposalMapper.Validation validation,
        List<CrowdProvenanceProjection.DataProvenance> provenance) {

    public OptimizationProposalView {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(validation, "validation");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        provenance = List.copyOf(Objects.requireNonNull(provenance, "provenance"));
    }

    /** A stored change with its before and after read back; an ADD has no before, a REMOVE no after. */
    public record ChangeView(UUID itemId, OptimizationChangeOperation operation,
            ItemProposalMapper.ItemState before, ItemProposalMapper.ItemState after) {

        public ChangeView {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
