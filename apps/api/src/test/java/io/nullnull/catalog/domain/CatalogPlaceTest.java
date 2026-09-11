package io.nullnull.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-022 canonical place identity")
class CatalogPlaceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    @DisplayName("BA-022-T1 an active canonical place has no redirect and a deprecated ID resolves once")
    void preservesCanonicalAndDeprecatedIdentityRules() {
        UUID canonicalId = UUID.randomUUID();
        CatalogPlace canonical = place(canonicalId, null, CatalogPlaceStatus.ACTIVE);
        CatalogPlace deprecated = place(UUID.randomUUID(), canonicalId, CatalogPlaceStatus.DEPRECATED);

        assertThat(canonical.isCanonical()).isTrue();
        assertThat(canonical.resolvedPlaceId()).isEqualTo(canonicalId);
        assertThat(deprecated.isCanonical()).isFalse();
        assertThat(deprecated.resolvedPlaceId()).isEqualTo(canonicalId);
    }

    @Test
    @DisplayName("BA-022-T1 malformed canonical references and incomplete coordinates are refused before persistence")
    void rejectsInvalidCanonicalShape() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> place(id, UUID.randomUUID(), CatalogPlaceStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> place(id, id, CatalogPlaceStatus.DEPRECATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPlace(id, null, "테스트", "A0101", BigDecimal.valueOf(37.5), null,
                "1", CatalogPlaceStatus.ACTIVE, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogPlace(id, null, " ", "A0101", null, null,
                "1", CatalogPlaceStatus.ACTIVE, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CatalogPlace place(UUID id, UUID canonicalPlaceId, CatalogPlaceStatus status) {
        return new CatalogPlace(id, canonicalPlaceId, "서울 테스트 관광지", "A0101",
                new BigDecimal("37.566535"), new BigDecimal("126.978001"), "1", status, NOW, NOW);
    }
}
