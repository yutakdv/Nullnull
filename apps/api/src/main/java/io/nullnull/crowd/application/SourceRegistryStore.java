package io.nullnull.crowd.application;

import io.nullnull.crowd.domain.SourceRegistration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SourceRegistryStore {

    List<SourceRegistration> findAll();

    Optional<SourceRegistration> findByCode(String code);

    SourceCondition conditionAt(String code, Instant at);

    record SourceCondition(boolean incidentActive, boolean latestRunQuarantined) {
    }
}
