package io.nullnull.contract;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
class SessionContractTest {
    @Autowired MockMvc mvc;
    @Autowired java.time.Clock clock;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;
    /**
     * A session-scoped response always carries {@code Cache-Control: private, no-store}, set by
     * {@code SessionHttpConfiguration}'s interceptor for every SESSION operation. A header the server
     * always sends but the contract never names is invisible to a generated client, so the two must agree.
     */
    @Test @DisplayName("every session-scoped operation declares the Cache-Control it always sends")
    void cacheControlParity() {
        var api = OpenApiDocument.load();
        var missing = new java.util.TreeSet<String>();
        mapping.getHandlerMethods().forEach((route, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("io.nullnull.")
                    || method.getBeanType().getPackageName().startsWith("io.nullnull.shared.problem")) { return; }
            var op = method.getMethodAnnotation(NullnullOperation.class);
            if (op == null || !List.of(op.security()).contains(Security.SESSION)) { return; }
            boolean declaredSomewhere = false;
            // 204 too: deleteTrip answers with no body and still sets the header, and a check
            // that cannot see its status would report it as missing forever.
            for (String code : List.of("200", "201", "202", "204")) {
                try {
                    if (api.responseHeaders(op.id(), code).contains("Cache-Control")) { declaredSomewhere = true; }
                } catch (IllegalArgumentException unknown) {
                    return;
                }
            }
            if (!declaredSomewhere) { missing.add(op.id()); }
        });
        assertThat(missing)
                .as("these operations always send Cache-Control: private, no-store but never declare it")
                .isEmpty();
    }

    /**
     * A merge-patch operation refuses any other media type with 415, because absent-means-keep and
     * null-means-clear are semantics of {@code application/merge-patch+json} rather than of the schema.
     * A client generated from a spec that omits 415 cannot type that refusal.
     */
    @Test @DisplayName("every merge-patch operation declares the 415 it answers a wrong media type with")
    void mergePatchDeclaresUnsupportedMediaType() {
        var api = OpenApiDocument.load();
        var missing = new java.util.TreeSet<String>();
        for (String id : api.operationIds()) {
            if (!api.requestMediaTypes(id).contains("application/merge-patch+json")) { continue; }
            if (!api.responseCodes(id).contains("415")) { missing.add(id); }
        }
        assertThat(missing)
                .as("merge-patch operations answer a wrong media type with 415 but do not declare it")
                .isEmpty();
    }

    @Test @DisplayName("BA-010-T1 every implemented handler declares matching OpenAPI operation security")
    void securityParity() {
        var api=OpenApiDocument.load();
        Set<String> ids=new HashSet<>();
        mapping.getHandlerMethods().forEach((route,method) -> {
            if (!method.getBeanType().getPackageName().startsWith("io.nullnull.")
                    || method.getBeanType().getPackageName().startsWith("io.nullnull.shared.problem")) { return; }
            var op=method.getMethodAnnotation(NullnullOperation.class);
            assertThat(op).as("Public controller must declare operation policy: %s",method.getMethod().getName()).isNotNull();
            assertThat(ids.add(op.id())).isTrue();
            Set<String> schemes=new HashSet<>();
            for(var security:op.security()) {
                schemes.add(switch (security) {
                    case SESSION -> "sessionCookie";
                    case CSRF -> "csrfToken";
                    case DELETION_STATUS_TOKEN -> "deletionStatusToken";
                });
            }
            assertThat(api.securityRequirements(op.id())).isEqualTo(schemes.isEmpty() ? List.of() : List.of(schemes));
            var declared=api.routeOf(op.id());
            assertThat(route.getPatternValues()).containsExactly(declared.path().substring("/api/v1".length()));
            assertThat(route.getMethodsCondition().getMethods()).extracting(Enum::name).containsExactly(declared.method());
        });
        assertThat(ids).isEqualTo(ImplementedOperationsRegistry.IMPLEMENTED);
        assertThat(api.declaredSecuritySchemes("searchPlaces")).containsExactly("sessionCookie");
    }
    @Test @DisplayName("BA-010-T3 bootstrap and CSRF responses satisfy schemas and secure cookie contract")
    void responses() throws Exception {
        var check=new JsonSchemaCheck(OpenApiDocument.load());
        var created=mvc.perform(post("/api/v1/demo/sessions").header("Origin","http://localhost:5173"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(check.validate("SessionBootstrap",created.getContentAsString())).isEmpty();
        var payload=tools.jackson.databind.json.JsonMapper.builder().build().readTree(created.getContentAsString());
        var tokenExpiry=java.time.Instant.parse(payload.get("expiresAt").asText());
        assertThat(tokenExpiry).isAfter(clock.instant()).isBefore(clock.instant().plus(java.time.Duration.ofHours(2)).plusSeconds(1));
        String cookie=created.getHeader("Set-Cookie");
        // Never print the cookie-bearing value in assertion diagnostics.
        assertThat(cookie.startsWith("__Host-nullnull_session=")).isTrue();
        for(String flag:List.of("; Secure","; HttpOnly","; SameSite=Lax","; Path=/")) { assertThat(cookie.contains(flag)).isTrue(); }
        assertThat(cookie.contains("Domain=")).isFalse();
        var bearer=new Cookie("__Host-nullnull_session",cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';')));
        var resumed=mvc.perform(post("/api/v1/demo/sessions").cookie(bearer).header("Origin","http://localhost:5173"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(resumed.getHeader("Set-Cookie")).isNull();
        assertThat(check.validate("SessionBootstrap",resumed.getContentAsString())).isEmpty();
        var csrf=mvc.perform(post("/api/v1/session/csrf").cookie(bearer).header("Origin","http://localhost:5173"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(check.validate("CsrfTokenResponse",csrf.getContentAsString())).isEmpty();
        assertThat(csrf.getHeader("Cache-Control")).isEqualTo("private, no-store");
        var csrfPayload=tools.jackson.databind.json.JsonMapper.builder().build().readTree(csrf.getContentAsString());
        var deleted=mvc.perform(delete("/api/v1/session").cookie(bearer)
                        .header("Origin","http://localhost:5173")
                        .header("X-CSRF-Token",csrfPayload.get("csrfToken").asText())
                        .header("Idempotency-Key","contract-delete-"+UUID.randomUUID()))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(check.validate("DeletionReceipt",deleted.getContentAsString())).isEmpty();
        var receipt=tools.jackson.databind.json.JsonMapper.builder().build().readTree(deleted.getContentAsString());
        var deletionStatus=mvc.perform(get(receipt.get("statusUrl").asText())
                        .header("X-Deletion-Status-Token",receipt.get("statusToken").asText()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(check.validate("DeletionRequestStatus",deletionStatus.getContentAsString())).isEmpty();
    }
}
