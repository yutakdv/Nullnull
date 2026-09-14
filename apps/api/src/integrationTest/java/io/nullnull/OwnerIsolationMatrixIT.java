package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * BA-070 over every trip-scoped operation at once.
 *
 * <p>Each slice proved owner isolation for the operations it added. This asks the question no slice
 * can: whether the answer holds across ALL of them, including the one added tomorrow. The call list
 * is read from {@code docs/api/openapi.yaml} rather than written here, so a new
 * {@code /trips/&#123;tripId&#125;} path joins the matrix by existing - a hand-kept list would go
 * quietly out of date, which is the failure this test is for.
 *
 * <p>The property is not "a foreign trip is refused" but "a foreign trip is INDISTINGUISHABLE from
 * one that was never created". A 403 would still answer the question the caller was really asking,
 * which is whether that id exists - invariant 11, and the reason {@code JdbcCandidateStore.find}
 * puts the owner in the join rather than checking it after the read.
 *
 * <p><strong>The third call is what keeps this honest.</strong> Two responses match easily because
 * both requests died before reaching the ownership decision: a body the operation rejects, a
 * content type it does not accept, an ETag it demands. A test comparing only those two would pass
 * against an API with no isolation at all. So each operation is also called against the caller's OWN
 * trip with the same body, and that answer must DIFFER. The first run of this test failed exactly
 * there and was right to: fourteen of sixteen operations were being measured for validation rather
 * than ownership, and one of them - {@code deleteTrip} - had destroyed the fixture every later
 * operation depended on. Fixtures are built per operation for that reason.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        // Both capabilities on: a flag that is off answers 403 to everyone, which leaks nothing but
        // also means ownership is never consulted, so the matrix would be measuring the flag.
        "nullnull.capabilities.optimization=true",
        "NULLNULL_CURSOR_SECRET=test-owner-isolation-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-070 owner isolation across every trip-scoped operation")
class OwnerIsolationMatrixIT {

    private static final Path SPEC = Path.of("../../docs/api/openapi.yaml");
    private static final String ORIGIN = "http://localhost:5173";

    /** Operations whose contract lists application/merge-patch+json first; application/json is a 415. */
    private static final Set<String> MERGE_PATCH = Set.of("updateTrip", "updateTripItem");

    /** Query parameters the matrix knows how to satisfy, by name. */
    private static final java.util.Map<String, String> QUERY_VALUES =
            java.util.Map.of("disposition", "REMOVE");

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * {@code idempotent} and {@code query} are read from the operation's own parameter list rather
     * than kept here as a second list. Both were hardcoded first and both were wrong, in the same
     * way: the scan that built them looked at a fixed number of characters after the operationId and
     * replaceTripItem's description is long enough to push its parameters past the window. A list
     * derived where the block is already isolated cannot drift from the contract or from itself.
     */
    private record Operation(String id, String method, String pathTemplate, boolean idempotent,
            java.util.List<String> query) {
    }

    /** Everything one operation's three calls need, built fresh so no call can spoil the next. */
    private record Fixture(SessionService.Bootstrap mine, String myTrip, String theirTrip,
            UUID placeId, UUID otherPlaceId, String candidateId, String itemId, String etag,
            long version) {
    }

    /**
     * Every row this class created, so it can put the database back.
     *
     * <p>A fixture per operation is what keeps the three calls honest, and it is also what makes
     * this the heaviest producer of rows in the suite: sixteen operations leave sixteen trips and
     * thirty-two places behind. Leaving them is not a private untidiness. {@code places} is
     * referenced by {@code trip_candidates} and {@code post_places} with NO cascade - deliberately,
     * since a place must not vanish under a candidate - so another test's {@code DELETE FROM places}
     * dies on rows this one abandoned, and the failure surfaces in THAT test's name. It did: a run
     * of this suite in the Compose gate ended 79 tests red, and the first name in the list belonged
     * to someone else. Each test was green on its own; the collision only exists in the order the
     * two happen to run, which is why it survived two local runs and a merge.
     *
     * <p>Deleting the trips is enough for everything beneath them - {@code trip_items},
     * {@code trip_candidates}, {@code candidate_sources}, {@code trip_constraints} all cascade, and
     * {@code owners.active_trip_id} is ON DELETE SET NULL. The places need their own statement
     * because they deliberately do not cascade.
     */
    private final List<String> createdTrips = new ArrayList<>();
    private final List<UUID> createdPlaces = new ArrayList<>();

    @AfterEach
    void removeWhatThisTestCreated() {
        for (String tripId : createdTrips) {
            jdbc.update("DELETE FROM trips WHERE id = ?", UUID.fromString(tripId));
        }
        for (UUID placeId : createdPlaces) {
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
        createdTrips.clear();
        createdPlaces.clear();
    }

    // ---------------------------------------------------------------- spec

    /**
     * Every operation whose path names a trip, read from the contract.
     *
     * <p>A regex over the YAML rather than a parser: what is read here is two levels deep and the
     * indentation is fixed by the linter, so a parser dependency in this source set would be a
     * larger thing to keep working than the lines it replaces.
     */
    private static List<Operation> tripScopedOperations() throws Exception {
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);
        List<Operation> found = new ArrayList<>();
        Matcher path = Pattern.compile("^ {2}(/\\S*\\{tripId}\\S*):\\s*$", Pattern.MULTILINE).matcher(spec);
        while (path.find()) {
            Matcher next = Pattern.compile("^ {2}/", Pattern.MULTILINE).matcher(spec);
            int end = next.find(path.end()) ? next.start() : spec.length();
            String block = spec.substring(path.end(), end);
            Matcher operation = Pattern.compile(
                    "^ {4}(get|put|post|patch|delete):\\s*$.*?^ {6}operationId: (\\w+)",
                    Pattern.MULTILINE | Pattern.DOTALL).matcher(block);
            int from = 0;
            while (operation.find(from)) {
                int following = block.indexOf("operationId:", operation.end());
                String body = following < 0 ? block.substring(operation.start())
                        : block.substring(operation.start(), following);
                java.util.List<String> query = new ArrayList<>();
                // REQUIRED ones only. An optional filter left off changes nothing about whether
                // the call reaches the ownership decision, and demanding a value for every one
                // would make this refuse to run over listTripCandidates's status filter.
                Matcher named = Pattern.compile(
                        "- name: (\\w+)\\s*\\n\\s*in: query\\s*\\n\\s*required: true").matcher(body);
                while (named.find()) {
                    query.add(named.group(1));
                }
                found.add(new Operation(operation.group(2), operation.group(1), path.group(1),
                        body.contains("parameters/IdempotencyKey"), List.copyOf(query)));
                from = operation.end();
            }
        }
        return found;
    }

    // ------------------------------------------------------------- fixtures

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        createdPlaces.add(id);
        return id;
    }

    private String createJson(SessionService.Bootstrap owner, String path, String body) throws Exception {
        String response = mvc.perform(post("/api/v1" + path).cookie(cookie(owner))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "fix-" + UUID.randomUUID())
                        .contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        if (!response.contains("\"id\"")) {
            throw new IllegalStateException("fixture call to " + path + " failed: " + response);
        }
        return response;
    }

    private String trip(SessionService.Bootstrap owner) throws Exception {
        String created = createJson(owner, "/trips",
                "{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\",\"timezone\":\"Asia/Seoul\","
                        + "\"planningLevel\":\"NOTHING\",\"interests\":[]}");
        String tripId = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");
        createdTrips.add(tripId);
        return tripId;
    }

    private Fixture fixture() throws Exception {
        var mine = owner();
        var theirs = owner();
        String myTrip = trip(mine);
        String theirTrip = trip(theirs);
        UUID placeId = place("경계 " + UUID.randomUUID());
        UUID otherPlaceId = place("대체 " + UUID.randomUUID());

        String candidate = createJson(mine, "/trips/" + myTrip + "/candidates",
                "{\"placeId\":\"" + placeId + "\",\"source\":{\"type\":\"SEARCH\"}}");
        String candidateId = candidate.replaceAll("(?s).*\"candidate\":\\{\"id\":\"([^\"]+)\".*", "$1");

        // A real item: the item-scoped operations answer 404 for an invented one whoever asks, which
        // would make own and foreign match and hide whether ownership was ever consulted.
        String item = mvc.perform(post("/api/v1/trips/" + myTrip + "/items").cookie(cookie(mine))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", mine.csrf.token)
                        .header("Idempotency-Key", "item-" + UUID.randomUUID())
                        .header("If-Match", etagOf(mine, myTrip))
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + placeId + "\",\"date\":\"2026-10-04\",\"position\":0}"))
                .andReturn().getResponse().getContentAsString();
        Matcher itemMatch = Pattern.compile("\"items\"\\s*:\\s*\\[\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(item);
        if (!itemMatch.find()) {
            throw new IllegalStateException("item fixture failed: " + item);
        }
        // removeTripItemConstraint answers 404 to owner and stranger alike when there is nothing to
        // remove, so the constraint has to exist before the matrix can learn anything from it.
        mvc.perform(put("/api/v1/trips/" + myTrip + "/items/" + itemMatch.group(1)
                        + "/constraints/MUST_VISIT").cookie(cookie(mine))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", mine.csrf.token)
                        .header("If-Match", etagOf(mine, myTrip))
                        .contentType("application/json")
                        .content("{\"type\":\"MUST_VISIT\",\"locked\":true}"))
                .andReturn();
        String etag = etagOf(mine, myTrip);
        long version = Long.parseLong(jdbc.queryForObject(
                "SELECT version FROM trips WHERE id = ?", String.class, UUID.fromString(myTrip)));
        return new Fixture(mine, myTrip, theirTrip, placeId, otherPlaceId, candidateId,
                itemMatch.group(1), etag, version);
    }

    private String etagOf(SessionService.Bootstrap owner, String tripId) throws Exception {
        return mvc.perform(get("/api/v1/trips/" + tripId).cookie(cookie(owner)))
                .andReturn().getResponse().getHeader("ETag");
    }

    // ---------------------------------------------------------------- calls

    private MvcResult call(Operation operation, Fixture fixture, String tripId) throws Exception {
        String path = "/api/v1" + operation.pathTemplate()
                .replace("{tripId}", tripId)
                .replace("{candidateId}", fixture.candidateId())
                .replace("{itemId}", fixture.itemId())
                .replace("{constraintType}", "MUST_VISIT");
        MockHttpServletRequestBuilder request = switch (operation.method()) {
            case "get" -> get(path);
            case "post" -> post(path);
            case "put" -> put(path);
            case "patch" -> patch(path);
            case "delete" -> delete(path);
            default -> throw new IllegalStateException("unknown method " + operation.method());
        };
        for (String parameter : operation.query()) {
            String value = QUERY_VALUES.get(parameter);
            if (value == null) {
                throw new IllegalStateException("no value for required query parameter "
                        + parameter + " on " + operation.id() + ": the matrix cannot reach the "
                        + "ownership decision without it");
            }
            request.param(parameter, value);
        }
        request.cookie(cookie(fixture.mine())).header("Origin", ORIGIN);
        if (!"get".equals(operation.method())) {
            request.header("X-CSRF-Token", fixture.mine().csrf.token)
                    .header("If-Match", fixture.etag());
            if (operation.idempotent()) {
                request.header("Idempotency-Key", "iso-" + UUID.randomUUID());
            }
        }
        String body = bodyFor(operation.id(), fixture);
        if (body != null) {
            request.contentType(MERGE_PATCH.contains(operation.id())
                    ? "application/merge-patch+json" : "application/json").content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private static String verdict(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher code = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"").matcher(body);
        return result.getResponse().getStatus() + "/" + (code.find() ? code.group(1) : "-");
    }

    @Test
    @DisplayName("BA-070-T1 another owner's trip answers exactly what a trip that never existed answers")
    void aForeignTripIsIndistinguishableFromAnAbsentOne() throws Exception {
        List<Operation> operations = tripScopedOperations();
        assertThat(operations).as("trip-scoped operations read from the contract").hasSizeGreaterThan(10);

        Set<String> leaked = new LinkedHashSet<>();
        Set<String> vacuous = new LinkedHashSet<>();
        for (Operation operation : operations) {
            Fixture fixture = fixture();
            // Own last: deleteTrip succeeds there, and anything it breaks is this fixture alone.
            String foreign = verdict(call(operation, fixture, fixture.theirTrip()));
            String absent = verdict(call(operation, fixture, UUID.randomUUID().toString()));
            String own = verdict(call(operation, fixture, fixture.myTrip()));
            if (!foreign.equals(absent)) {
                leaked.add(operation.id() + ": foreign=" + foreign + " absent=" + absent);
            }
            if (foreign.equals(own)) {
                vacuous.add(operation.id() + ": own=" + own + " equals foreign=" + foreign);
            }
        }
        assertThat(vacuous).as("operations whose own-trip answer matches the foreign one - for those "
                + "this test measured validation, not ownership").isEmpty();
        assertThat(leaked).as("operations that tell a caller a trip they do not own exists").isEmpty();
    }

    /**
     * The smallest VALID body each mutating operation accepts, taken from its request schema.
     *
     * <p>Valid, not merely present. Some operations validate before they decide ownership -
     * createOptimization answers 422 to a malformed body - and against those a plausible-looking
     * body makes all three calls fail identically, which is the vacuum the guard above catches.
     */
    private static String bodyFor(String operationId, Fixture fixture) {
        return switch (operationId) {
            case "addTripCandidate" ->
                "{\"placeId\":\"" + fixture.otherPlaceId() + "\",\"source\":{\"type\":\"SEARCH\"}}";
            case "addTripItem" ->
                "{\"placeId\":\"" + fixture.otherPlaceId() + "\",\"date\":\"2026-10-05\",\"position\":1}";
            case "createOptimization" ->
                "{\"scope\":\"ITEM\",\"targetItemId\":\"" + fixture.itemId()
                    + "\",\"inputTripVersion\":" + fixture.version() + ",\"includeCandidates\":false}";
            case "reorderTripItems" ->
                "{\"items\":[{\"itemId\":\"" + fixture.itemId()
                    + "\",\"date\":\"2026-10-04\",\"position\":0}]}";
            case "replaceTripInterests" -> "{\"interests\":[]}";
            case "replaceTripItem" -> "{\"replacementPlaceId\":\"" + fixture.otherPlaceId() + "\"}";
            case "setTripItemConstraint" -> "{\"type\":\"MUST_VISIT\",\"locked\":true}";
            case "updateTrip" -> "{\"title\":\"제목\"}";
            case "updateTripItem" -> "{\"durationMinutes\":60}";
            default -> null;
        };
    }
}
