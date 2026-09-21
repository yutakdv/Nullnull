package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.infrastructure.kto.KtoEngServiceMatchProbeMain.Place;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-086 step 1 after the id lookup failed: the match probe reports what an English item could be
 * matched by, and must not become a copy of the provider's list.
 *
 * <p>Two clauses, split for the same reason {@link KtoEngServiceProbeMainTest} splits them. One is
 * the question the probe was built to answer - which candidates are near a place we hold, how far,
 * carrying which codes and which fields. The other is CMP-KTO-008: provider prose never reaches the
 * operator's terminal. Folded together, a test that only checked distances would satisfy the id and
 * a regression that printed titles would have nothing watching it.
 */
class KtoEngServiceMatchProbeMainTest {

    private static final String CANARY = "Only in the provider body and never in the report";
    private static final Place GYEONGBOKGUNG =
            new Place("126508", new BigDecimal("37.579617"), new BigDecimal("126.977041"), null);

    private static String list(String items) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                "body":{"totalCount":2,"items":{"item":[%s]}}}}
                """.formatted(items);
    }

    /** Same coordinate as the place, so the distance is exactly zero. */
    private static final String SAME_SPOT = """
            {"contentid":"264337","contenttypeid":"76","title":"%s","addr1":"%s",
             "mapx":"126.977041","mapy":"37.579617","lDongRegnCd":"11","lDongSignguCd":"110"}
            """.formatted(CANARY, CANARY);

    /** 0.009 degrees of latitude north: about 1,001 m. */
    private static final String NORTH = """
            {"contentid":"1000001","contenttypeid":"76","title":"Somewhere Else","addr1":"",
             "mapx":"126.977041","mapy":"37.588617","cat1":"A02"}
            """;

    @Test
    @DisplayName("BA-086-T15 the report lists each candidate with its computed distance, its codes and the field names")
    void candidatesCarryDistanceCodesAndShape() {
        List<String> lines = KtoEngServiceMatchProbeMain.report(list(SAME_SPOT + "," + NORTH), 1, GYEONGBOKGUNG);
        String report = String.join("\n", lines);

        assertThat(report).contains("resultCode=0000 totalCount=2 returned=2");
        // The field names are the observation that answers "is there a link back to the Korean id".
        assertThat(report).contains("fields=addr1,cat1,contentid,contenttypeid,lDongRegnCd,lDongSignguCd,mapx,mapy,title");
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("KTO_ENG_MATCH_PROBE_CANDIDATE index=1 rank=1 ")
                .contains("distanceMeters=0").contains("contentid=264337").contains("contenttypeid=76")
                .contains("lDongRegnCd=11").contains("lDongSignguCd=110"));
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("KTO_ENG_MATCH_PROBE_CANDIDATE index=1 rank=2 ")
                .contains("distanceMeters=1001").contains("contentid=1000001").contains("cat1=A02"));
        assertThat(report).contains("verdict=OBSERVED candidates=2");
    }

    @Test
    @DisplayName("the probe reports candidates and never decides which one is the place")
    void noMatchVerdictIsIssued() {
        String report = String.join("\n",
                KtoEngServiceMatchProbeMain.report(list(SAME_SPOT + "," + NORTH), 1, GYEONGBOKGUNG));
        // A matcher needs a threshold and a type correspondence nobody has observed yet.
        assertThat(report).doesNotContain("UNIQUE").doesNotContain("AMBIGUOUS").doesNotContain("MATCH ");
    }

    @Test
    @DisplayName("BA-086-T15 an empty neighbourhood is its own verdict and an unusable coordinate is not a distance")
    void emptyAndUnusable() {
        String empty = "{\"response\":{\"header\":{\"resultCode\":\"0000\"},"
                + "\"body\":{\"totalCount\":0,\"items\":\"\"}}}";
        assertThat(KtoEngServiceMatchProbeMain.report(empty, 2, GYEONGBOKGUNG))
                .contains("KTO_ENG_MATCH_PROBE_RESULT index=2 verdict=NONE");

        String noCoordinate = list("{\"contentid\":\"5\",\"title\":\"x\",\"mapx\":\"\",\"mapy\":\"\"}");
        assertThat(String.join("\n", KtoEngServiceMatchProbeMain.report(noCoordinate, 1, GYEONGBOKGUNG)))
                .contains("distanceMeters=unknown");
    }

    @Test
    @DisplayName("BA-086-T15 a provider error is reported as one, not parsed as candidates")
    void providerErrorIsNotAnObservation() {
        String error = "{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"" + CANARY + "\"}}}";
        String report = String.join("\n", KtoEngServiceMatchProbeMain.report(error, 1, GYEONGBOKGUNG));
        assertThat(report).contains("resultCode=22").contains("verdict=PROVIDER_ERROR")
                .doesNotContain("verdict=OBSERVED").doesNotContain(CANARY);
    }

    @Test
    @DisplayName("BA-086-T16 titles and addresses are measured, never copied")
    void proseIsMeasuredNotQuoted() {
        String report = String.join("\n", KtoEngServiceMatchProbeMain.report(list(SAME_SPOT), 1, GYEONGBOKGUNG));
        assertThat(report).doesNotContain(CANARY);
        assertThat(report).contains("titleLength=" + CANARY.length()).contains("titleAsciiLetterPercent=100")
                .contains("titleHangul=false");

        String echoed = list(SAME_SPOT.replace(CANARY, "경복궁"));
        String korean = String.join("\n", KtoEngServiceMatchProbeMain.report(echoed, 1, GYEONGBOKGUNG));
        assertThat(korean).contains("titleAsciiLetterPercent=0").contains("titleHangul=true").doesNotContain("경복궁");
    }

    @Test
    @DisplayName("BA-086-T16 a code field carrying prose is reported by length, not printed")
    void codeFieldsPrintOnlyCodes() {
        String smuggled = list("{\"contentid\":\"" + CANARY + "\",\"cat1\":\"경복궁\",\"mapx\":\"126.977041\","
                + "\"mapy\":\"37.579617\"}");
        String report = String.join("\n", KtoEngServiceMatchProbeMain.report(smuggled, 1, GYEONGBOKGUNG));
        assertThat(report).doesNotContain(CANARY).doesNotContain("경복궁")
                .contains("contentid=<not-a-code length=" + CANARY.length() + ">")
                .contains("cat1=<not-a-code length=3>");
    }

    @Test
    @DisplayName("BA-086-T16 a non-JSON answer is named, not dumped")
    void nonJsonIsNamedNotDumped() {
        String report = String.join("\n",
                KtoEngServiceMatchProbeMain.report("<html>" + CANARY + "</html>", 1, GYEONGBOKGUNG));
        assertThat(report).contains("verdict=NOT_JSON").doesNotContain(CANARY);
    }

    private static String titled(String title) {
        return list("{\"contentid\":\"264329\",\"title\":\"" + title
                + "\",\"mapx\":\"126.977041\",\"mapy\":\"37.579617\"}");
    }

    private static Place named(String name) {
        return KtoEngServiceMatchProbeMain.withNames(List.of(GYEONGBOKGUNG), name).get(0);
    }

    @Test
    @DisplayName("BA-086-T18 a title whose Hangul segment is exactly our name is reported as equal")
    void exactHangulSegmentIsEvidence() {
        String report = String.join("\n",
                KtoEngServiceMatchProbeMain.report(titled("Gyeongbokgung Palace (경복궁)"), 1, named("경복궁")));
        assertThat(report).contains("hangulSegmentEqualsName=true").contains("nameInTitle=true");
    }

    @Test
    @DisplayName("BA-086-T18 containing our name is not equality: a different item named after the place stays unequal")
    void containmentIsNotEquality() {
        String report = String.join("\n", KtoEngServiceMatchProbeMain.report(
                titled("Royal Guard Changing Ceremony (경복궁 수문장 교대식)"), 1, named("경복궁")));
        assertThat(report).contains("hangulSegmentEqualsName=false").contains("nameInTitle=true");
    }

    @Test
    @DisplayName("BA-086-T18 a title with no Hangul is not equal, and whitespace alone does not break equality")
    void noHangulAndWhitespace() {
        assertThat(String.join("\n",
                KtoEngServiceMatchProbeMain.report(titled("Gyeongbokgung Palace"), 1, named("경복궁"))))
                .contains("hangulSegmentEqualsName=false").contains("nameInTitle=false");
        assertThat(String.join("\n", KtoEngServiceMatchProbeMain.report(
                titled("N Seoul Tower (서울  타워)"), 1, named(" 서울 타워 "))))
                .contains("hangulSegmentEqualsName=true");
    }

    @Test
    @DisplayName("BA-086-T16 the name evidence prints neither our name nor the title")
    void nameEvidenceQuotesNothing() {
        String report = String.join("\n",
                KtoEngServiceMatchProbeMain.report(titled("Deoksugung (덕수궁)"), 1, named("덕수궁")));
        assertThat(report).doesNotContain("덕수궁").doesNotContain("Deoksugung");
    }

    @Test
    @DisplayName("BA-086-T18 names are optional, but when given they must cover every place in order")
    void namesCoverEveryPlace() {
        assertThat(String.join("\n", KtoEngServiceMatchProbeMain.report(titled("Palace (경복궁)"), 1, GYEONGBOKGUNG)))
                .doesNotContain("hangulSegmentEqualsName").doesNotContain("nameInTitle");
        List<Place> two = KtoEngServiceMatchProbeMain.places("1:0:0,2:0:0");
        assertThatThrownBy(() -> KtoEngServiceMatchProbeMain.withNames(two, "경복궁"))
                .hasMessageContaining("2 expected, 1 given");
        assertThatThrownBy(() -> KtoEngServiceMatchProbeMain.withNames(two, "경복궁|"))
                .hasMessageContaining("empty name at position 2");
    }

    @Test
    @DisplayName("BA-086-T17 the place list is bounded: at most five places, each id:lat:lon")
    void placeListIsBounded() {
        assertThat(KtoEngServiceMatchProbeMain.places("126508:37.579617:126.977041, 1:0:0")).hasSize(2);
        String six = String.join(",", List.of("1:0:0", "2:0:0", "3:0:0", "4:0:0", "5:0:0", "6:0:0"));
        assertThatThrownBy(() -> KtoEngServiceMatchProbeMain.places(six)).hasMessageContaining("at most 5");
        assertThatThrownBy(() -> KtoEngServiceMatchProbeMain.places("126508:37.5")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KtoEngServiceMatchProbeMain.places("126508:91:0")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KtoEngServiceMatchProbeMain.places("")).isInstanceOf(IllegalStateException.class);
    }
}
