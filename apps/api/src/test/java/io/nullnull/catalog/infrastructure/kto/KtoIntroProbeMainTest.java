package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A-027: the probe answers one question and must not become a copy of the provider's body.
 *
 * <p>The interesting assertions here are the negative ones. A shape report that quietly includes the
 * whole response would still look like a shape report in the operator's terminal, and
 * {@code CMP-KTO-008} is the rule it would break.
 */
class KtoIntroProbeMainTest {

    private static final String CANARY = "이 문장은 응답 본문에만 있고 보고서에 나오면 안 된다";

    private static String body(String usetime) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                "body":{"totalCount":1,"items":{"item":[{
                  "contentid":"126508","contenttypeid":"12",
                  "usetime":"%s",
                  "restdate":"연중무휴",
                  "infocenter":"%s",
                  "chkbabycarriage":"" }]}}}}
                """.formatted(usetime, CANARY);
    }

    @Test
    @DisplayName("BA-021-T4 the report names every field and copies none of them")
    void reportsShapeWithoutCopyingTheBody() {
        List<String> lines = KtoIntroProbeMain.report(body("09:00~18:00"), "126508", "12");
        String report = String.join("\n", lines);

        assertThat(report).contains("verdict=OBSERVED");
        assertThat(report).contains("name=usetime").contains("name=restdate").contains("name=infocenter");
        // A field outside the question is named and measured, never shown.
        assertThat(report).doesNotContain(CANARY);
        assertThat(report).contains("name=infocenter type=scalar length=" + CANARY.length());
        // Only the two deciding fields may carry a preview. The canary above guards one field; this
        // guards the list itself, so adding contentid to it would not stay green.
        assertThat(lines.stream().filter(line -> line.contains(" preview=")))
                .hasSize(2)
                .allMatch(line -> line.contains("name=usetime ") || line.contains("name=restdate "));
    }

    @Test
    @DisplayName("BA-021-T4 the preview separates a time range from prose, which is the whole question")
    void previewDistinguishesStructureFromProse() {
        String structured = String.join("\n", KtoIntroProbeMain.report(body("09:00~18:00"), "126508", "12"));
        assertThat(structured).contains("preview=09:00~18:00").contains("markup=false").contains("lines=1");

        String prose = "관람 시간은 계절에 따라 다르며 자세한 내용은 홈페이지를 참고하시기 바랍니다. 동절기에는 한 시간 일찍 마감합니다.";
        String free = String.join("\n", KtoIntroProbeMain.report(body(prose), "126508", "12"));
        assertThat(free).contains("length=" + prose.length());
        // Long prose is truncated rather than reproduced: the decision needs the shape, not the text.
        assertThat(free).doesNotContain(prose);
        // BA-021-T4 names the bound, so the bound is asserted: 40 characters and the ellipsis.
        assertThat(free).contains("preview=" + prose.substring(0, 40) + "…");
    }

    @Test
    @DisplayName("BA-021-T4 a multi-line value cannot break the one-line-per-field report")
    void multiLineValuesAreFlattened() {
        // The escape stays escaped on the way in: a raw newline inside a JSON string is not JSON,
        // and this case is about what the report does with a value that really carries one.
        String report = String.join("\n",
                KtoIntroProbeMain.report(body("09:00~18:00\\n동절기 09:00~17:00"), "126508", "12"));
        long fieldLines = report.lines().filter(line -> line.startsWith("KTO_INTRO_PROBE_FIELD")).count();
        assertThat(fieldLines).isEqualTo(6);
        assertThat(report).contains("lines=2");
    }

    @Test
    @DisplayName("BA-021-T5 a provider error is reported as one, not parsed as an observation")
    void providerErrorIsNotAnObservation() {
        String error = "{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"LIMITED\"}}}";
        String report = String.join("\n", KtoIntroProbeMain.report(error, "126508", "12"));
        assertThat(report).contains("verdict=PROVIDER_ERROR").doesNotContain("verdict=OBSERVED");
    }

    @Test
    @DisplayName("BA-021-T4 BA-021-T5 a non-JSON answer is named, not dumped")
    void nonJsonIsNamedNotDumped() {
        String html = "<html><body>" + CANARY + "</body></html>";
        String report = String.join("\n", KtoIntroProbeMain.report(html, "126508", "12"));
        assertThat(report).contains("verdict=NOT_JSON").doesNotContain(CANARY);
    }

    @Test
    @DisplayName("BA-021-T5 an empty item list is reported rather than read as a field-less observation")
    void emptyItemsAreReported() {
        String empty = "{\"response\":{\"header\":{\"resultCode\":\"0000\"},"
                + "\"body\":{\"totalCount\":0,\"items\":{\"item\":[]}}}}";
        String report = String.join("\n", KtoIntroProbeMain.report(empty, "126508", "12"));
        assertThat(report).contains("verdict=NO_ITEM");
    }
}
