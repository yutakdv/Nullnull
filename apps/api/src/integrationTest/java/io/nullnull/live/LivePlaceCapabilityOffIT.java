package io.nullnull.live;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two new Live routes with the flag at its default, which is the submission build's setting.
 *
 * <p>This file exists for the reason {@code OptimizationCapabilityOffIT} does: every other Live test
 * turns the flag on, so without it nothing looks at the configuration the contest profile actually
 * ships. {@code CapabilityOffCoverageIT} drives one operation per capability - its register is about
 * capabilities, not routes - and names this class for the other two.
 *
 * <p><strong>Both ids are ones that do not exist, and that is the point.</strong> A refusal that
 * needed a real area or a real place would be a refusal the caller could use to find out which ones
 * are real. The capability gate runs before anything reads storage, so a disabled server answers the
 * same 403 either way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-091 Live 장소 route 둘은 flag 가 꺼진 기본 설정에서 거절한다")
class LivePlaceCapabilityOffIT {

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;

    @Test
    @DisplayName("BA-091 listLiveAreaPlaces 와 getLivePlace 는 capability 가 꺼져 있으면 FORBIDDEN 이다")
    void bothRoutesRefuseWhileTheCapabilityIsOff() throws Exception {
        Cookie cookie = new Cookie("__Host-nullnull_session",
                sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie);

        // The code as well as the status. 403 has more than one producer on this server - the
        // same-origin check answers 403 CSRF_INVALID before any of this on an unsafe method - and a
        // GET that asserted only the status would pass while measuring nothing about the capability.
        mvc.perform(get("/api/v1/live/areas/{id}/places", UUID.randomUUID()).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/live/places/{id}", UUID.randomUUID()).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
