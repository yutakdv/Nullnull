package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A published request property that no server type mentions is a promise nobody keeps.
 *
 * <p>Three of them were found by hand while implementing one operation, all in
 * {@code ReplaceTripItemRequest}, and all with the same cause: the field reached the contract before
 * any code read it. A fourth in another schema would not be found the same way, so the comparison is
 * mechanical from here: every property of an implemented operation's request schema must appear on
 * the body type that operation binds, or be registered below as deliberately refused.
 *
 * <p>What this can and cannot see, stated plainly so it is not trusted for more than it does. It
 * sees a property the body type never mentions - the shape {@code releaseConstraints} had, published
 * on three operations with no Java field anywhere. It does NOT see a property that is bound and then
 * ignored: {@code preserveDateTime} and {@code relationId} are both components of
 * {@code ReplaceTripItemBody}, so this check passes them and the registry is what records why they
 * are refused. Reading is not something a signature can prove.
 *
 * <p>"Find the components whose accessor is never called" was considered for that second half and
 * does not work, for a reason that needs no experiment: refusing a field requires reading it.
 * {@code TripController} calls {@code body.relationId()} and {@code body.preserveDateTime()} to hand
 * them to the command that rejects them, so a never-called rule reports nothing for exactly the two
 * fields it would exist to catch. It could only see a component bound and then passed nowhere at
 * all, which is a different defect and one no current operation has.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("published request properties have a reader or a registered refusal")
class RequestPropertyReaderTest {

    /**
     * Properties the server binds and deliberately refuses, with the issue that decides their fate.
     *
     * <p>Registered rather than silently tolerated: without this list the next reader sees a field in
     * the contract, finds it on the body record, and concludes it works. Each entry is a field the
     * server answers 422 for, and the integration tests that prove the refusal live beside the
     * operation ({@code TripItemReplaceIT}).
     */
    private static final Map<String, String> REFUSED = Map.of(
            "ReplaceTripItemRequest.preserveDateTime",
            "#203 - only true is supported; what false should do was never decided",
            "ReplaceTripItemRequest.relationId",
            "#204 - no operation issues a relation id, so no caller can hold a valid one");

    /**
     * Operations whose body is not a record, so there are no components to compare against.
     *
     * <p>updateTrip and updateTripItem bind a raw map because merge-patch has to tell "absent"
     * from "null", which a record cannot express - a null component means both. UpdateTripBodies does the reading and
     * rejects unknown fields itself, which is the part this check would otherwise provide.
     *
     * <p>ingestEventBatch binds the raw text on purpose: docs/contracts/events.schema.json is the
     * canonical definition of a batch and AGENTS.md forbids reimplementing it in Java, so binding it
     * to a record would be exactly the second definition that rule exists to prevent. Both of these
     * were found by this check on its first run rather than assumed, and both turned out to be
     * deliberate - which is why they are named here with their reason instead of being skipped as a
     * class.
     */
    private static final Set<String> NOT_RECORD_BOUND = Set.of("updateTrip", "updateTripItem", "ingestEventBatch");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("every request property is bound by the operation's body type or registered as refused")
    void everyPublishedRequestPropertyHasAReaderOrARegisteredRefusal() {
        OpenApiDocument api = OpenApiDocument.load();
        Map<String, HandlerMethod> handlers = handlersByOperationId();
        TreeSet<String> unread = new TreeSet<>();
        for (String operationId : ImplementedOperationsRegistry.IMPLEMENTED) {
            if (NOT_RECORD_BOUND.contains(operationId)) {
                continue;
            }
            String schema = api.requestBodySchema(operationId).orElse(null);
            if (schema == null) {
                continue;
            }
            HandlerMethod handler = handlers.get(operationId);
            assertThat(handler).as("no handler serves the implemented operation %s", operationId)
                    .isNotNull();
            Set<String> bound = boundComponents(handler);
            for (String property : api.propertyNames(schema)) {
                if (!bound.contains(property) && !REFUSED.containsKey(schema + "." + property)) {
                    unread.add(schema + "." + property + " (" + operationId + ")");
                }
            }
        }
        assertThat(unread)
                .as("these request properties are published but no body type binds them; give them a"
                        + " reader in the same change, or register the refusal in REFUSED")
                .isEmpty();
    }

    @Test
    @DisplayName("a registered refusal names a property that still exists")
    void theRefusalRegistryCannotOutliveTheFieldItExcuses() {
        // The same rule docs/api/oasdiff-ignore.txt follows: an exemption that stops matching a real
        // finding is deleted, not left behind. Without this, removing preserveDateTime one day would
        // leave an entry excusing a field nobody can send.
        OpenApiDocument api = OpenApiDocument.load();
        TreeSet<String> stale = new TreeSet<>();
        for (String entry : REFUSED.keySet()) {
            int dot = entry.lastIndexOf('.');
            String schema = entry.substring(0, dot);
            String property = entry.substring(dot + 1);
            if (!api.propertyNames(schema).contains(property)) {
                stale.add(entry);
            }
        }
        assertThat(stale).as("these refusals name properties the contract no longer has").isEmpty();
    }

    private Map<String, HandlerMethod> handlersByOperationId() {
        Map<String, HandlerMethod> byId = new java.util.HashMap<>();
        mapping.getHandlerMethods().forEach((route, method) -> {
            NullnullOperation operation = method.getMethodAnnotation(NullnullOperation.class);
            if (operation != null) {
                byId.put(operation.id(), method);
            }
        });
        return byId;
    }

    /** Record component names of the handler's {@code @RequestBody} parameter, or an empty set. */
    private static Set<String> boundComponents(HandlerMethod handler) {
        for (var parameter : handler.getMethodParameters()) {
            if (parameter.getParameterAnnotation(RequestBody.class) == null) {
                continue;
            }
            Class<?> type = parameter.getParameterType();
            if (!type.isRecord()) {
                return Set.of();
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName());
            }
            return names;
        }
        return Set.of();
    }
}
