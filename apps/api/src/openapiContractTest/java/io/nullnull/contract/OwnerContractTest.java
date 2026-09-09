package io.nullnull.contract;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class,ServletPathMockMvcConfiguration.class})
class OwnerContractTest {
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Test @DisplayName("BA-011-T1 OwnerProfile and validation Problem match public schemas")
    void schemas() throws Exception {
        var check=new JsonSchemaCheck(OpenApiDocument.load());
        var owner=sessions.bootstrap(null,null,null);
        var cookie=new Cookie("__Host-nullnull_session",owner.cookie);
        var read=mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(check.validate("OwnerProfile",read.getContentAsString())).isEmpty();
        var updated=mvc.perform(patch("/api/v1/me").cookie(cookie).header("Origin","http://localhost:5173")
                .header("X-CSRF-Token",owner.csrf.token).contentType("application/merge-patch+json")
                .content("{\"locale\":\"en-US\",\"onboardingCompleted\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(check.validate("OwnerProfile",updated.getContentAsString())).isEmpty();
        var invalid=mvc.perform(patch("/api/v1/me").cookie(cookie).header("Origin","http://localhost:5173")
                .header("X-CSRF-Token",owner.csrf.token).contentType("application/merge-patch+json")
                .content("{\"locale\":\"ja-JP\"}"))
                .andExpect(status().is(422)).andReturn().getResponse();
        assertThat(check.validate("Problem",invalid.getContentAsString())).isEmpty();
    }
    @Test @DisplayName("BA-011-T3 ordinary JSON cannot accidentally replace the merge-patch contract")
    void mediaType() throws Exception {
        var owner=sessions.bootstrap(null,null,null);
        mvc.perform(patch("/api/v1/me").cookie(new Cookie("__Host-nullnull_session",owner.cookie))
                .header("Origin","http://localhost:5173").header("X-CSRF-Token",owner.csrf.token)
                .contentType("application/json").content("{\"onboardingCompleted\":true}"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
