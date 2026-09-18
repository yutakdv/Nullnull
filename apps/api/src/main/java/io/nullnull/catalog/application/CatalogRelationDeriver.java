package io.nullnull.catalog.application;

import io.nullnull.catalog.application.CatalogRelationStore.DerivedRelation;
import io.nullnull.catalog.application.CatalogRelationStore.RulePair;
import io.nullnull.shared.ids.UuidV7;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BA-026: the producer {@code place_relations} did not have.
 *
 * <p>Everything downstream of this was built and green - V027 stores relations, CatalogRelationQuery
 * selects them, {@code listRelatedPlaces} projects them, and seven BA-024 clauses hold. What was
 * missing was anything that writes a row, so in production that route could only ever answer
 * {@code UNKNOWN(SOURCE_DISABLED)}: not because the gate was closed, but because the table was empty.
 * The same gap BA-025 closed for opening hours, and found the same way.
 *
 * <p>It is a re-evaluation rather than a curation, and that is the one place it differs from A-031 and
 * A-032. Those needed a person to read a page, so a reviewable plan file was the artefact. Here the
 * input is already in our own database - a plan file would be an operator transcribing our catalog
 * back to us, and the only thing it could add is a chance to mistype it.
 */
@Service
public class CatalogRelationDeriver {

    /** The registry row for "our own taxonomy and region rule" (V007), PROD_APPROVED and enabled. */
    public static final String SOURCE = "NULLNULL_CATALOG_RULE";

    /** Shown beside the candidate. V027 refuses a row with nothing to say. */
    static final String REASON = "같은 분류·지역";

    /**
     * How long a derived row stands, taken from the registry rather than chosen: NULLNULL_CATALOG_RULE
     * carries {@code stale_after_seconds = 604800} and a refresh expectation of "카탈로그 변경 시 재평가".
     * A rule-derived relation is a statement about the catalog as it stood, and the catalog moves.
     */
    static final Duration TRUSTED_FOR = Duration.ofDays(7);

    /**
     * The most candidates one source place may produce, taken from policy-v1's
     * {@code candidateCaps.relatedPerChannel}; {@code PolicyPinsParityTest} fails the build if the two
     * drift. No ranker sits after this (ADR-0006 · 예외), so this is the only cap on the path, and a
     * source over it is not trimmed here - see {@link #derive()}.
     */
    public static final int MAX_PER_SOURCE = 100;

    private final CatalogRelationStore relations;
    private final Clock clock;

    public CatalogRelationDeriver(CatalogRelationStore relations, Clock clock) {
        this.relations = relations;
        this.clock = clock;
    }

    /**
     * One pass: refresh what the rule still says, then close what it no longer says.
     *
     * <p>That order matters. Refreshing first means a pair that still matches has its window extended
     * before anything looks for pairs to close, so no row is closed and reopened within one run.
     *
     * <p><b>A source place over the cap is skipped whole, not trimmed.</b> Trimming would mean picking
     * a hundred of its peers, and nothing here has a reason to prefer any hundred - the rule says they
     * are all equally similar, which is exactly why it is a weak rule. An arbitrary subset would be a
     * ranking nobody computed, presented as evidence. The place is named in the report instead, and
     * its existing rows are left alone: they still satisfy the rule, so nothing closes them, and they
     * lapse on their own seven days after the last run that could refresh them.
     */
    @Transactional
    public DerivationReport derive() {
        Instant now = clock.instant();
        Map<UUID, List<UUID>> bySource = new LinkedHashMap<>();
        for (RulePair pair : relations.ruleCandidates()) {
            bySource.computeIfAbsent(pair.sourcePlaceId(), key -> new ArrayList<>())
                    .add(pair.targetPlaceId());
        }

        long revision = relations.currentRevision(SOURCE);
        List<UUID> skipped = new ArrayList<>();
        List<DerivedRelation> rows = new ArrayList<>();
        bySource.forEach((source, targets) -> {
            if (targets.size() > MAX_PER_SOURCE) {
                skipped.add(source);
                return;
            }
            for (UUID target : targets) {
                rows.add(new DerivedRelation(UuidV7.create(clock), source, target, SOURCE, revision,
                        now, now.plus(TRUSTED_FOR), now));
            }
        });

        int recorded = relations.upsert(List.copyOf(rows), REASON);
        int expired = relations.expireUnmatched(now, SOURCE);
        return new DerivationReport(recorded, expired, List.copyOf(skipped));
    }

    /**
     * What one pass did. {@code skipped} is the part an operator has to act on: those places have no
     * derived relations and the report is the only thing that says so.
     */
    public record DerivationReport(int recorded, int expired, List<UUID> skipped) {
    }
}
