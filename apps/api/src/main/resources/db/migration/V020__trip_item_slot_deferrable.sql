-- BA-040: reorderTripItems moves several items in one transaction, and the obvious moves pass
-- through a state the old constraint refused.
--
-- Swapping two items on a day is the smallest case: whichever UPDATE runs first puts two rows on the
-- same (trip_id, trip_date, position) until the second one runs. V014's constraint is checked per
-- statement, so that intermediate state is rejected and a swap is impossible however the statements
-- are ordered. The rule itself is right - ERD §11 wants one item per slot, and two items claiming a
-- position is an order the UI cannot render - but it has to be judged on the transaction's RESULT,
-- not on each row as it moves.
--
-- DEFERRABLE INITIALLY IMMEDIATE keeps the existing behaviour everywhere else: every writer that does
-- not ask for deferral still fails on its own statement, at the same point it did before. Only a
-- transaction that issues SET CONSTRAINTS trip_items_slot_unique DEFERRED postpones the check to
-- COMMIT, which is exactly the reorder. An INITIALLY DEFERRED default would have been the quiet
-- version of this change: every existing insert would start reporting its conflict at commit instead,
-- far from the statement that caused it.
--
-- A UNIQUE constraint cannot be altered in place - ALTER CONSTRAINT only accepts foreign keys - so it
-- is dropped and recreated. The rebuild reindexes the table, and no data can be lost by it: the new
-- constraint covers the same columns, so any row set that was legal before is still legal.
ALTER TABLE trip_items DROP CONSTRAINT trip_items_slot_unique;
ALTER TABLE trip_items ADD CONSTRAINT trip_items_slot_unique
    UNIQUE (trip_id, trip_date, position) DEFERRABLE INITIALLY IMMEDIATE;
