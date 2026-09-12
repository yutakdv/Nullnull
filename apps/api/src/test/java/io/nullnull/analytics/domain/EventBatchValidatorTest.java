package io.nullnull.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-033-T1 against the canonical schema itself.
 *
 * <p>The second test measures what the validator's own messages contain, because the service's
 * refusal text depends on it. The expectation going in was that they quote the offending value -
 * that is what a useful validator message does, and for an analytics payload the value is the one
 * thing that must not leave the server. They do not: an enum violation lists the ALLOWED values and
 * says the sent one was not among them. What they do carry is the JSON path, and they are rendered
 * in the JVM's default locale, which is neither English nor stable. So the reason the service
 * returns a fixed sentence is not leakage - it is that the library's text is not part of any
 * contract and would make {@code detail} change language with the server's locale.
 */
class EventBatchValidatorTest {

    private static final EventBatchValidator VALIDATOR = new EventBatchValidator(canonicalSchema());
    private static final String TRIP_ID = "018f3f8e-9b67-7a21-8d31-31d315b938f3";
    private static final String PASTED_TEXT = "폐렴환자경복궁9시";

    private static String canonicalSchema() {
        // The same document the runtime loads; processResources packages it from docs/contracts.
        Path packaged = Path.of("build/resources/main/contracts/events.schema.json");
        Path canon = Files.exists(packaged) ? packaged
                : Path.of("../../docs/contracts/events.schema.json");
        try {
            return Files.readString(canon, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the canonical event schema", missing);
        }
    }

    private static String batch(String route, String extraProperty) {
        return "{\"schemaVersion\":\"1.1.0\",\"events\":[{"
                + "\"eventId\":\"018f3f8e-9b67-7a21-8d31-31d315b938f1\",\"name\":\"trip_created\","
                + "\"occurredAt\":\"2026-09-04T09:00:00+09:00\",\"context\":{\"locale\":\"ko-KR\","
                + "\"timezone\":\"Asia/Seoul\",\"route\":\"" + route + "\"},"
                + "\"properties\":{\"tripId\":\"" + TRIP_ID + "\",\"method\":\"MANUAL\","
                + "\"planningLevel\":\"MUST_VISIT_ONLY\",\"dayCount\":3,\"itemCount\":2"
                + extraProperty + "}}]}";
    }

    @Test
    @DisplayName("BA-033-T1 the canonical schema refuses what the privacy rules forbid")
    void refusesConcreteRoutesFreeTextAndOutOfRangeCounts() {
        assertThat(VALIDATOR.validate(batch("/start", ""))).isEmpty();

        assertThat(VALIDATOR.validate(batch("/trip/" + TRIP_ID, ""))).isNotEmpty();
        assertThat(VALIDATOR.validate(batch("/search?q=%EC%84%9C%EC%9A%B8", ""))).isNotEmpty();
        assertThat(VALIDATOR.validate(batch("/start", ",\"note\":\"" + PASTED_TEXT + "\""))).isNotEmpty();
        assertThat(VALIDATOR.validate(batch("/start", ",\"lat\":37.5796,\"lng\":126.977"))).isNotEmpty();
        assertThat(VALIDATOR.validate(
                batch("/start", "").replace("\"dayCount\":3", "\"dayCount\":31"))).isNotEmpty();
    }

    @Test
    @DisplayName("BA-033-T1 validator messages carry the path and the JVM locale, not the value")
    void theMessagesAreNotSafeToPublishAsAContractDetail() {
        String concreteRoute = "/trip/" + TRIP_ID;
        String messages = render(VALIDATOR.validate(batch(concreteRoute, "")));

        // Measured, not assumed: an enum violation lists the ALLOWED values and does not repeat the
        // one that was sent. Good - the rejected route carries a trip id.
        assertThat(messages).doesNotContain(TRIP_ID);
        String withText = render(VALIDATOR.validate(
                batch("/start", ",\"note\":\"" + PASTED_TEXT + "\"")));
        assertThat(withText).doesNotContain(PASTED_TEXT);

        // What they DO carry is the JSON path into the payload...
        assertThat(messages).contains("/events/0/context/route");
        // ...and the property name a client chose, which is still client-supplied text.
        assertThat(withText).contains("note");
        // ...and they are rendered in the JVM's default locale, so the same failure reads
        // differently on two servers. That is why AnalyticsEventService answers with one fixed
        // English sentence instead of forwarding this; AnalyticsIngestIT pins that sentence.
        assertThat(messages).isNotBlank();
    }

    private static String render(List<Error> errors) {
        StringBuilder text = new StringBuilder();
        for (Error error : errors) {
            text.append(error).append(System.lineSeparator());
        }
        return text.toString();
    }
}
