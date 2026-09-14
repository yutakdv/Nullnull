package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogVersion;
import io.nullnull.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A-034: one catalog version, read from the registry that already moves for the right reason.
 *
 * <p>Against the real database because the value's whole point is that it follows a row somebody
 * else maintains. A test that stubbed the registry would pin the format and prove nothing about
 * where the number comes from.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("catalog version")
class CatalogVersionIT {

    @Autowired
    CatalogVersion version;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("the version is the catalog source's own registry revision, named with the source")
    void theVersionFollowsTheRegistry() {
        long revision = jdbc.queryForObject("SELECT current_revision FROM source_registry WHERE code = ?",
                Long.class, CatalogVersion.SOURCE_CODE);

        assertThat(version.current()).isEqualTo(CatalogVersion.SOURCE_CODE + ":" + revision);
        // The source code is part of the value: a bare number cannot say what it is a revision of,
        // and the day a second catalog source exists that ambiguity would be silent.
        assertThat(version.current()).startsWith(CatalogVersion.SOURCE_CODE + ":");
    }

    @Test
    @DisplayName("raising the revision changes the version, which is what invalidates stored previews")
    void aBumpChangesTheValue() {
        String before = version.current();
        long next = jdbc.queryForObject("SELECT current_revision + 1 FROM source_registry WHERE code = ?",
                Long.class, CatalogVersion.SOURCE_CODE);
        // The revision row comes first, and not as a formality: source_registry.current_revision has
        // a foreign key to it, so the catalog version cannot be advanced without a recorded revision
        // to advance to. The first version of this test tried a bare UPDATE and was refused - which
        // is the constraint doing exactly what it should.
        jdbc.update("INSERT INTO source_registry_revisions (source_code, version, canonical_contract,"
                + " contract_hash, reviewed_at, created_at) VALUES (?, ?, '{}'::jsonb, ?, now(), now())",
                CatalogVersion.SOURCE_CODE, next, "d".repeat(64));
        jdbc.update("UPDATE source_registry SET current_revision = ? WHERE code = ?",
                next, CatalogVersion.SOURCE_CODE);
        try {
            // Runs pin this string in their fingerprint, so a bump makes every stored preview compare
            // unequal. That is the intended behaviour and this is where it is visible: a preview
            // computed against a catalog we no longer publish must not still be applicable.
            assertThat(version.current()).isNotEqualTo(before);
        } finally {
            jdbc.update("UPDATE source_registry SET current_revision = ? WHERE code = ?",
                    next - 1, CatalogVersion.SOURCE_CODE);
            jdbc.update("DELETE FROM source_registry_revisions WHERE source_code = ? AND version = ?",
                    CatalogVersion.SOURCE_CODE, next);
        }
        assertThat(version.current()).isEqualTo(before);
    }
}
