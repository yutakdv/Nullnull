package io.nullnull.crowd.infrastructure.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.KtoCallInventory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CMP-KTO-006 the KTO call inventory's output")
class KtoCallInventoryMainTest {

    private static final KtoCallInventory.Operation DETAIL = new KtoCallInventory.Operation(
            "KTO_KOR_SERVICE_2", "KOR_SERVICE_2_DETAIL_COMMON", 3,
            Instant.parse("2026-09-20T01:00:00Z"), Instant.parse("2026-09-20T02:00:00Z"));

    @Test
    @DisplayName("the target names the database without its user, password or query")
    void theTargetCarriesNoCredential() {
        assertThat(KtoCallInventoryMain.target(
                "jdbc:postgresql://reader:hunter2@db.internal:5432/nullnull?password=hunter2&sslmode=require"))
                .isEqualTo("postgresql://db.internal:5432/nullnull");
        assertThat(KtoCallInventoryMain.target("jdbc:postgresql://localhost/nullnull"))
                .isEqualTo("postgresql://localhost/nullnull");
        assertThat(KtoCallInventoryMain.target("")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("only a deployed environment's list with at least one usable call counts as evidence")
    void evidenceFollowsTheActualCallRule() {
        KtoCallInventory used = new KtoCallInventory("r1", List.of(DETAIL), 0, 0);
        KtoCallInventory unused = new KtoCallInventory("r1", List.of(), 2, 1);
        assertThat(KtoCallInventoryMain.evidence("staging", used)).isEqualTo("counts_as_evidence=true");
        assertThat(KtoCallInventoryMain.evidence("production", used)).isEqualTo("counts_as_evidence=true");
        for (String local : List.of("local", "test", "ci", "development", "")) {
            assertThat(KtoCallInventoryMain.evidence(local, used)).as(local)
                    .isEqualTo("counts_as_evidence=false reason=environment-not-deployed");
        }
        assertThat(KtoCallInventoryMain.evidence("staging", unused))
                .isEqualTo("counts_as_evidence=false reason=no-usable-call");
    }

    @Test
    @DisplayName("the report names the database, environment and release, then each operation and what was left out")
    void theReportIsSelfDescribing() {
        String report = KtoCallInventoryMain.render("postgresql://db.internal:5432/nullnull", "staging",
                new KtoCallInventory("2026.09.20-1", List.of(DETAIL), 2, 1));
        assertThat(report.lines().toList()).containsExactly(
                "kto_inventory target=postgresql://db.internal:5432/nullnull environment=staging release=2026.09.20-1",
                "kto_operation source=KTO_KOR_SERVICE_2 endpoint=KOR_SERVICE_2_DETAIL_COMMON calls=3"
                        + " first=2026-09-20T01:00:00Z last=2026-09-20T02:00:00Z",
                "kto_inventory_excluded rejected=2 replay=1",
                "kto_inventory operations=1 counts_as_evidence=true");
    }
}
