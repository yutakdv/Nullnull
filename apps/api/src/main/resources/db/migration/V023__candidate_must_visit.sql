-- FR-TRC-04: the traveller can say a saved place has to be in the trip, on the screen where they
-- save it. Until now there was nowhere to put that, so the answer was collected and dropped (#185).
--
-- It is an INTENTION, not a lock, and the distinction is the whole design. Locks live on items -
-- trip_constraints.trip_item_id is NOT NULL - and a candidate has no date for one to pin. So this
-- column does not make a candidate lockable and nothing in the lock machinery reads it: invariant 7
-- is about items, and a flag pretending to be a lock would put the two in the same sentence.
--
-- What it does is survive until scheduling. When the candidate becomes an item, the item gets a
-- MUST_VISIT constraint in the same transaction (invariant 5), and the intention becomes a lock at
-- the moment there is something to lock.
--
-- MUST_VISIT is the only one of the four this can become, and that is not a simplification: V014's
-- trip_constraints typed check requires DATE, TIME and RESERVATION to carry a date or a clock time,
-- and MUST_VISIT to carry neither. A candidate has neither. The shapes line up exactly.
--
-- DEFAULT false rather than nullable: a candidate saved before this column existed was saved without
-- the question being asked, and "the traveller did not ask for this" is false, not unknown.
ALTER TABLE trip_candidates ADD COLUMN must_visit boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN trip_candidates.must_visit IS
    'FR-TRC-04: the traveller asked for this place to be in the trip. An intention while the row is '
    'a candidate; becomes a MUST_VISIT trip_constraint on the item it is scheduled onto.';
