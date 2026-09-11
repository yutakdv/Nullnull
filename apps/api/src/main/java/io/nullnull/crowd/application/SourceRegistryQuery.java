package io.nullnull.crowd.application;

import io.nullnull.crowd.domain.SourceRegistration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SourceRegistryQuery {

    private static final Set<String> ENVIRONMENTS = Set.of("local", "test", "staging", "production");

    private final SourceRegistryStore store;

    public SourceRegistryQuery(SourceRegistryStore store) {
        this.store = store;
    }

    public List<SourceRegistration> enabledFor(String environment) {
        String normalized = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        if (!ENVIRONMENTS.contains(normalized)) {
            throw new IllegalArgumentException("environment must be one of " + ENVIRONMENTS);
        }
        // The 2026-09-07 team decision permits DEV_APPROVED in the contest production build.
        return store.findAll().stream().filter(SourceRegistration::collectionEnabled).toList();
    }

    public Optional<SourceRegistration> find(String code) {
        return store.findByCode(code);
    }
}
