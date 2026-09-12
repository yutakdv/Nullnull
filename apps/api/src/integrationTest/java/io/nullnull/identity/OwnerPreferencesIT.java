package io.nullnull.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class,ServletPathMockMvcConfiguration.class})
@Tag("identity-safety")
class OwnerPreferencesIT {
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private SessionService.Bootstrap owner() { return sessions.bootstrap(null,null,null); }
    private Cookie cookie(SessionService.Bootstrap owner) { return new Cookie("__Host-nullnull_session",owner.cookie); }
    private ResultActions change(SessionService.Bootstrap owner,String body) throws Exception {
        return mvc.perform(patch("/api/v1/me").cookie(cookie(owner)).header("Origin","http://localhost:5173")
                .header("X-CSRF-Token",owner.csrf.token).contentType("application/merge-patch+json").content(body));
    }
    @Test @DisplayName("BA-011-T1 Korean English timezone preferences survive authenticated read")
    void localeRoundTrip() throws Exception {
        var owner=owner();
        for(String locale:List.of("en-US","ko-KR")) {
            change(owner,"{\"locale\":\""+locale+"\",\"timezone\":\"Europe/Paris\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.locale").value(locale));
            mvc.perform(get("/api/v1/me").cookie(cookie(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.locale").value(locale))
                    .andExpect(jsonPath("$.timezone").value("Europe/Paris"))
                    .andExpect(header().string("Cache-Control","private, no-store"));
        }
        for(String locale:List.of("ja-JP","zh-CN","fr-FR")) {
            change(owner,"{\"locale\":\""+locale+"\"}").andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("locale"))
                    .andExpect(jsonPath("$.fieldErrors[0].code").value("UNSUPPORTED_LOCALE"));
        }
        change(owner,"{\"timezone\":\"not-a-zone\"}").andExpect(status().is(422))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_TIMEZONE"));
    }
    @Test @DisplayName("BA-011-T2 current owner cannot be selected through input and trip lookup fails closed")
    void isolationAndUnavailableTrip() throws Exception {
        var a=owner();var b=owner();
        change(a,"{\"locale\":\"en-US\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").cookie(cookie(b)).param("ownerId",a.owner.id().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(b.owner.id().toString()))
                .andExpect(jsonPath("$.locale").value("ko-KR"));
        for(int i=0;i<3;i++) {
            change(a,"{\"activeTripId\":\""+UUID.randomUUID()+"\"}")
                    .andExpect(status().is(422)).andExpect(jsonPath("$.fieldErrors[0].code").value("TRIP_NOT_FOUND"));
        }
        mvc.perform(patch("/api/v1/me").cookie(cookie(a)).header("Origin","http://localhost:5173")
                .header("X-CSRF-Token",b.csrf.token).contentType("application/merge-patch+json")
                .content("{\"locale\":\"ko-KR\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT locale FROM owners WHERE id = ?",String.class,a.owner.id())).isEqualTo("en-US");
    }
    @Test @DisplayName("BA-011-T3 omitted fields preserve state null clears active trip and repeated onboarding is inert")
    void mergePatchAndRepeat() throws Exception {
        var a=owner();
        // A real trip: V013 added the foreign key and the same-owner trigger V002 deferred, so a
        // made-up id no longer reaches the column at all.
        UUID trip=io.nullnull.testsupport.TripRows.insert(jdbc,a.owner.id(),java.time.Instant.now());
        jdbc.update("UPDATE owners SET active_trip_id = ? WHERE id = ?",trip,a.owner.id());
        change(a,"{\"onboardingCompleted\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTripId").value(trip.toString()));
        String version=jdbc.queryForObject("SELECT xmin::text FROM owners WHERE id = ?",String.class,a.owner.id());
        change(a,"{\"onboardingCompleted\":true}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM owners WHERE id = ?",String.class,a.owner.id())).isEqualTo(version);
        change(a,"{\"activeTripId\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTripId").isEmpty()).andExpect(jsonPath("$.onboardingCompleted").value(true));
        assertThat(jdbc.queryForObject("SELECT active_trip_id FROM owners WHERE id = ?",UUID.class,a.owner.id())).isNull();
        change(a,"{\"onboardingCompleted\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.onboardingCompleted").value(false));
    }
    @Test @DisplayName("BA-011-T3 malformed merge patch is rejected without a partial write")
    void malformedAndAtomic() throws Exception {
        var a=owner();
        for(String body:List.of("{}","[]","[\"value\"]","null","{\"ownerId\":\"forged\"}","{\"locale\":null}",
                "{\"timezone\":null}","{\"onboardingCompleted\":null}","{\"onboardingCompleted\":\"true\"}",
                "{\"locale\":12}","{\"activeTripId\":12}","{\"activeTripId\":\"1-1-1-1-1\"}")) {
            change(a,body).andExpect(status().isBadRequest());
        }
        change(a,"{\"locale\":\"en-US\",\"activeTripId\":\""+UUID.randomUUID()+"\"}").andExpect(status().is(422));
        assertThat(jdbc.queryForObject("SELECT locale FROM owners WHERE id = ?",String.class,a.owner.id())).isEqualTo("ko-KR");
        mvc.perform(patch("/api/v1/me").cookie(cookie(a)).header("Origin","http://localhost:5173")
                .header("X-CSRF-Token",a.csrf.token).contentType("application/json").content("{\"locale\":\"en-US\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }
    @Test @DisplayName("BA-011-T1 revoked and expired cookies cannot read or change preferences")
    void expiredCookie() throws Exception {
        var a=owner();
        jdbc.update("UPDATE demo_sessions SET revoked_at = created_at WHERE owner_id = ?",a.owner.id());
        mvc.perform(get("/api/v1/me").cookie(cookie(a))).andExpect(status().isUnauthorized());
        change(a,"{\"locale\":\"en-US\"}").andExpect(status().isUnauthorized());
    }
}
