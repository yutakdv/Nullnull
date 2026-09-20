package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * queryLiveAreas over real HTTP, with the default properties - which is the submission build's
 * configuration, Live capability OFF.
 *
 * <p><strong>The flag cannot be turned on here, and that is a guard rather than a gap.</strong>
 * {@code DemoCapabilityQuery} refuses to start with FEATURE_LIVE_DATA ON while {@code live} is in
 * its {@code WITHOUT_A_SOURCE} list, and this slice does NOT take it out: a route that answers and a
 * source that has something to answer WITH are different things, and nothing stores a Seoul reading
 * yet. The enabled path is exercised at the service level instead ({@code LiveAreaQueryServiceTest}),
 * and the flag leaves that list when the collector's writer lands.
 *
 * <p>What this file can do over HTTP is the half that matters most for privacy: the request shape is
 * judged before the capability is, so a viewport is refused here exactly as it would be on a server
 * with the feature on.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
