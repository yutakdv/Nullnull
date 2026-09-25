package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-086 step 1: the English probe answers whether a Korean content id resolves in the English
 * dataset and comes back in English, and must not become a copy of the provider's body.
 *
 * <p>Two clauses, not one, and the split is the point. {@code T10} is the question the probe was
 * built to answer - does a content id we already hold resolve in the English dataset, and what
 * shape comes back. {@code T11} is an invariant-10 claim: the report measures the value's language
 * without copying the value. Folded together, a single test that only checked content-id
 * resolution would satisfy the id, and a regression that printed the provider's prose into
 * operator evidence would have nothing watching it.
 *
 * <p>The load-bearing assertions are the negative ones. A shape report that quietly carried the
 * whole response would still look like a shape report in the operator's terminal, and
 * {@code CMP-KTO-008} is the rule it would break.
 */
class KtoEngServiceProbeMainTest {

    private static final String CANARY = "이 문장은 응답 본문에만 있고 보고서에 나오면 안 된다";

    private static String body(String title, String areacode, String ldongRegnCd) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                "body":{"totalCount":1,"items":{"item":[{
                  "contentid":"126508","contenttypeid":"12",
                  "title":"%s",
                  "addr1":"161 Sajik-ro, Jongno-gu, Seoul",
                  "overview":"%s",
                  "areacode":"%s",
                  "lDongRegnCd":"%s",
                  "homepage":"" }]}}}}
                """.formatted(title, CANARY, areacode, ldongRegnCd);
    }

    @Test
    @DisplayName("BA-086-T11 the report names every field and copies none of them")
    void reportsShapeWithoutCopyingTheBody() {
        List<String> lines = KtoEngServiceProbeMain.report(body("Gyeongbokgung Palace", "1", ""), "126508");
        String report = String.join("\n", lines);

        assertThat(report).contains("verdict=OBSERVED");
        assertThat(report).contains("name=title").contains("name=addr1").contains("name=overview");
        // overview is a deciding field, so it is MEASURED - and still never shown. Nor is any other text
        // the provider sent: the English title and address are provider text as much as the overview.
        assertThat(report).doesNotContain(CANARY);
        assertThat(providerText(body("Gyeongbokgung Palace", "1", ""))).isNotEmpty()
                .allSatisfy(value -> assertThat(report).as(value).doesNotContain(value));
        assertThat(report).contains("length=" + CANARY.length());
        // Only the deciding fields carry a language measurement. This guards the list itself: adding
        // contentid to it would not stay green.
        assertThat(lines.stream().filter(line -> line.contains(" asciiLetterPercent=")))
                .hasSize(4)
                .allMatch(line -> line.contains("name=title ") || line.contains("name=addr1 ")
                        || line.contains("name=overview ") || line.contains("name=areacode ")
                        || line.contains("name=lDongRegnCd "));
    }

    @Test
    @DisplayName("BA-086-T28 an English value and a Korean one are told apart by whether they carry Hangul")
    void languageIsMeasured() {
        String english = String.join("\n",
                KtoEngServiceProbeMain.report(body("Gyeongbokgung Palace", "1", ""), "126508"));
        assertThat(english).contains("name=title type=scalar length=20 empty=false asciiLetterPercent=100 hangul=false");

        // The failure this exists to catch: the English endpoint echoing the Korean title back.
        String echoed = String.join("\n", KtoEngServiceProbeMain.report(body("경복궁", "1", ""), "126508"));
        assertThat(echoed).contains("name=title").contains("asciiLetterPercent=0").contains("hangul=true");
    }

    @Test
    @DisplayName("BA-086-T11 a Korean title echoed back is measured, not quoted")
    void anEchoedKoreanTitleIsNotQuoted() {
        // T28 decides the echo is Korean; this is the other clause - deciding it does not print it.
        String echoed = String.join("\n", KtoEngServiceProbeMain.report(body("경복궁", "1", ""), "126508"));
        assertThat(echoed).contains("name=title").doesNotContain("경복궁");
        assertThat(providerText(body("경복궁", "1", ""))).isNotEmpty()
                .allSatisfy(value -> assertThat(echoed).as(value).doesNotContain(value));
    }

    /**
     * Every text value the provider sent, from the header and the item. Codes that are all digits are left out:
     * the report prints lengths and our own content id as numbers, so "12" or "1" would match those, and the
     * probe's job with a code is to show its length, which the field lines above already hold. Empty values
     * cannot be found in anything.
     */
    private static List<String> providerText(String body) {
        List<String> values = new ArrayList<>();
        collectText(new ObjectMapper().readTree(body), values);
        values.removeIf(value -> value.isBlank() || value.chars().allMatch(Character::isDigit));
        return values;
    }

    private static void collectText(JsonNode node, List<String> values) {
        if (node.isString()) {
            values.add(node.stringValue());
        }
        for (JsonNode child : node) {
            collectText(child, values);
        }
    }

    @Test
    @DisplayName("BA-086-T29 punctuation and digits do not drag a value towards the middle")
    void onlyLettersCountTowardsTheLanguageShare() {
        // "161 Sajik-ro, Jongno-gu, Seoul" is 30 characters, of which the digits, spaces, hyphens
        // and commas are language-neutral. Counting them would report something near 70% for a
        // value that is entirely English, and the same for one that is entirely Korean.
        assertThat(KtoEngServiceProbeMain.asciiLetterPercent("161 Sajik-ro, Jongno-gu, Seoul")).isEqualTo(100);
        assertThat(KtoEngServiceProbeMain.asciiLetterPercent("161 사직로, 종로구, 서울")).isZero();
        // A value with no letters at all is 0 rather than a division by zero.
        assertThat(KtoEngServiceProbeMain.asciiLetterPercent("1, 2, 3")).isZero();
    }

    @Test
    @DisplayName("BA-086-T10 the areacode/lDongRegnCd disagreement A-034 found on the Korean side is visible here")
    void theRegionCodePairIsMeasuredOnBothSides() {
        String drifted = String.join("\n", KtoEngServiceProbeMain.report(body("Palace", "", "11"), "126508"));
        assertThat(drifted).contains("name=areacode type=scalar length=0 empty=true");
        assertThat(drifted).contains("name=lDongRegnCd type=scalar length=2 empty=false");
    }

    @Test
    @DisplayName("BA-086-T10 a content id the English dataset does not carry is a verdict, not a field-less observation")
    void anAbsentContentIdIsItsOwnAnswer() {
        String empty = "{\"response\":{\"header\":{\"resultCode\":\"0000\"},"
                + "\"body\":{\"totalCount\":0,\"items\":{\"item\":[]}}}}";
        String report = String.join("\n", KtoEngServiceProbeMain.report(empty, "126508"));
        assertThat(report).contains("totalCount=0").contains("verdict=NO_ITEM").doesNotContain("verdict=OBSERVED");
    }

    @Test
    @DisplayName("BA-086-T10 a provider error is reported as one, not parsed as an observation")
    void providerErrorIsNotAnObservation() {
        String error = "{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"LIMITED\"}}}";
        String report = String.join("\n", KtoEngServiceProbeMain.report(error, "126508"));
        assertThat(report).contains("verdict=PROVIDER_ERROR").doesNotContain("verdict=OBSERVED");
    }

    @Test
    @DisplayName("BA-086-T11 a non-JSON answer is named, not dumped")
    void nonJsonIsNamedNotDumped() {
        String html = "<html><body>" + CANARY + "</body></html>";
        String report = String.join("\n", KtoEngServiceProbeMain.report(html, "126508"));
        assertThat(report).contains("verdict=NOT_JSON").doesNotContain(CANARY);
    }
}
