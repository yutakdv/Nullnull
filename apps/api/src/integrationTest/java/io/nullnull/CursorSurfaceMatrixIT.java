package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.nullnull.identity.application.SessionService;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.cursor.SignedCursorCodec;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-027 over every cursor-paged surface at once.
 *
 * <p>A cursor used to carry {@code nextOrdinal}: how many rows to skip. §5.1 designed that number
 * for a frozen snapshot, but {@code feed_snapshots} was never built, so it became an OFFSET into a
 * live table - and an offset is a claim about the size of the set rather than about the reader's
 * place in it. A row inserted ahead of them re-serves one they have read; a row removed ahead of
 * them skips one they have not. The worst case is the one the product asks for: saving a candidate
 * while browsing the feed puts a row at the head of {@code created_at DESC} and pushes every later
 * page of that owner's own list along by one.
 *
 * <p><strong>The surface list is read from the contract, not written here.</strong> Four surfaces
 * carried the defect; a fifth written tomorrow would be born with it, and a hand-kept list would go
 * quietly out of date exactly then. Surfaces are found by their response shape - every schema that
 * embeds {@code CursorPage}, then every operation that returns one of those - because
 * {@code searchPlaces} is a read-only POST whose cursor travels in the body, so scanning for the
 * {@code cursor} query parameter would miss it.
 *
 * <p><strong>Two of the six are declared and not yet routed</strong> ({@code listOptimizationHistory},
 * {@code listNotifications}). They are not skipped: the fixtures below must cover exactly the
 * surfaces the application actually serves, which is read from the running context's handler
 * methods. Routing either one makes {@code BA-027-T4} fail until it has a fixture here, which is
 * the only thing that stops the fifth surface being born with the defect.
 */
@SpringBootTest(properties = {
        // searchPlaces and the feed's embedded places both sit behind the publication gate; with it
        // closed they answer 503 and there is no listing to page through.
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-cursor-surface-matrix-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-027 cursor pagination across every cursor-paged surface")
class CursorSurfaceMatrixIT {

    private static final Path SPEC = Path.of("../../docs/api/openapi.yaml");
    private static final String ORIGIN = "http://localhost:5173";
    private static final int SEEDED = 4;

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @Autowired io.nullnull.social.application.FeedCursorProperties feedCursors;
    @Autowired io.nullnull.trip.application.TripCursorProperties tripCursors;
    @Autowired io.nullnull.catalog.application.CatalogPublicationProperties catalogPublication;

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("BA-027-T1 a row inserted ahead of the cursor does not re-serve a row already read")
    void aRowInsertedAheadDoesNotRepeatOne() throws Exception {
        Map<String, String> repeated = new LinkedHashMap<>();
        for (Surface surface : surfaces().values()) {
            SessionService.Bootstrap reader = surface.seed();
            assertThat(surface.page(reader, null, 50).ids())
                    .as(surface.operationId + " seeded rows are all visible").hasSize(SEEDED);

            Page first = surface.page(reader, null, 2);
            assertThat(first.nextCursor()).as(surface.operationId + " hands out a cursor").isNotNull();

            UUID ahead = surface.insertAhead();
            // Without this the test would pass against a row that never went ahead of anything.
            assertThat(surface.page(reader, null, 50).ids())
                    .as(surface.operationId + " the new row really did land ahead of the page just read")
                    .startsWith(ahead);

            List<UUID> second = surface.page(reader, first.nextCursor(), 2).ids();
            List<UUID> again = new ArrayList<>(second);
            again.retainAll(first.ids());
            if (!again.isEmpty()) {
                repeated.put(surface.operationId, "read " + first.ids() + " then " + second);
            }
        }
        assertThat(repeated).as("surfaces that served a row the reader had already been given").isEmpty();
    }

    @Test
    @DisplayName("BA-027-T2 a row removed ahead of the cursor does not skip a row not yet read")
    void aRowRemovedAheadDoesNotSkipOne() throws Exception {
        Map<String, String> skipped = new LinkedHashMap<>();
        for (Surface surface : surfaces().values()) {
            SessionService.Bootstrap reader = surface.seed();
            List<UUID> all = surface.page(reader, null, 50).ids();
            Page first = surface.page(reader, null, 2);
            assertThat(first.ids()).as(surface.operationId + " first page").containsExactly(all.get(0), all.get(1));

            // A row the reader has already been served disappears from behind them. Under an offset
            // the set shrinks and the same offset now points one row further on.
            surface.remove(all.get(0));

            List<UUID> second = surface.page(reader, first.nextCursor(), 2).ids();
            if (!second.equals(List.of(all.get(2), all.get(3)))) {
                skipped.put(surface.operationId, "expected " + List.of(all.get(2), all.get(3)) + " got " + second);
            }
        }
        assertThat(skipped).as("surfaces that skipped a row the reader had not been given").isEmpty();
    }

    @Test
    @DisplayName("BA-027-T3 a cursor names the row the page ended on and not its place in the listing")
    void aCursorNamesARowRatherThanACount() throws Exception {
        Map<String, String> positional = new LinkedHashMap<>();
        for (Surface surface : surfaces().values()) {
            SessionService.Bootstrap reader = surface.seed();
            List<UUID> all = surface.page(reader, null, 50).ids();

            // Two cursors for the SAME last row, minted when that row sat at different places in the
            // listing: second of four, then third of five. A cursor holding the sort key cannot tell
            // them apart; one holding a count resumes two rows apart.
            String fromSecond = surface.page(reader, null, 2).nextCursor();
            UUID ahead = surface.insertAhead();
            Page wider = surface.page(reader, null, 3);
            assertThat(wider.ids()).as(surface.operationId + " both pages end on the same row")
                    .containsExactly(ahead, all.get(0), all.get(1));

            List<UUID> afterSecond = surface.page(reader, fromSecond, 2).ids();
            List<UUID> afterThird = surface.page(reader, wider.nextCursor(), 2).ids();
            if (!afterSecond.equals(afterThird)) {
                positional.put(surface.operationId, "resumed at " + afterSecond + " and " + afterThird);
            }
            if (!afterSecond.equals(List.of(all.get(2), all.get(3)))) {
                positional.put(surface.operationId + " (position)",
                        "expected " + List.of(all.get(2), all.get(3)) + " got " + afterSecond);
            }
        }
        assertThat(positional).as("surfaces whose cursor moved when the rows ahead of it did").isEmpty();
    }

    @Test
    @DisplayName("BA-027-T4 every cursor-paged surface the API serves is covered by this matrix")
    void everyCursorPagedSurfaceIsCovered() throws Exception {
        Set<String> declared = cursorPagedOperations();
        assertThat(declared).as("operations returning a page that embeds CursorPage, read from the contract")
                .hasSizeGreaterThanOrEqualTo(4);

        Set<String> routed = new LinkedHashSet<>(declared);
        routed.retainAll(servedOperations());
        Set<String> notRouted = new LinkedHashSet<>(declared);
        notRouted.removeAll(routed);

        // Equality in both directions. A surface that starts being served without a fixture here
        // fails on the left; a fixture for something the API does not serve fails on the right, and
        // would otherwise sit in this file proving nothing.
        assertThat(surfaces().keySet())
                .as("cursor-paged surfaces the running API serves (declared but not routed: " + notRouted
                        + " - routing one of those makes this fail until it is covered here)")
                .containsExactlyInAnyOrderElementsOf(routed);
    }

    @Test
    @DisplayName("BA-027-T5 a cursor issued under another sort order is refused rather than resumed")
    void aCursorFromAnotherSortOrderIsRefused() throws Exception {
        Map<String, String> resumed = new LinkedHashMap<>();
        for (Surface surface : surfaces().values()) {
            SessionService.Bootstrap reader = surface.seed();
            String issued = surface.page(reader, null, 2).nextCursor();
            assertThat(issued).as(surface.operationId + " hands out a cursor").isNotNull();

            // An offset was a number, and a number means the same thing under any ordering. A key is
            // the name the ordering gives a row, so feeding one order's key to another resumes at a
            // place nobody asked for - silently, because everything else about the cursor is intact.
            //
            // The identity re-issue is what keeps this honest: it changes nothing but the signature,
            // and it must still be accepted. Without it a surface that rejected every re-signed
            // cursor - or every cursor at all - would pass while measuring nothing.
            assertThat(surface.call(reader, reissued(surface.codec(), issued, 0), 2).getResponse().getStatus())
                    .as(surface.operationId + " the same claims re-signed are still accepted")
                    .isEqualTo(200);

            var response = surface.call(reader, reissued(surface.codec(), issued, 1), 2).getResponse();
            if (response.getStatus() != 400 || !response.getContentAsString().contains("CURSOR_INVALID")) {
                resumed.put(surface.operationId, response.getStatus() + " " + response.getContentAsString());
            }
        }
        assertThat(resumed).as("surfaces that accepted a cursor minted under a different sort order").isEmpty();
    }

    // ---------------------------------------------------------------- spec

    /**
     * Every operation whose 200 response is a page that embeds {@code CursorPage}.
     *
     * <p>A regex over the YAML rather than a parser, for the reason {@code OwnerIsolationMatrixIT}
     * gives: what is read here is two levels deep under an indentation the linter already fixes, so
     * a parser dependency in this source set would be more to keep working than the lines it saves.
     */
    private static Set<String> cursorPagedOperations() throws Exception {
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);
        String components = spec.substring(spec.indexOf("\n  schemas:"));
        Set<String> pageSchemas = new LinkedHashSet<>();
        Matcher schema = Pattern.compile("^ {4}(\\w+):\\s*$", Pattern.MULTILINE).matcher(components);
        List<int[]> blocks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (schema.find()) {
            names.add(schema.group(1));
            blocks.add(new int[] {schema.end(), components.length()});
            if (blocks.size() > 1) {
                blocks.get(blocks.size() - 2)[1] = schema.start();
            }
        }
        for (int index = 0; index < names.size(); index++) {
            String body = components.substring(blocks.get(index)[0], blocks.get(index)[1]);
            if (body.contains("$ref: \"#/components/schemas/CursorPage\"")) {
                pageSchemas.add(names.get(index));
            }
        }
        assertThat(pageSchemas).as("schemas that embed CursorPage").isNotEmpty();

        Set<String> operations = new LinkedHashSet<>();
        Matcher operation = Pattern.compile("^ {6}operationId: (\\w+)\\s*$", Pattern.MULTILINE).matcher(spec);
        List<Integer> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (operation.find()) {
            ids.add(operation.group(1));
            starts.add(operation.end());
        }
        for (int index = 0; index < ids.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : spec.length();
            String body = spec.substring(starts.get(index), end);
            for (String page : pageSchemas) {
                if (body.contains("$ref: \"#/components/schemas/" + page + "\"")) {
                    operations.add(ids.get(index));
                }
            }
        }
        return operations;
    }

    /** operationIds the running application actually has a handler method for. */
    private Set<String> servedOperations() {
        Set<String> served = new LinkedHashSet<>();
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().values().forEach(handler -> {
                    NullnullOperation operation = handler.getMethodAnnotation(NullnullOperation.class);
                    if (operation != null) {
                        served.add(operation.id());
                    }
                });
        return served;
    }

    // ------------------------------------------------------------- surfaces

    private Map<String, Surface> surfaces() {
        Map<String, Surface> surfaces = new LinkedHashMap<>();
        for (Surface surface : List.of(new FeedSurface(), new CandidateSurface(), new TripSurface(),
                new PlaceSearchSurface())) {
            surfaces.put(surface.operationId, surface);
        }
        return surfaces;
    }

    /** One page as this matrix reads it: the ids in order, and the cursor for the next one. */
    private record Page(List<UUID> ids, String nextCursor) { }

    /**
     * A listing that pages with a cursor, and the three things a pagination test needs from it:
     * rows to read, a row that lands AHEAD of what has been read, and a way to take one away.
     *
     * <p>Removals go through JDBC rather than an API where an API would not produce the state at
     * all - a post is hidden by a curator, a place loses the coordinates the projection requires -
     * because what is being modelled is a row leaving the set, not the operation that moved it.
     */
    private abstract class Surface {

        final String operationId;
        /** {@code items[].<nested>.id} where the item wraps the row, or null for {@code items[].id}. */
        private final String nested;

        Surface(String operationId, String nested) {
            this.operationId = operationId;
            this.nested = nested;
        }

        /** Creates {@link #SEEDED} rows and returns the session that can read them. */
        abstract SessionService.Bootstrap seed() throws Exception;

        /** Creates one more row that sorts ahead of every seeded one; returns its id. */
        abstract UUID insertAhead() throws Exception;

        abstract void remove(UUID id);

        abstract MvcResult call(SessionService.Bootstrap reader, String cursor, int limit) throws Exception;

        /** The codec that signs this surface's cursors, so a test can re-issue one it already holds. */
        abstract SignedCursorCodec codec();

        Page page(SessionService.Bootstrap reader, String cursor, int limit) throws Exception {
            return read(call(reader, cursor, limit).getResponse().getContentAsString(), nested);
        }
    }

    private final class FeedSurface extends Surface {

        private UUID place;

        FeedSurface() {
            super("listFeed", "post");
        }

        @Override
        SignedCursorCodec codec() {
            return feedCursors.cursorCodec();
        }

        @Override
        SessionService.Bootstrap seed() {
            // The feed is global: without this, another test class's posts are part of this listing
            // and the assertions become about the order the suite ran in (FeedIT does the same).
            jdbc.update("DELETE FROM posts");
            place = place("피드 " + UUID.randomUUID(), false);
            for (int index = 0; index < SEEDED; index++) {
                post("커서 " + index, Instant.parse("2026-05-01T00:00:00Z").plusSeconds(index * 60L));
            }
            return owner();
        }

        @Override
        UUID insertAhead() {
            return post("먼저 공개된 글", Instant.parse("2026-06-01T00:00:00Z"));
        }

        @Override
        void remove(UUID id) {
            // published_at goes with the status: posts_published_shape_check holds the two together,
            // because a row the feed orders by publishedAt has no place in that order without one.
            jdbc.update("UPDATE posts SET status = 'HIDDEN', published_at = NULL WHERE id = ?", id);
        }

        @Override
        MvcResult call(SessionService.Bootstrap reader, String cursor, int limit) throws Exception {
            var request = get("/api/v1/feed").param("limit", Integer.toString(limit)).cookie(cookie(reader));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            return mvc.perform(request).andReturn();
        }

        private UUID post(String title, Instant publishedAt) {
            UUID id = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id,"
                            + " created_at, updated_at)"
                            + " VALUES (?, 'DRAFT', ?, ?, 'https://example.test/cover.jpg', ?, ?, ?)",
                    id, title, "본문 " + title,
                    io.nullnull.testsupport.PostCovers.firstPartyAsset(jdbc, Instant.now()), now, now);
            jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                    + " VALUES (?, ?, 0, 'PRIMARY')", id, place);
            // Published last: V022's trigger requires the primary place to exist first.
            jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                    Timestamp.from(publishedAt), id);
            return id;
        }
    }

    private final class CandidateSurface extends Surface {

        private String tripId;
        private SessionService.Bootstrap reader;

        CandidateSurface() {
            super("listTripCandidates", null);
        }

        @Override
        SignedCursorCodec codec() {
            return tripCursors.cursorCodec();
        }

        @Override
        SessionService.Bootstrap seed() throws Exception {
            reader = owner();
            tripId = trip(reader, "2026-10-04");
            for (int index = 0; index < SEEDED; index++) {
                UUID candidate = candidate();
                // created_at is the sort value and every save here happens in the same breath, so
                // the seeded order is set explicitly rather than left to how fast the suite runs.
                jdbc.update("UPDATE trip_candidates SET created_at = ? WHERE id = ?",
                        Timestamp.from(Instant.parse("2026-05-01T00:00:00Z").plusSeconds(index * 60L)),
                        candidate);
            }
            return reader;
        }

        @Override
        UUID insertAhead() throws Exception {
            UUID candidate = candidate();
            jdbc.update("UPDATE trip_candidates SET created_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")), candidate);
            return candidate;
        }

        @Override
        void remove(UUID id) {
            jdbc.update("DELETE FROM candidate_sources WHERE candidate_id = ?", id);
            jdbc.update("DELETE FROM trip_candidates WHERE id = ?", id);
        }

        @Override
        MvcResult call(SessionService.Bootstrap owner, String cursor, int limit) throws Exception {
            var request = get("/api/v1/trips/" + tripId + "/candidates")
                    .param("limit", Integer.toString(limit)).cookie(cookie(owner));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            return mvc.perform(request).andReturn();
        }

        private UUID candidate() throws Exception {
            String body = mvc.perform(post("/api/v1/trips/" + tripId + "/candidates").cookie(cookie(reader))
                            .header("Origin", ORIGIN).header("X-CSRF-Token", reader.csrf.token)
                            .header("Idempotency-Key", "cursor-" + UUID.randomUUID())
                            .contentType("application/json")
                            .content("{\"placeId\":\"" + place("후보 " + UUID.randomUUID(), false)
                                    + "\",\"source\":{\"type\":\"SEARCH\"}}"))
                    .andReturn().getResponse().getContentAsString();
            JsonNode saved = json.readTree(body).path("candidate").path("id");
            if (saved.isMissingNode()) {
                throw new IllegalStateException("candidate fixture failed: " + body);
            }
            return UUID.fromString(saved.stringValue());
        }
    }

    private final class TripSurface extends Surface {

        private SessionService.Bootstrap reader;

        TripSurface() {
            super("listTrips", null);
        }

        @Override
        SignedCursorCodec codec() {
            return tripCursors.cursorCodec();
        }

        @Override
        SessionService.Bootstrap seed() throws Exception {
            // A fresh owner each time: this listing is per-owner, so the seeded four are all of it.
            reader = owner();
            for (int index = 0; index < SEEDED; index++) {
                trip(reader, "2026-10-0" + (index + 1));
            }
            return reader;
        }

        @Override
        UUID insertAhead() throws Exception {
            return UUID.fromString(trip(reader, "2026-11-01"));
        }

        @Override
        void remove(UUID id) {
            // owners.active_trip_id is ON DELETE SET NULL, so the row simply goes.
            jdbc.update("DELETE FROM trips WHERE id = ?", id);
        }

        @Override
        MvcResult call(SessionService.Bootstrap owner, String cursor, int limit) throws Exception {
            var request = get("/api/v1/trips").param("limit", Integer.toString(limit)).cookie(cookie(owner));
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            return mvc.perform(request).andReturn();
        }
    }

    private final class PlaceSearchSurface extends Surface {

        private String token;

        PlaceSearchSurface() {
            super("searchPlaces", null);
        }

        @Override
        SignedCursorCodec codec() {
            return catalogPublication.cursorCodec();
        }

        @Override
        SessionService.Bootstrap seed() {
            // The seeded names carry a token unique to this run, so the query matches these rows and
            // nothing another test left behind, and they sort by an ASCII suffix this test controls.
            token = "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            for (int index = 0; index < SEEDED; index++) {
                place(token + "-b" + index, true);
            }
            return owner();
        }

        @Override
        UUID insertAhead() {
            return place(token + "-a0", true);
        }

        @Override
        void remove(UUID id) {
            // The card's own case: a place that loses its eligibility for the public projection,
            // which requires coordinates. DEPRECATED is not the way to model it - that status is a
            // MERGE, and catalog_require_active_canonical_target refuses one without a target, so
            // deprecating here would be inventing a merge this test has no reason to perform.
            jdbc.update("UPDATE places SET latitude = NULL, longitude = NULL WHERE id = ?", id);
        }

        @Override
        MvcResult call(SessionService.Bootstrap reader, String cursor, int limit) throws Exception {
            String body = "{\"query\":\"" + token + "\",\"limit\":" + limit
                    + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
            return mvc.perform(post("/api/v1/places/search").cookie(cookie(reader))
                    .header("Origin", ORIGIN).contentType("application/json").content(body)).andReturn();
        }
    }

    // ------------------------------------------------------------- fixtures

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID place(String name, boolean coordinates) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (coordinates) {
            jdbc.update("INSERT INTO places (id, canonical_name, category_code, latitude, longitude,"
                    + " region_code, status, created_at, updated_at)"
                    + " VALUES (?, ?, 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)", id, name, now, now);
        } else {
            jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                    + " created_at, updated_at) VALUES (?, ?, 'A0101', '1', 'ACTIVE', ?, ?)", id, name, now, now);
        }
        return id;
    }

    private String trip(SessionService.Bootstrap owner, String startDate) throws Exception {
        String body = mvc.perform(post("/api/v1/trips").cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "cursor-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"" + startDate + "\",\"endDate\":\"" + startDate
                                + "\",\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode id = json.readTree(body).path("id");
        if (id.isMissingNode()) {
            throw new IllegalStateException("trip fixture failed: " + body);
        }
        return id.stringValue();
    }

    /**
     * The same cursor re-signed, with {@code sortVersionDelta} added to its sort version.
     *
     * <p>The claims are read out of the payload rather than rebuilt from per-surface constants: the
     * snapshot id, context and owner binding a surface uses are its own business, and a test that
     * restated them here would drift from whichever surface changed one.
     */
    private static String reissued(SignedCursorCodec codec, String cursor, int sortVersionDelta) {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split("\\|", -1);
        return codec.encode(new CursorClaims(parts[0], parts[1], parts[2], parts[3],
                Integer.parseInt(parts[4]) + sortVersionDelta,
                Instant.ofEpochSecond(Long.parseLong(parts[5])), parts[6]));
    }

    /** {@code items[].id}, or {@code items[].<nested>.id} where the item wraps the row. */
    private Page read(String body, String nested) {
        JsonNode root = json.readTree(body);
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            throw new IllegalStateException("not a cursor page: " + body);
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode row = nested == null ? item : item.path(nested);
            ids.add(UUID.fromString(row.path("id").stringValue()));
        }
        JsonNode next = root.path("page").path("nextCursor");
        return new Page(List.copyOf(ids), next.isNull() || next.isMissingNode() ? null : next.stringValue());
    }
}
