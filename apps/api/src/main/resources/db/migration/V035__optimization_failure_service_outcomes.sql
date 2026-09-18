-- BA-051 / #261: a run whose job is dead-lettered can say why it ended.
--
-- Until now a run had no code for it, and the CHECK below requires one for every FAILED run
-- ((status = 'FAILED') = (failure_code IS NOT NULL), V024). So when the optimize-item job ran out of
-- attempts, or ended on an answer outside the recommendation contract, the job became FAILED and the
-- run stayed RUNNING - measured three seconds after the job ended (#252). Every existing code was
-- wrong for it: each describes the trip or its evidence, and DATA_INSUFFICIENT in particular would
-- tell the user "not enough information yet" about a service that did not answer (invariant 6).
--
--   RECOMMENDATION_UNAVAILABLE  the service did not answer through every attempt (retryable)
--   INTERNAL_ERROR              an answer outside the contract, a handler failure, or a lease that
--                               ran out with no attempt left (not retryable)
--
-- V024 wrote the vocabulary as a CHECK and V031 replaced it once already; applied migrations cannot
-- be edited, so it is replaced again rather than amended in place.

ALTER TABLE optimization_runs DROP CONSTRAINT optimization_runs_failure_code_check;

ALTER TABLE optimization_runs ADD CONSTRAINT optimization_runs_failure_code_check CHECK
    (failure_code IS NULL OR failure_code IN
        ('TRIP_CHANGED', 'DATA_CHANGED', 'LOCK_CONFLICT', 'ROUTE_UNAVAILABLE', 'NO_IMPROVEMENT',
         'DATA_INSUFFICIENT', 'RECOMMENDATION_UNAVAILABLE', 'INTERNAL_ERROR'));

-- APPLY_FAILED stays absent, as in V024 and V031: it names an APPLY that could not be written, so a
-- run that never attempted one cannot report it.
