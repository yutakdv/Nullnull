package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.operations.application.ReadinessProbe;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "nullnull.env=test")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-086 English text source registration")
class EngTextSourceRegistryIT {

    private static final String SOURCE = "KTO_ENG_SERVICE";

    @Autowired SourceRegistryQuery registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired List<ReadinessProbe> probes;

    @Test
    @DisplayName("BA-086-T21 the English source is registered as collectable under a reviewed revision")
    void englishSourceIsCollectableUnderAReviewedRevision() {
        SourceRegistration source = registry.find(SOURCE).orElseThrow();

        assertThat(registry.enabledFor("production")).extracting(SourceRegistration::code).contains(SOURCE);
        assertThat(source.collectionEnabled()).isTrue();
        assertThat(source.currentRevision()).isEqualTo(1L);
        assertThat(source.staleAfterSeconds()).isEqualTo(604_800L);
        assertThat(source.quotaPerDay()).isEqualTo(1_000);
        // The revision is what textProvenance credits English text from, so its approved wording is the
        // user-facing credit: the owner's attribution decision (#60) and the portal's licence and page.
        Map<String, Object> revision = jdbc.queryForMap("""
                SELECT canonical_contract->>'attributionTemplate' AS attribution,
                       canonical_contract->'license'->>'name' AS license,
                       canonical_contract->>'officialUrl' AS official,
                       contract_hash = encode(sha256(convert_to(canonical_contract::text, 'UTF8')), 'hex')
                           AS hash_matches
                  FROM source_registry_revisions
                 WHERE source_code = ? AND version = 1
                """, SOURCE);
        assertThat(revision).containsEntry("attribution", "출처: ⓒ한국관광공사")
                .containsEntry("license", "이용허락범위 제한 없음 (관광정보 텍스트; 이미지 별도 심사)")
                .containsEntry("official", "https://www.data.go.kr/data/15101753/openapi.do")
                .containsEntry("hash_matches", true);
    }

    @Test
    @DisplayName("the English source has its own readiness probe, so a quarantine there is visible")
    void englishSourceHasAReadinessProbe() {
        ReadinessProbe probe = probes.stream().filter(candidate -> ("source:" + SOURCE).equals(candidate.name()))
                .findFirst().orElseThrow(() -> new AssertionError("no readiness probe for " + SOURCE));

        assertThat(probe.probe(Instant.now()).status()).isEqualTo(ReadinessProbe.ProbeStatus.READY);
    }
}
