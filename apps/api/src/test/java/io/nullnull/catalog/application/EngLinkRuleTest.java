package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-086 English record to canonical place link rule")
class EngLinkRuleTest {

    // 경복궁 as the canonical place: lclsSystm1 HS, lDongRegnCd 11, lDongSignguCd 110.
    private static final EngLinkRule.PlaceFacts PLACE = new EngLinkRule.PlaceFacts(
            new BigDecimal("37.579617"), new BigDecimal("126.977041"), "HS", "11", "110");

    @Test
    @DisplayName("a record 99 m away with the same classification and legal dong is the same place")
    void sameCodesWithinTheRadiusHold() {
        assertThat(EngLinkRule.holds(record(north(99), "126.977041", "HS", "11", "110"), PLACE)).isTrue();
    }

    @Test
    @DisplayName("a record 101 m away is not, even with every code equal")
    void beyondTheRadiusFails() {
        assertThat(EngLinkRule.holds(record(north(101), "126.977041", "HS", "11", "110"), PLACE)).isFalse();
    }

    @Test
    @DisplayName("each code must match on its own")
    void eachCodeIsRequired() {
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", "VE", "11", "110"), PLACE)).isFalse();
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", "HS", "41", "110"), PLACE)).isFalse();
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", "HS", "11", "140"), PLACE)).isFalse();
    }

    @Test
    @DisplayName("a missing coordinate or code on either side fails rather than being assumed")
    void missingFactsFail() {
        assertThat(EngLinkRule.holds(record(null, null, "HS", "11", "110"), PLACE)).isFalse();
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", null, "11", "110"), PLACE)).isFalse();
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", "HS", null, "110"), PLACE)).isFalse();
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", "HS", "11", null), PLACE)).isFalse();
        EngLinkRule.PlaceFacts noSnapshot = new EngLinkRule.PlaceFacts(
                new BigDecimal("37.579617"), new BigDecimal("126.977041"), "HS", "11", null);
        assertThat(EngLinkRule.holds(record("37.579617", "126.977041", "HS", "11", "110"), noSnapshot)).isFalse();
    }

    @Test
    @DisplayName("the distance is the one the owner read off the match probe")
    void distanceIsTheProbeDistance() {
        // One degree of latitude on a sphere of radius 6,371,008.8 m is 111,195 m to the metre.
        assertThat(Math.round(GeoDistance.haversineMeters(
                new BigDecimal("37"), new BigDecimal("127"), new BigDecimal("38"), new BigDecimal("127"))))
                .isEqualTo(111_195L);
    }

    private static String north(int metres) {
        // Latitude offset for a northward move of that many metres on the same sphere.
        double degrees = Math.toDegrees(metres / GeoDistance.EARTH_RADIUS_METERS);
        return new BigDecimal("37.579617").add(BigDecimal.valueOf(degrees))
                .setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static KtoEngRecord record(String latitude, String longitude, String classification, String region,
            String sigungu) {
        return KtoEngRecord.of("264329", "76", "Gyeongbokgung Palace", null,
                latitude == null ? null : new BigDecimal(latitude), longitude == null ? null : new BigDecimal(longitude),
                classification, region, sigungu);
    }
}
