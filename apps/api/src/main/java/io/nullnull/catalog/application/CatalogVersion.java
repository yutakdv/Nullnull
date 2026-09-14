package io.nullnull.catalog.application;

import io.nullnull.crowd.application.SourceRegistryQuery;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Which version of the catalog a set of hydrated facts came from (A-034).
 *
 * <p>One value for two callers that were each missing one: the optimizer's run fingerprint needs to
 * change when the catalog behind a preview changes, and a related-places request needs to say which
 * taxonomy its categories belong to. Those were filed as two gaps and are one concept - "the catalog
 *판본 these facts were hydrated from" - so they get one producer rather than two strings that can
 * disagree.
 *
 * <p>The value is the catalog source's registry revision, which already exists and already moves for
 * the right reason: a revision is bumped when the provider contract or approval changes, which is
 * exactly when facts hydrated from it stop being comparable to facts hydrated before.
 *
 * <p>The source code is part of the value, not implied by it. A bare {@code 4} cannot say what it is
 * the fourth revision OF, and the day a second catalog source exists that ambiguity would be silent.
 *
 * <p><strong>A revision bump invalidates every stored preview.</strong> Runs pin this string in their
 * fingerprint, so raising the revision makes existing READY previews compare unequal and refuse to
 * apply. That is the intended behaviour: a preview computed against a catalog we no longer publish
 * is one nobody should be able to accept. Anyone tempted to loosen the comparison should change this
 * decision instead.
 */
@Service
public class CatalogVersion {

    /** The catalog's own source. V008 registers it; V007 seeds the registry row. */
    public static final String SOURCE_CODE = "KTO_KOR_SERVICE_2";

    private final SourceRegistryQuery registry;

    public CatalogVersion(SourceRegistryQuery registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * @throws IllegalStateException when the catalog source is not registered. Not a degraded mode:
     *     every fact this version would describe was hydrated through that source, so if it is
     *     missing the facts have no provenance and nothing downstream should pretend otherwise.
     */
    public String current() {
        long revision = registry.find(SOURCE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "the catalog source " + SOURCE_CODE + " is not registered"))
                .currentRevision();
        return SOURCE_CODE + ":" + revision;
    }
}
