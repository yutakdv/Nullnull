package io.nullnull.operations.application;

import io.nullnull.operations.domain.JobLease;

/**
 * A write was refused because the lease it named is no longer the current one: the job was re-taken
 * after the lease expired, the attempt moved on, or the row is no longer RUNNING.
 *
 * <p>This is the exception that makes "only the current lease may commit"
 * (docs/engineering/TEST_STRATEGY.md REC-JOB-01) observable. It is raised whenever a conditional
 * update matches zero rows, so a stale worker never mistakes "nothing happened" for success. Thrown
 * from inside {@link JobContext#transactional} it also rolls back the handler's domain write together
 * with the job row it belongs to.
 *
 * <p>No public HTTP contract: background work has no caller to answer. The worker logs it and lets
 * the job continue under whoever holds it now.
 */
public class StaleLeaseException extends RuntimeException {

    private final JobLease lease;

    public StaleLeaseException(JobLease lease, String operation) {
        // Job id and type only: no payload, deduplication key or owner reaches this message.
        super("job lease is no longer held, " + operation + " rejected: type=" + lease.type()
                + " jobId=" + lease.jobId() + " attempt=" + lease.attempt());
        this.lease = lease;
    }

    public JobLease lease() {
        return lease;
    }
}
