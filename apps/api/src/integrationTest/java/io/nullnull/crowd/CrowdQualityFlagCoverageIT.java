package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.CrowdForecastQuery.SourceDescriptor;
import io.nullnull.crowd.application.CrowdForecastQuery.Snapshot;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.application.KtoForecastRequest;
import io.nullnull.crowd.application.KtoForecastResponseValidator;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.shared.provider.ProviderResponseValidator;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-023: the mirror of "a check that passes without proving anything" is a guard that is fully
 * implemented and can never fire.
 *
 * <p>{@code crowd_snapshots.quality_flags} declares five values and
 * {@link io.nullnull.crowd.domain.TemporalComparisonPolicy} makes decisions from them under invariant
 * 8, but the only writer of that table hardcodes an empty array. Four of the five therefore never
 * appear on real data, and nothing said so. This test makes each value account for itself: it is
 * either demonstrated to be producible, demonstrated to be unreachable because the response is
 * rejected before a snapshot exists, or registered as awaiting a source that is not built yet - and
 * a value in the database CHECK that is in none of the three groups fails here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-023 crowd quality flag coverage")
class CrowdQualityFlagCoverageIT {

    private static final Instant NOW = Instant.parse("2032-01-01T00:00:00Z");

    /**
     * Declared, with no producer, on purpose. A forecast row has no {@code observed_at}, so skew is
     * undefined for it, and this operation refuses a partial payload outright rather than storing one.
     * Both belong to the live/replay collector, which is BA-090. Inventing a producer now would mean
     * inventing the condition it detects.
     */
    private static final Map<QualityFlag, String> AWAITING_SOURCE = Map.of(
            QualityFlag.OBSERVED_AT_SKEW, "BA-090 live collector: forecast rows carry no observed_at",
            QualityFlag.PARTIAL_PAYLOAD, "BA-090 live collector: this operation rejects a partial payload");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-023-T1 every declared quality flag is produced, structurally impossible, or registered")
    void everyDeclaredQualityFlagAccountsForItself() {
        Set<QualityFlag> produced = flagsTheProjectionCanActuallyProduce();
        Set<QualityFlag> impossible = flagsWhoseResponseIsRejectedBeforeASnapshotExists();

        assertThat(produced).as("a live provider incident is the one flag a real row can carry today")
                .containsExactly(QualityFlag.PROVIDER_INCIDENT);
        assertThat(impossible).as("both outcomes reject, so no snapshot can ever carry these")
                .containsExactlyInAnyOrder(QualityFlag.SCHEMA_DRIFT, QualityFlag.MAPPING_UNCERTAIN);
        assertThat(AWAITING_SOURCE.values()).allSatisfy(owner -> assertThat(owner).isNotBlank());

        Set<String> accounted = new LinkedHashSet<>();
        produced.forEach(flag -> accounted.add(flag.name()));
        impossible.forEach(flag -> accounted.add(flag.name()));
        AWAITING_SOURCE.keySet().forEach(flag -> accounted.add(flag.name()));

        Set<String> declaredInJava = Arrays.stream(QualityFlag.values()).map(Enum::name)
                .collect(Collectors.toSet());
        Set<String> declaredInDatabase = checkConstraintValues("crowd_snapshots_quality_flags_check");

        assertThat(declaredInJava).as("the enum and the CHECK must declare the same vocabulary")
                .containsExactlyInAnyOrderElementsOf(declaredInDatabase);
        assertThat(accounted).as("a new quality flag must say which of the three it is")
                .containsExactlyInAnyOrderElementsOf(declaredInJava);
    }

    /**
     * The only path that adds a flag to a stored set, exercised rather than asserted: the projection
     * adds PROVIDER_INCIDENT from live incident state, and adds nothing else.
     */
    private Set<QualityFlag> flagsTheProjectionCanActuallyProduce() {
        CrowdProvenanceProjection projection = new CrowdProvenanceProjection();
        Set<QualityFlag> quiet = projection.project(snapshot(false), NOW, false).provenance().qualityFlags().stream()
                .map(QualityFlag::valueOf).collect(Collectors.toSet());
        Set<QualityFlag> duringIncident = projection.project(snapshot(true), NOW, false).provenance().qualityFlags()
                .stream().map(QualityFlag::valueOf).collect(Collectors.toSet());
        assertThat(quiet).as("a stored empty set must project as empty, not as a default")
                .isEmpty();
        return duringIncident;
    }

    /**
     * Exercised too: a response that would justify either flag is refused, and the refusal carries no
     * snapshot, so the flag has nowhere to be written.
     */
    private Set<QualityFlag> flagsWhoseResponseIsRejectedBeforeASnapshotExists() {
        KtoForecastResponseValidator validator = new KtoForecastResponseValidator();
        KtoForecastRequest request = new KtoForecastRequest(UUID.randomUUID(), "11", "110", "테스트 관광지");
        KtoForecastResponseValidator.Validation drifted = validator.validate(
                """
                {"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[]}}}}
                """.getBytes(StandardCharsets.UTF_8), request, 2, UUID.randomUUID(), NOW, Duration.ofHours(24));
        KtoForecastResponseValidator.Validation ambiguous = validator.validate(
                """
                {"response":{"header":{"resultCode":"0000"},"body":{
                  "items":{"item":[{"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지",
                  "baseYmd":"20320101","cnctrRate":"12.3"}]},"totalCount":3390}}}
                """.getBytes(StandardCharsets.UTF_8), request, 2, UUID.randomUUID(), NOW, Duration.ofHours(24));

        assertThat(drifted.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        assertThat(ambiguous.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.MAPPING_UNCERTAIN);
        assertThat(drifted.hasCoverage()).as("a rejected response must not leave a snapshot to flag").isFalse();
        assertThat(ambiguous.hasCoverage()).as("a rejected response must not leave a snapshot to flag").isFalse();
        return Set.of(QualityFlag.SCHEMA_DRIFT, QualityFlag.MAPPING_UNCERTAIN);
    }

    private Set<String> checkConstraintValues(String constraintName) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraintName);
        assertThat(definition).as("constraint %s must exist", constraintName).isNotNull();
        Matcher matcher = Pattern.compile("\"([A-Z_]+)\"").matcher(definition);
        Set<String> values = new LinkedHashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        assertThat(values).as("the constraint text must actually enumerate values").isNotEmpty();
        return values;
    }

    private static Snapshot snapshot(boolean incidentActive) {
        return new Snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new SourceDescriptor("KTO_CONCENTRATION_FORECAST", "한국관광공사 관광지 집중률 예측", 2,
                        "이용허락범위 제한 없음", "https://www.data.go.kr/data/15128555/openapi.do",
                        "https://data.go.kr/ugs/selectPortalPolicyView.do", "출처: ⓒ한국관광공사",
                        "날짜 단위 상대 집중률 예측"),
                SourceState.FORECAST, null, NOW.plusSeconds(86_400), NOW.minusSeconds(60), NOW.plusSeconds(3600),
                "KTO_RELATIVE_CONCENTRATION_INDEX", BigDecimal.valueOf(42.5), "relative-index", null, null,
                Set.of(), "issue-1", "issue-1", "kto-tats-cnctr-rate-v4.1", null, ComparisonScope.PLACE,
                "fixture place", "DIRECT", false, incidentActive);
    }
}
