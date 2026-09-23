package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Found;
import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Gone;
import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Rejected;
import io.nullnull.catalog.application.KtoEngDetailResponseValidator.Result;
import io.nullnull.shared.provider.ProviderResponseValidator.Outcome;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-086 KTO EngService2 detailCommon2 response validation")
class KtoEngDetailResponseValidatorTest {

    private final KtoEngDetailResponseValidator validator = new KtoEngDetailResponseValidator();
    private final KtoPlaceRequest request = new KtoPlaceRequest("264329", "76");

    @Test
    @DisplayName("one matching item becomes the text and the facts the link rule reads, and nothing else")
    void oneMatchingItemIsFound() {
        Result result = validate(item("264329", "76", "Gyeongbokgung Palace (경복궁)", "161, Sajik-ro",
                "37.579617", "126.977041"));

        assertThat(result).isInstanceOf(Found.class);
        KtoEngRecord record = ((Found) result).record();
        assertThat(record.contentId()).isEqualTo("264329");
        assertThat(record.contentTypeId()).isEqualTo("76");
        assertThat(record.title()).isEqualTo("Gyeongbokgung Palace (경복궁)");
        assertThat(record.address()).isEqualTo("161, Sajik-ro");
        assertThat(record.latitude().toPlainString()).isEqualTo("37.579617");
        assertThat(record.longitude().toPlainString()).isEqualTo("126.977041");
        assertThat(record.classification()).isEqualTo("HS");
        assertThat(record.regionCode()).isEqualTo("11");
        assertThat(record.sigunguCode()).isEqualTo("110");
        assertThat(record.payloadHash()).matches("[0-9a-f]{64}");
        assertThat(result.verdict().outcome()).isEqualTo(Outcome.OK);
        assertThat(result.responseCount()).isOne();
    }

    @Test
    @DisplayName("a well-formed answer with exactly zero items says the record is gone, and is not drift")
    void zeroItemsIsGone() {
        Result emptyString = validate("""
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                 "body":{"items":"","numOfRows":10,"pageNo":1,"totalCount":0}}}
                """);
        Result emptyArray = validate("""
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                 "body":{"items":{"item":[]},"numOfRows":10,"pageNo":1,"totalCount":0}}}
                """);

        assertThat(emptyString).isInstanceOf(Gone.class);
        assertThat(emptyArray).isInstanceOf(Gone.class);
        assertThat(emptyString.verdict().outcome()).isEqualTo(Outcome.OK);
        assertThat(emptyString.responseCount()).isZero();
    }

    @Test
    @DisplayName("an answer that does not say how many items it has is drift, not a deletion")
    void missingCountIsNotGone() {
        Result noCount = validate("""
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":""}}}
                """);
        Result countButItem = validate("""
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":
                  {"contentid":"264329","contenttypeid":"76","title":"Gyeongbokgung Palace"}},"totalCount":0}}}
                """);

        assertThat(noCount).isInstanceOf(Rejected.class);
        assertThat(noCount.verdict().outcome()).isEqualTo(Outcome.SCHEMA_DRIFT);
        assertThat(countButItem).isInstanceOf(Rejected.class);
        assertThat(countButItem.verdict().outcome()).isEqualTo(Outcome.SCHEMA_DRIFT);
    }

    @Test
    @DisplayName("provider errors, other identifiers, several items and bad coordinates are rejected")
    void rejectsWhatCannotBeRead() {
        Result providerError = validate("""
                {"response":{"header":{"resultCode":"22"},"body":{}}}
                """);
        Result gatewayError = validate("""
                {"responseTime":"2026-09-23T00:00:00Z","resultCode":"20","resultMsg":"redacted"}
                """);
        Result otherId = validate(item("999999", "76", "Other", null, "37.5", "126.9"));
        Result otherType = validate(item("264329", "12", "Gyeongbokgung Palace", null, "37.5", "126.9"));
        Result twoItems = validate("""
                {"response":{"header":{"resultCode":"0000"},"body":{"totalCount":2,"items":{"item":[
                  {"contentid":"264329","contenttypeid":"76","title":"A"},
                  {"contentid":"264329","contenttypeid":"76","title":"B"}]}}}}
                """);
        Result outOfRange = validate(item("264329", "76", "Gyeongbokgung Palace", null, "137.5", "126.9"));
        Result notJson = validate("not json");

        assertThat(providerError.verdict().outcome()).isEqualTo(Outcome.PROVIDER_ERROR);
        assertThat(gatewayError.verdict().outcome()).isEqualTo(Outcome.PROVIDER_ERROR);
        assertThat(otherId.verdict().outcome()).isEqualTo(Outcome.SCHEMA_DRIFT);
        assertThat(otherType.verdict().outcome()).isEqualTo(Outcome.SCHEMA_DRIFT);
        assertThat(twoItems.verdict().outcome()).isEqualTo(Outcome.SCHEMA_DRIFT);
        assertThat(outOfRange.verdict().outcome()).isEqualTo(Outcome.RANGE);
        assertThat(notJson.verdict().outcome()).isEqualTo(Outcome.SCHEMA_DRIFT);
        assertThat(java.util.List.of(providerError, gatewayError, otherId, otherType, twoItems, outOfRange, notJson))
                .allSatisfy(rejected -> assertThat(rejected).isInstanceOf(Rejected.class));
    }

    @Test
    @DisplayName("a title longer than a localized name can hold leaves the record without a name, uncut and not drift")
    void titleTheLocalizationCannotHoldIsNotServed() {
        Result result = validate(item("264329", "76", "x".repeat(201), null, "37.5", "126.9"));

        assertThat(result).isInstanceOf(Found.class);
        assertThat(((Found) result).record().title()).isNull();
        assertThat(result.verdict().outcome()).isEqualTo(Outcome.OK);
    }

    @Test
    @DisplayName("a key the link rule reads that is absent from the item is drift, not an empty value")
    void absentRequiredKeyIsDrift() {
        for (String key : java.util.List.of("title", "addr1", "mapx", "mapy", "lclsSystm1", "lDongRegnCd",
                "lDongSignguCd")) {
            String withoutKey = item("264329", "76", "Gyeongbokgung Palace", "161, Sajik-ro", "37.579617",
                    "126.977041").replaceFirst("\"" + key + "\":(\"[^\"]*\")?,?", "");
            assertThat(withoutKey).as("fixture drops %s", key).doesNotContain("\"" + key + "\"");

            Result result = validate(withoutKey);

            assertThat(result.verdict().outcome()).as(key).isEqualTo(Outcome.SCHEMA_DRIFT);
        }
    }

    @Test
    @DisplayName("a missing coordinate or code is kept as missing, so the link rule can refuse it")
    void missingFactsStayMissing() {
        Result result = validate("""
                {"response":{"header":{"resultCode":"0000"},"body":{"totalCount":1,"items":{"item":
                  {"contentid":"264329","contenttypeid":"76","title":"","addr1":"",
                   "mapx":"","mapy":"","lclsSystm1":"","lDongRegnCd":"","lDongSignguCd":""}}}}}
                """);

        assertThat(result).isInstanceOf(Found.class);
        KtoEngRecord record = ((Found) result).record();
        assertThat(record.title()).isNull();
        assertThat(record.address()).isNull();
        assertThat(record.latitude()).isNull();
        assertThat(record.longitude()).isNull();
        assertThat(record.classification()).isNull();
        assertThat(record.regionCode()).isNull();
        assertThat(record.sigunguCode()).isNull();
    }

    private Result validate(String body) {
        return validator.validate(body.getBytes(StandardCharsets.UTF_8), request);
    }

    private static String item(String contentId, String contentTypeId, String title, String address,
            String latitude, String longitude) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "contentid":"%s","contenttypeid":"%s","title":"%s","addr1":%s,"addr2":"",
                  "mapy":"%s","mapx":"%s","lclsSystm1":"HS","lclsSystm2":"HS01","lclsSystm3":"HS010100",
                  "lDongRegnCd":"11","lDongSignguCd":"110","cat1":"","areacode":"","sigungucode":"",
                  "overview":"A long English overview with opening hours 09:00-18:00 that must not be kept.",
                  "modifiedtime":"20250101000000","createdtime":"20200101000000"}]},
                  "numOfRows":1,"pageNo":1,"totalCount":1}}}
                """.formatted(contentId, contentTypeId, title, address == null ? "\"\"" : "\"" + address + "\"",
                latitude, longitude);
    }
}
