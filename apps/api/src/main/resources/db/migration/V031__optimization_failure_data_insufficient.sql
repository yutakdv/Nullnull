-- BA-051 / #225: the run failure plane gains the answer apps/ai was already giving.
--
-- items/propose can end in DATA_INSUFFICIENT - evaluator.py returns it from four places - and until
-- now a run had no way to record that. Every existing code was wrong for it: NO_IMPROVEMENT says
-- the alternatives were judged and lost, which the card forbids using as a cover ("NO_IMPROVEMENT로
-- 숨기지 않는다"), and DATA_CHANGED says evidence was withdrawn mid-run rather than never having
-- been enough. Invariant 6 asks for exactly this distinction: 데이터 부재 is its own state.
--
-- V024 wrote the vocabulary as a CHECK and cannot be edited now that it has been applied, so the
-- constraint is replaced rather than amended in place.

ALTER TABLE optimization_runs DROP CONSTRAINT optimization_runs_failure_code_check;

ALTER TABLE optimization_runs ADD CONSTRAINT optimization_runs_failure_code_check CHECK
    (failure_code IS NULL OR failure_code IN
        ('TRIP_CHANGED', 'DATA_CHANGED', 'LOCK_CONFLICT', 'ROUTE_UNAVAILABLE', 'NO_IMPROVEMENT',
         'DATA_INSUFFICIENT'));

-- APPLY_FAILED is still absent, and deliberately: it names an APPLY that could not be written, so a
-- run that never attempted one cannot report it. That split is the one V024 made and this keeps.
