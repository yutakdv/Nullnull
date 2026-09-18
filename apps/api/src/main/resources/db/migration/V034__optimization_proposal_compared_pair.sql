-- getOptimization (#16 R3): the forecast pair a proposal compared, stored with the proposal.
--
-- V029 kept no snapshot ids, and a reader had to find the pair again in the run's frozen sets by the
-- two days the change names - which needs the trip's timezone to turn a day back into the instant a
-- point was filed under. A trip's timezone can be edited after the run (the local dates are kept), and
-- then the pair cannot be found and a run the contract promises stays readable answers 500. The ids
-- are what apps/ai returned in ItemProposalOut and ProposalRevalidator already required to equal the
-- candidate this API hydrated, so storing them records the comparison rather than re-deriving it.
--
-- Nullable because rows written before this migration have no pair to record; the application refuses
-- to read such a row rather than guessing one. No production data existed when this was written.
--
-- Deliberately NOT foreign keys to crowd_snapshots, like optimization_changes.trip_item_id (V029). A
-- foreign key would make the points of a frozen set undeletable, and BA-052-T5 constructs exactly that
-- deletion - the state a retention sweep would create - to prove APPLY refuses a preview whose evidence
-- is gone. The cost is on the read side: once a point a proposal names is deleted, that run's
-- getOptimization cannot show its evidence and answers 500. Nothing deletes points today; a retention
-- sweep has to account for proposals that name them.
ALTER TABLE optimization_proposals
    ADD COLUMN before_snapshot_id uuid,
    ADD COLUMN after_snapshot_id uuid;

ALTER TABLE optimization_proposals ADD CONSTRAINT optimization_proposals_compared_pair_check CHECK (
    (before_snapshot_id IS NULL) = (after_snapshot_id IS NULL)
);
