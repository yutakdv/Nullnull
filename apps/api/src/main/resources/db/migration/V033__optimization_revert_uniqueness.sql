-- BA-053: an applied decision can be undone once, and the database is what says so.
--
-- ERD section 9 already required this - "revert decision은 APPLY 하나만 참조하고 원 decision당
-- 최대 하나다: unique (reverted_decision_id) WHERE reverted_decision_id IS NOT NULL" - and V030
-- created the column without the index. So this is not a rule invented here; it is one the canonical
-- document has been carrying while nothing enforced it.
--
-- Why it has to be the database: two callers racing to revert the same decision both read a state
-- that permits it, and an application check decides only which transaction read first. BA-052-T1
-- measured that shape - with the application guard disabled the partial unique index was what kept
-- one winner, and with the index unreachable the guard was. Here there is no second line yet, so
-- until this index exists "undone once" is a sentence with nothing under it.

CREATE UNIQUE INDEX optimization_decisions_one_revert_per_decision
    ON optimization_decisions (reverted_decision_id) WHERE reverted_decision_id IS NOT NULL;

-- What this does NOT close, said here because the next reader will otherwise assume it does.
--
-- V030's own comment names a different gap: "What this cannot check is that reverted_decision_id
-- names an APPLY rather than a KEEP or another REVERT: that is a different row, and a CHECK sees
-- only its own. BA-053 owns that assertion." That is still true after this index. A unique index
-- constrains how MANY rows may point at one decision; it says nothing about WHAT the decision they
-- point at is. The kind check lives in the application, and BA-053 owes it there.
--
-- The fixture this meets is outside the predicate: FlywayMigrationIT's populateEveryTable inserts a
-- single KEEP whose reverted_decision_id is NULL, so a partial index on NOT NULL never judges it.
-- Checked before writing this rather than after - a migration cannot be corrected once applied.
