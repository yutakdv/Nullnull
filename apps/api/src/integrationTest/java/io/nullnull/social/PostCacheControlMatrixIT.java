package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.nullnull.identity.application.SessionService;
import io.nullnull.social.application.ObjectStorage;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BA-082-T17: every post route sends the {@code Cache-Control} its contract declares.
 *
 * <p><strong>Declaring a header and sending it are two facts, and only one of them was checked.</strong>
 * {@code SessionContractTest} reads {@code openapi.yaml} and asks whether an operation declares the
 * header; nothing compared that declaration with the bytes a response actually carries. BA-060-T19
 * is the same gap found in the importer, where {@code parseTripImport} declares {@code no-store}
 * while its siblings declare {@code private, no-store} and no test could tell.
 *
 * <p>It matters here because of what a withdrawal is for. BA-082's safety boundary says taking a
 * post back must block cache exposure too, and "no shared cache may hold this answer" is a promise
 * made by a header on the wire - not by a line in a YAML file.
 *
 * <p><strong>The operation list is read from the contract rather than written here.</strong> A
 * hand-kept list is the failure this test exists to prevent: a post route added tomorrow would sit
 * outside the clause silently. {@link io.nullnull.OwnerIsolationMatrixIT} reads its call list
 * the same way and for the same reason. The consequence is deliberate - a new {@code /posts} or
 * {@code /feed} operation turns this red until somebody exercises it here, which is the moment to
 * decide what its caching promise is.
 *
 * <p><strong>Two guards keep it from passing vacuously.</strong> An empty contract scan would make
 * "every declared route sends its header" true of nothing, so the count is floored. And every
 * operation the contract declares must be exercised, or the test names the one that was not -
 * without that, deleting a call from {@link #EXERCISES} would silently narrow the matrix, which is
 * exactly the shape reading the list from the contract was meant to close.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        PostAuthoringIT.RecordingStorage.class})
@DisplayName("BA-082 post routes send the Cache-Control they declare")
class PostCacheControlMatrixIT {

    private static final Path SPEC = Path.of("../../docs/api/openapi.yaml");
    private static final String ORIGIN = "http://localhost:5173";

    /** Paths this clause is about. The scan takes every operation under them, whatever it is called. */
    private static final List<String> POST_PATHS = List.of("/posts", "/feed");

    /**
     * The floor that makes the scan's emptiness a failure rather than a pass.
     *
     * <p>Seven were found on 2026-09-20. The floor is lower than the count on purpose: it is here to
     * catch a scan that stopped matching, not to pin a number that moves whenever a route is added.
     */
    private static final int MINIMUM_DECLARED = 5;

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectStorage storage;

    private PostAuthoringIT.Recorder recorder;
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> uploads = new ArrayList<>();

    @BeforeEach
    void resetRecorder() {
        recorder = (PostAuthoringIT.Recorder) storage;
        recorder.reset();
    }

    @Test
    @DisplayName("BA-082-T17 every /posts and /feed operation sends the Cache-Control its contract declares")
    void everyPostRouteSendsTheHeaderItDeclares() throws Exception {
        Map<String, String> declared = declaredCacheControl();
        assertThat(declared)
                .as("the contract scan found the post routes; an empty map would make every claim below vacuous")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_DECLARED);

        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        UUID uploadId = issueTicket(author);

        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("createPostImageUpload", headerOf(issueTicketResult(author)));

        MvcResult created = createPost(author, uploadId, placeId);
        sent.put("createPost", headerOf(created));
        UUID postId = uuidField(created, "postId");
        sent.put("listFeed", headerOf(mvc.perform(get("/api/v1/feed").cookie(cookie(author)))
                .andReturn()));
        sent.put("getPost", headerOf(mvc.perform(get("/api/v1/posts/" + postId).cookie(cookie(author)))
                .andReturn()));
        sent.put("savePost", headerOf(mvc.perform(put("/api/v1/posts/" + postId + "/saved")
                .cookie(cookie(author)).header("Origin", ORIGIN).header("X-CSRF-Token", author.csrf.token))
                .andReturn()));
        sent.put("unsavePost", headerOf(mvc.perform(delete("/api/v1/posts/" + postId + "/saved")
                .cookie(cookie(author)).header("Origin", ORIGIN).header("X-CSRF-Token", author.csrf.token))
                .andReturn()));
        sent.put("recordFeedFeedback", headerOf(mvc.perform(post("/api/v1/feed/feedback")
                .cookie(cookie(author)).header("Origin", ORIGIN).header("X-CSRF-Token", author.csrf.token)
                .header("Idempotency-Key", "feedback-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"postId\":\"" + postId + "\",\"action\":\"IMPRESSION\","
                        + "\"occurredAt\":\"2026-09-20T10:00:00Z\"}"))
                .andReturn()));

        // The guard that keeps reading the list from the contract meaningful: an operation nobody
        // called here is reported by name rather than skipped.
        Set<String> unexercised = new LinkedHashSet<>(declared.keySet());
        unexercised.removeAll(sent.keySet());
        assertThat(unexercised)
                .as("a post route the contract declares but this matrix never calls; exercise it here "
                        + "and decide what its caching promise is")
                .isEmpty();

        // The other direction, which the floor above cannot give. MINIMUM_DECLARED only says the
        // scan found something; it would be satisfied by a scan that quietly stopped seeing one
        // operation, and then that operation's promise is checked by nobody while everything stays
        // green. An operation this test calls but the scan did not find is that failure, by name.
        Set<String> unscanned = new LinkedHashSet<>(sent.keySet());
        unscanned.removeAll(declared.keySet());
        assertThat(unscanned)
                .as("this matrix calls an operation the contract scan did not find; the scan is "
                        + "narrower than it looks, not the contract")
                .isEmpty();

        for (Map.Entry<String, String> promise : declared.entrySet()) {
            assertThat(sent.get(promise.getKey()))
                    .as("%s declares Cache-Control: %s, so that is what it must send",
                            promise.getKey(), promise.getValue())
                    .isEqualTo(promise.getValue());
        }
    }

    /**
     * operationId to the one {@code Cache-Control} value its contract block declares.
     *
     * <p>Every declaration inside an operation must agree: an operation whose success and failure
     * responses promised different caching would be reported here rather than silently reduced to
     * whichever one the scan happened to read first.
     */
    private static Map<String, String> declaredCacheControl() throws IOException {
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);
        Map<String, String> declared = new TreeMap<>();
        Matcher path = Pattern.compile("^ {2}(/\\S*):\\s*$", Pattern.MULTILINE).matcher(spec);
        List<int[]> blocks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (path.find()) {
            blocks.add(new int[] {path.end(), spec.length()});
            names.add(path.group(1));
        }
        for (int i = 0; i < blocks.size() - 1; i++) {
            blocks.get(i)[1] = blocks.get(i + 1)[0];
        }
        for (int i = 0; i < blocks.size(); i++) {
            String name = names.get(i);
            if (POST_PATHS.stream().noneMatch(name::startsWith)) {
                continue;
            }
            String block = spec.substring(blocks.get(i)[0], blocks.get(i)[1]);
            Matcher operation = Pattern.compile("operationId:\\s*(\\w+)").matcher(block);
            List<int[]> ops = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            while (operation.find()) {
                ops.add(new int[] {operation.end(), block.length()});
                ids.add(operation.group(1));
            }
            for (int j = 0; j < ops.size() - 1; j++) {
                ops.get(j)[1] = ops.get(j + 1)[0];
            }
            for (int j = 0; j < ops.size(); j++) {
                String body = block.substring(ops.get(j)[0], ops.get(j)[1]);
                Set<String> values = new LinkedHashSet<>();
                Matcher header = Pattern.compile(
                        "Cache-Control:\\s*\\n\\s*schema:\\s*\\n\\s*type: string\\s*\\n\\s*const: (.+)")
                        .matcher(body);
                while (header.find()) {
                    values.add(header.group(1).trim());
                }
                if (values.isEmpty()) {
                    continue;
                }
                assertThat(values)
                        .as("%s declares one caching promise, not several", ids.get(j))
                        .hasSize(1);
                declared.put(ids.get(j), values.iterator().next());
            }
        }
        return declared;
    }

    private static String headerOf(MvcResult result) {
        assertThat(result.getResponse().getStatus())
                .as("the header is read from a response the operation meant to send, not from a rejection")
                .isLessThan(300);
        return result.getResponse().getHeader("Cache-Control");
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private MvcResult issueTicketResult(SessionService.Bootstrap owner) throws Exception {
        byte[] image = jpeg(64, 48);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        MvcResult result = mvc.perform(post("/api/v1/posts/images/uploads")
                .cookie(cookie(owner)).header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", "upload-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"contentType\":\"image/jpeg\",\"contentLength\":" + image.length
                        + ",\"checksumSha256\":\"" + checksum + "\"}"))
                .andReturn();
        UUID uploadId = uuidField(result, "uploadId");
        uploads.add(uploadId);
        recorder.put(quarantineKeyOf(uploadId), image);
        return result;
    }

    private UUID issueTicket(SessionService.Bootstrap owner) throws Exception {
        issueTicketResult(owner);
        return uploads.get(uploads.size() - 1);
    }

    private MvcResult createPost(SessionService.Bootstrap owner, UUID uploadId, UUID placeId)
            throws Exception {
        return mvc.perform(post("/api/v1/posts")
                .cookie(cookie(owner)).header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", "post-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"uploadId\":\"" + uploadId + "\",\"title\":\"가을 산책\",\"body\":\"좋았다\","
                        + "\"altText\":\"단풍\",\"placeIds\":[\"" + placeId + "\"]}"))
                .andReturn();
    }

    /**
     * The value of one uuid-valued field of a JSON response.
     *
     * <p>The match is asserted rather than assumed. {@code String.replaceAll} returns its input
     * unchanged when nothing matches, so a field that was renamed comes back as the whole body and
     * fails several lines later as "UUID string too large" - a message about the wrong thing. This
     * failed exactly that way first: the field is {@code postId}, not {@code id}.
     */
    private static UUID uuidField(MvcResult result, String field) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher match = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").matcher(body);
        assertThat(match.find()).as("%s is in the response: %s", field, body).isTrue();
        return UUID.fromString(match.group(1));
    }

    private String quarantineKeyOf(UUID uploadId) {
        return jdbc.queryForObject("SELECT quarantine_key FROM upload_intents WHERE id = ?",
                String.class, uploadId);
    }

    private UUID place() {
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, 'cache-control test 장소', 'HS', '11', 'ACTIVE', now(), now())",
                placeId);
        places.add(placeId);
        return placeId;
    }

    private static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.ORANGE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Removes what this class wrote, naming its own rows (the gate shares one database). */
    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID uploadId : uploads) {
            jdbc.update("DELETE FROM posts WHERE cover_asset_id IN"
                    + " (SELECT id FROM media_assets WHERE source_external_id = ?)", uploadId.toString());
            jdbc.update("DELETE FROM media_assets WHERE source_external_id = ?", uploadId.toString());
            jdbc.update("DELETE FROM upload_intents WHERE id = ?", uploadId);
        }
        for (UUID placeId : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
        uploads.clear();
        places.clear();
    }
}
