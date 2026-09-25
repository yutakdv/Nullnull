package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * queryLiveAreas over real HTTP, with the default properties - which is the submission build's
 * configuration, Live capability OFF.
 *
 * <p><strong>The flag is OFF here on purpose, and it is no longer the case that it cannot be
 * turned on.</strong> This paragraph used to say {@code DemoCapabilityQuery} refuses to start with
 * FEATURE_LIVE_DATA ON while {@code live} sits in its {@code WITHOUT_A_SOURCE} list. BA-090 ended
 * that - SEOUL_CITYDATA is promoted in V046, a collector stores a reading per area, and {@code live}
 * left the list, which {@code DemoCapabilityQuery} now holds only {@code replay} in. What this file
 * measures is therefore a choice rather than a limit: the default configuration, which is the
 * submission build's, where the tab is off. {@code LiveAreaReadIT} and {@code LivePlaceApiIT} drive
 * the enabled path.
 *
 * <p>What this file can do over HTTP is the half that matters most for privacy: the request shape is
 * judged before the capability is, so a viewport is refused here exactly as it would be on a server
 * with the feature on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-091 queryLiveAreas over HTTP")
class LiveAreaApiIT {

    /**
     * Seven decimals, which is a device fix rather than a map view - and the exact digits are what
     * the test below searches for, so they are written once here.
     */
    private static final String PRECISE_WEST = "126.9770411";
    private static final String PRECISE_SOUTH = "37.5796171";
    private static final String PRECISE_EAST = "126.9880411";
    private static final String PRECISE_NORTH = "37.5896171";

    /**
     * A POST is origin-checked BEFORE any session or capability work (#240 A-1), so a request
     * without this header is refused 403 CSRF_INVALID and never reaches what these cases are about.
     * The first run of this file did exactly that and read the 403 as the capability's.
     */
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;

    @Test
    @DisplayName("BA-091-T10 viewport 거절 응답이 좌표를 담지 않는다")
    void aRefusedViewportIsNotEchoedBackToTheCaller() throws Exception {
        String cookie = sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie;

        MvcResult refused = mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session", cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\",\"viewport\":{\"west\":" + PRECISE_WEST
                                + ",\"south\":" + PRECISE_SOUTH + ",\"east\":" + PRECISE_EAST
                                + ",\"north\":" + PRECISE_NORTH + "}}"))
                // 422, because ProblemCode.VALIDATION_FAILED is UNPROCESSABLE_CONTENT: the body
                // parsed and its fields are the problem. The code is asserted below with the rest of
                // the text, so this case names both the layer that refused and what it refused.
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        String body = refused.getResponse().getContentAsString();

        // It refused, and it said WHY. Without this half, an empty 500 would satisfy the absence
        // assertion below while telling the caller nothing - "contains no coordinates" is cheap to
        // pass by returning nothing at all.
        assertThat(body).contains("VALIDATION_FAILED").contains("VIEWPORT_TOO_PRECISE");
        // And the coordinates are not in it - not whole, and not truncated to something a reader
        // could still place. A precise position the caller must not send us is also one we must not
        // write back out: the response is the part of this exchange that gets screenshot, pasted
        // into a bug report and stored somewhere nobody is thinking about invariant 10.
        assertThat(body).doesNotContain(PRECISE_WEST, PRECISE_SOUTH, PRECISE_EAST, PRECISE_NORTH);
        assertThat(body).doesNotContain("126.977", "37.579", "126.988", "37.589");
    }

    /**
     * The log half of the refusal. CoarseViewportTest holds the exception's own text; this holds what
     * a refused request actually writes, at the configured levels, to every appender the root has -
     * which is where a later "log the rejected box for debugging" line, or a handler that logs the
     * cause it was handed, would show up. The viewport travels in the body, so the access log's
     * query switch is not the channel here; what is, is any line written while the request ran.
     * Logback is one writer and not the only one: a System.out or System.err print never reaches an
     * appender, so the process's own standard streams are searched too.
     */
    @Test
    @DisplayName("BA-091-T4 viewport 거절이 좌표를 로그에 남기지 않는다")
    void aRefusedViewportLeavesItsCoordinatesInNoLogLine(CapturedOutput console) throws Exception {
        String cookie = sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie;
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            mvc.perform(post("/api/v1/live/areas")
                            .cookie(new Cookie("__Host-nullnull_session", cookie))
                            .header("Origin", ORIGIN)
                            .contentType("application/json")
                            .content("{\"mode\":\"AUTO\",\"viewport\":{\"west\":" + PRECISE_WEST
                                    + ",\"south\":" + PRECISE_SOUTH + ",\"east\":" + PRECISE_EAST
                                    + ",\"north\":" + PRECISE_NORTH + "}}"))
                    .andExpect(status().isUnprocessableEntity());
        } finally {
            root.detachAppender(logs);
        }

        // The capture saw this request's own line, or the sweep below proves nothing: an appender that
        // caught nothing satisfies "no line holds a coordinate" as well as a clean server does.
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("method=POST route=/live/areas status=422"));
        assertThat(logs.list).allSatisfy(event -> {
            String written = event.getFormattedMessage() + " " + event.getThrowableProxy()
                    + " " + event.getMDCPropertyMap();
            assertThat(written).doesNotContain(PRECISE_WEST, PRECISE_SOUTH, PRECISE_EAST, PRECISE_NORTH);
            assertThat(written).doesNotContain("126.977", "37.579", "126.988", "37.589");
        });
        // The streams: the console appender writes the same access line there, which is what shows this
        // capture saw the request too.
        assertThat(console.getAll()).contains("method=POST route=/live/areas status=422");
        assertThat(console.getAll()).doesNotContain(PRECISE_WEST, PRECISE_SOUTH, PRECISE_EAST, PRECISE_NORTH);
        assertThat(console.getAll()).doesNotContain("126.977", "37.579", "126.988", "37.589");
    }

    @Test
    @DisplayName("BA-091 coarse 한 viewport 는 통과하고 그다음 capability 에서 막힌다")
    void aCoarseViewportPassesTheShapeCheckAndMeetsTheCapabilityGate() throws Exception {
        String cookie = sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie;

        // Three decimals and both spans over 0.01 degrees: the shape the contract asks for. The 403
        // rather than a 400 is what makes the case above non-vacuous - a route that answered 400 to
        // every body would satisfy "the refusal carries no coordinates" without refusing anything
        // for the reason it claims.
        mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session", cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\",\"regionCode\":\"KR-11\",\"viewport\":"
                                + "{\"west\":126.977,\"south\":37.579,\"east\":127.007,\"north\":37.609}}"))
                .andExpect(status().isForbidden())
                // The code as well as the status, because 403 has more than one producer here: the
                // same-origin check answers 403 CSRF_INVALID before any of this, and a case that
                // asserted only the status would pass while measuring that instead.
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("BA-091 계약에 없는 mode 와 반쪽 viewport 는 거절된다")
    void anUnknownModeAndAHalfViewportAreRefused() throws Exception {
        String cookie = sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie;

        mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session", cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"EVERYTHING\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // A box missing one edge must not fall through as "no viewport": that would serve an
        // unbounded query to a caller who believed they had asked for a box.
        mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session", cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\",\"viewport\":{\"west\":126.977,\"south\":37.579,"
                                + "\"east\":127.007}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
