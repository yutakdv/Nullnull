-- BA-051: what an optimization run proposes, and the changes each proposal is made of.
--
-- V024 created the runs and deliberately left these two to the slice that produces them. A run can
-- still reach READY with nothing stored here, which stays true: these tables say what a proposal is,
-- not that every run has one.
--
-- optimization_decisions is still absent. It belongs to BA-052, which is what writes a decision.

CREATE TABLE optimization_proposals (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES optimization_runs(id) ON DELETE CASCADE,
    -- Rank within the run, 1 upwards, matching the contract's minimum of 1.
    --
    -- There is no upper bound here even though the card selects at most three. That number is a
    -- draft of a policy - the card says so itself - and a migration is the one file that cannot be
    -- corrected once applied. The cap lives where the policy lives; this column only says the ranks
    -- of one run are distinct and start at 1.
    rank int NOT NULL,
    summary text NOT NULL,
    -- Invariant 8 in the schema: a number comparing this proposal's crowd against the baseline may
    -- exist only when the pair was eligible to be compared, and when it was not, the reason must be
    -- named. Written as one CHECK over the three columns rather than three NOT NULLs, because the
    -- failure that matters is the combination: a delta with no eligibility is a comparison we were
    -- not entitled to make, and an ineligible proposal with no reason is one nobody can explain.
    comparison_eligible boolean NOT NULL,
    -- 100 is the contract's own maximum for a comparison reason code (CrowdComparison.reasonCode);
    -- it is not a width picked here.
    comparison_reason_code varchar(100),
    -- numeric(14, 4) because that is what a crowd value is (V011 crowd_snapshots.value). A delta
    -- between two of those needs the same magnitude, and the narrower numeric(5, 4) written here
    -- first would have refused any pair further apart than 9.9999 - a column that rejects real
    -- measurements to honour a number nobody measured.
    crowd_delta numeric(14, 4),
    -- Route evidence, not crowd evidence, so it is not part of the comparison CHECK. P0 confirms no
    -- route provider, so this is null on every row the current system can write - which is a fact
    -- about our data rather than a claim that the itinerary does not move.
    travel_minutes_delta int,
    validation_summary jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT optimization_proposals_rank_check CHECK (rank >= 1),
    -- The contract's own bounds for summary (minLength 1, maxLength 500). Kept here so a proposal
    -- that could not be serialised is refused at the write rather than at the response.
    CONSTRAINT optimization_proposals_summary_check CHECK
        (length(btrim(summary)) BETWEEN 1 AND 500),
    CONSTRAINT optimization_proposals_comparison_check CHECK (
        (comparison_eligible AND comparison_reason_code IS NULL)
        OR (NOT comparison_eligible AND comparison_reason_code IS NOT NULL AND crowd_delta IS NULL)
    ),
    CONSTRAINT optimization_proposals_rank_unique UNIQUE (run_id, rank)
);

CREATE TABLE optimization_changes (
    id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL REFERENCES optimization_proposals(id) ON DELETE CASCADE,
    -- Deliberately NOT a foreign key to trip_items, which is where ERD section 2 and the contract
    -- disagree and the contract is right: an ADD carries "a stable prospective item ID reserved by
    -- the preview", an id for an item that does not exist and will not exist unless the traveller
    -- applies the proposal. A foreign key would reject exactly the operation that invents the id.
    -- The other four operations do name a live item, and that they do is the revalidator's
    -- post-condition rather than something this column can hold for them.
    trip_item_id uuid NOT NULL,
    operation varchar(10) NOT NULL,
    before_value jsonb,
    after_value jsonb,
    sequence int NOT NULL,
    CONSTRAINT optimization_changes_operation_check CHECK
        (operation IN ('MOVE', 'REORDER', 'REPLACE', 'ADD', 'REMOVE')),
    -- ERD section 2 annotates these two as "null only for ADD" and "null only for REMOVE". Written
    -- as biconditionals so both directions hold: an ADD must have no before, and anything that is
    -- not an ADD must have one. A one-way NOT NULL would let a MOVE arrive with half its state and
    -- still be stored, and the diff a traveller is asked to approve would be missing its left side.
    CONSTRAINT optimization_changes_before_check CHECK
        ((before_value IS NULL) = (operation = 'ADD')),
    CONSTRAINT optimization_changes_after_check CHECK
        ((after_value IS NULL) = (operation = 'REMOVE')),
    CONSTRAINT optimization_changes_sequence_check CHECK (sequence >= 0),
    CONSTRAINT optimization_changes_sequence_unique UNIQUE (proposal_id, sequence)
);

-- Immutable, the way crowd_snapshots is (V011). A proposal is the thing a traveller was shown and
-- said yes or no to; editing one after the fact would rewrite what they agreed to. Anything that
-- needs to change is a new run.
CREATE FUNCTION optimization_prevent_proposal_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'optimization proposals and their changes are immutable';
END;
$$;

CREATE TRIGGER optimization_proposals_immutable_guard
BEFORE UPDATE ON optimization_proposals
FOR EACH ROW EXECUTE FUNCTION optimization_prevent_proposal_mutation();

CREATE TRIGGER optimization_changes_immutable_guard
BEFORE UPDATE ON optimization_changes
FOR EACH ROW EXECUTE FUNCTION optimization_prevent_proposal_mutation();
