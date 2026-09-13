package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogRelationQuery.CatalogRelationCandidate;
import io.nullnull.catalog.application.CatalogRelationQuery.CatalogRelationSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-024's convergence rule, tested where it is a function rather than where it is a route.
 *
 * <p>The clause is about every order the evidence could arrive in, and an integration test can only
 * drive the orders this PostgreSQL instance happens to choose - two rows that differ only by
 * {@code source_code} have no order in {@code JdbcCatalogRelationQuery}'s SQL, so which one arrives
 * first is a planner decision, not something a fixture can set. Here the input is a plain list, so
 * every permutation of it is reachable and all of them are asserted.
 */
@DisplayName("BA-024 relation convergence")
class CatalogRelationProjectionServiceTest {

    private static final Instant RECORDED = Instant.parse("2031-01-01T00:00:00Z");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String RULE = "NULLNULL_CATALOG_RULE";
    private static final String OFFICIAL = "KTO_RELATED_PLACES";
    /**
     * A provider whose registry code sorts <em>after</em> our own rule's. The tie-break between two
     * rows for one place is (tier, source code), so a fixture whose stronger row also has the smaller
     * code cannot tell the two arms apart - both would pick the same row and the test would pass for
     * a comparator that never looked at the tier.
     */
    private static final String LATER_PROVIDER = "SEOUL_CITYDATA";

    @Test
    @DisplayName("BA-024-T2 the same evidence converges the same way in every order it can arrive in")
    void convergenceIsAFunctionOfTheEvidenceAndNotOfItsArrivalOrder() {
        // Two sources naming one place, a second place named once, and a third named by the other
        // source alone: enough that a rule which kept whichever row it saw first would answer
        // differently for at least one arrival order.
        List<CatalogRelationCandidate> evidence = List.of(
                similar(SECOND, RULE), similar(SECOND, OFFICIAL), similar(FIRST, RULE), similar(THIRD, OFFICIAL));

        List<List<CatalogRelationCandidate>> orders = permutations(evidence);
        List<CatalogRelationCandidate> settled = CatalogRelationProjectionService.converge(orders.getFirst());

        assertThat(orders).hasSize(24);
        for (List<CatalogRelationCandidate> order : orders) {
            assertThat(CatalogRelationProjectionService.converge(order)).isEqualTo(settled);
        }
        // And the answer it settles on is the merged one, not merely a stable copy of the input:
        // three places for four rows, in target order.
        assertThat(settled).extracting(CatalogRelationCandidate::targetPlaceId)
                .containsExactly(FIRST, SECOND, THIRD);
    }

    @Test
    @DisplayName("the surviving evidence for one place is the strongest, not the earliest")
    void theStrongestEvidenceSurvivesAMerge() {
        // The EXACT row carries the larger source code, so picking it cannot be the code tie-break
        // doing the work. V027 admits EXACT only for a confirmed provider-direct relation, which is
        // why the weaker row is the one our own rule wrote.
        CatalogRelationCandidate ours = similar(FIRST, RULE);
        CatalogRelationCandidate official = exact(FIRST, LATER_PROVIDER);

        assertThat(CatalogRelationProjectionService.converge(List.of(ours, official))).containsExactly(official);
        assertThat(CatalogRelationProjectionService.converge(List.of(official, ours))).containsExactly(official);
    }

    /** Every arrival order, because the clause is about all of them and there are only 24. */
    private static List<List<CatalogRelationCandidate>> permutations(List<CatalogRelationCandidate> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<CatalogRelationCandidate>> orders = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            List<CatalogRelationCandidate> rest = new ArrayList<>(items);
            CatalogRelationCandidate head = rest.remove(index);
            for (List<CatalogRelationCandidate> tail : permutations(rest)) {
                List<CatalogRelationCandidate> order = new ArrayList<>();
                order.add(head);
                order.addAll(tail);
                orders.add(List.copyOf(order));
            }
        }
        return List.copyOf(orders);
    }

    private static CatalogRelationCandidate similar(UUID target, String sourceCode) {
        return candidate(target, "SIMILAR", sourceCode);
    }

    private static CatalogRelationCandidate exact(UUID target, String sourceCode) {
        return candidate(target, "EXACT", sourceCode);
    }

    private static CatalogRelationCandidate candidate(UUID target, String relationType, String sourceCode) {
        return new CatalogRelationCandidate(UUID.nameUUIDFromBytes((target + relationType + sourceCode).getBytes()),
                target, relationType, "같은 분류·지역",
                RULE.equals(sourceCode) ? "INTERNAL_RULE" : "PROVIDER_DIRECT",
                // V027 admits EXACT only alongside a confirmed mapping, so a fixture that paired them
                // differently would be asserting on a row the table would refuse.
                "EXACT".equals(relationType) ? "CONFIRMED" : "UNCERTAIN", RECORDED, null, RECORDED,
                new CatalogRelationSource(sourceCode, 1L, "출처", "QUALITATIVE", null, null, null, null, null,
                        null, "PLACE"));
    }
}
