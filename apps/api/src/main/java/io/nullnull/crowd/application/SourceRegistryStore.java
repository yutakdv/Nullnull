package io.nullnull.crowd.application;

import io.nullnull.crowd.domain.SourceRegistration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SourceRegistryStore {

    List<SourceRegistration> findAll();

    Optional<SourceRegistration> findByCode(String code);

    SourceCondition conditionAt(String code, Instant at);

    /**
     * Records a reviewed release for the source's latest run, if that run is quarantined.
     *
     * <p>Returns the run it released, or empty when the latest run is not quarantined - which is the
     * operator's signal that there was nothing to release, not a silent success. The refusal itself is
     * never rewritten: the release is a {@code RESOLVED} incident beside it, and a reader can still
     * see that the source was shut and why.
     *
     * <p>Only an operator command calls this. Nothing on a request path releases a quarantine, and
     * nothing releases one automatically - a source shut by provider drift stays shut until a person
     * has looked at it.
     */
    Optional<ReleasedRun> releaseLatestQuarantine(String code, String incidentCode, Instant reviewedAt);

    record SourceCondition(boolean incidentActive, boolean latestRunQuarantined) {
    }

    record ReleasedRun(java.util.UUID runId, Instant startedAt) {
    }
}
