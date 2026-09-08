-- BA-005 correction: the deduplication key names at most one OUTSTANDING piece of work
-- (docs/architecture/ERD.md §4 "Deletion/background jobs"), not one row that ever existed.
--
-- V001 made the key unique over the whole table, terminal rows included, and `enqueue` returns the row
-- it collides with. Measured: enqueue -> claim -> complete -> enqueue the same key returned the old
-- COMPLETED job with created=false. A scheduled collector with a natural key (`collector:kto:area-1`)
-- would therefore run once and then silently never run again for the whole
-- NULLNULL_JOB_FINISHED_RETENTION window (P7D by default) - nothing thrown, nothing logged.
--
-- Rollback compatibility (§7.7). Dropping the constraint removes a restriction, so every read and every
-- ordinary INSERT/UPDATE an older binary makes keeps working, and rows written by either binary stay
-- valid under the new index. The one statement that does not survive is the previous binary's
-- `ON CONFLICT (deduplication_key) DO NOTHING`: PostgreSQL infers that target from a unique index over
-- the whole column, which no longer exists, so an old task's enqueue raises an error instead of
-- silently taking the wrong branch. That is reachable only through JobQueue.enqueue, which refuses any
-- type without a registered handler - and no handler exists in this repository yet, so no caller can
-- reach it in a deployed environment today. The first slice that adds a handler must therefore ship
-- after this migration, not across it.
--
-- Not CONCURRENTLY, deliberately: the drop and the create belong in one transaction, because a window
-- with neither would accept a second outstanding row for a key. The table is empty in every environment
-- this migration reaches, so the brief ACCESS EXCLUSIVE lock costs nothing; a future table with real
-- volume needs the concurrent form and its own two-step migration.
ALTER TABLE background_jobs DROP CONSTRAINT background_jobs_deduplication_key_key;

CREATE UNIQUE INDEX background_jobs_outstanding_key_idx
    ON background_jobs (deduplication_key)
    WHERE status IN ('READY', 'RETRY', 'RUNNING');
