package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-082 createPostImageUpload and createPost against a real database.
 *
 * <p>The object store is a recording double. That is not a weaker test of the two clauses this
 * class exists for: T1 is about which caller may consume which ticket, and T2 is about whether
 * anything reaches the published location when validation refuses - both are decided in our code,
 * and the double lets the assertion count what was published rather than infer it.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        PostAuthoringIT.RecordingStorage.class})
@DisplayName("BA-082 post authoring")
class PostAuthoringIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectStorage storage;

    private Recorder recorder;

    @BeforeEach
    void resetRecorder() {
        recorder = (Recorder) storage;
        recorder.reset();
    }

    @Test
    @DisplayName("BA-082-T1 a ticket signed for one owner cannot be consumed by another")
    void anotherOwnersTicketIsNotConsumable() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        var stranger = sessions.bootstrap(null, null, null);
        UUID placeId = place();

        UUID uploadId = issueTicket(author);
        recorder.put(quarantineKeyOf(uploadId), jpeg(64, 48));

        // The stranger knows the id - the only thing standing between them and the object is that
        // the intent is not theirs.
        createPost(stranger, uploadId, placeId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Indistinguishable from an id that never existed: a different refusal would confirm that
        // this one is real (BA-070-T1's standard is indistinguishable, not merely refused).
        createPost(stranger, UUID.randomUUID(), placeId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // And the refusal is about ownership, not about the request being malformed: the owner it
        // belongs to succeeds with the same body. Without this the two assertions above would pass
        // against a route that refuses everything.
        createPost(author, uploadId, placeId).andExpect(status().isCreated());

        assertThat(recorder.published).hasSize(1);
        assertThat(recorder.deleted).contains(quarantineKeyOf(uploadId));
    }

    @Test
    @DisplayName("BA-082-T2 bytes that fail validation reach no published location and no post")
    void refusedBytesArePublishedNowhere() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        long postsBefore = postsAuthoredBy(author);

        UUID uploadId = issueTicket(author);
        // Declared image/jpeg at signing time; what actually arrived is not an image at all.
        recorder.put(quarantineKeyOf(uploadId), "<!doctype html><script>alert(1)</script>"
                .getBytes(StandardCharsets.UTF_8));

        createPost(author, uploadId, placeId).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // The three facts that make "refused" mean something.
        assertThat(recorder.published).as("nothing reached the published location").isEmpty();
        assertThat(postsAuthoredBy(author)).as("no post row").isEqualTo(postsBefore);
        assertThat(recorder.deleted).as("the refused object is deleted where it is refused")
                .contains(quarantineKeyOf(uploadId));
        // And no asset row claims THIS upload, which is A-058's check point spelled out.
        //
        // Scoped to source_external_id rather than counting the table: the gate runs every suite
        // against one database, so "no USER_UPLOAD asset exists" would be an assertion about every
        // test that ran before this one. It was written that way first and failed with 2 - the two
        // posts T1 and T15 legitimately published.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets"
                + " WHERE source_external_id = ?", Long.class, uploadId.toString())).isZero();
    }

    @Test
    @DisplayName("BA-082 bytes with a changed checksum cannot be published even if they decode")
    void changedImageWithTheSameLengthIsRejected() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        byte[] declared = jpeg(64, 48);
        byte[] changed = declared.clone();
        changed[100] ^= 1;
        assertThat(changed).hasSameSizeAs(declared);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(changed))).isNotNull();
        UUID uploadId = issueTicket(author, declared);
        recorder.put(quarantineKeyOf(uploadId), changed);

        createPost(author, uploadId, placeId).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(recorder.published).isEmpty();
        assertThat(postsAuthoredBy(author)).isZero();
        assertThat(recorder.deleted).contains(quarantineKeyOf(uploadId));
    }

    @Test
    @DisplayName("BA-082 declared upload length must match the received bytes exactly")
    void shorterImageThanDeclaredIsRejected() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        byte[] image = jpeg(64, 48);
        String response = createUpload(author, "upload-" + UUID.randomUUID(), image.length + 1,
                sha256(image)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID uploadId = uploadIdOf(response);
        issued.add(uploadId);
        recorder.put(quarantineKeyOf(uploadId), image);

        createPost(author, uploadId, placeId).andExpect(status().isUnprocessableContent());
        assertThat(recorder.published).isEmpty();
        assertThat(postsAuthoredBy(author)).isZero();
    }

    @Test
    @DisplayName("BA-082 an empty place list is refused before an upload ticket is spent")
    void emptyPlaceListIsRejectedBeforeUploadIsSpent() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        UUID uploadId = issueTicket(author);
        recorder.put(quarantineKeyOf(uploadId), jpeg(64, 48));

        mvc.perform(post("/api/v1/posts")
                        .cookie(new Cookie("__Host-nullnull_session", author.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", author.csrf.token)
                        .header("Idempotency-Key", "post-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"uploadId\":\"" + uploadId + "\",\"title\":\"가을 산책\","
                                + "\"body\":\"좋았다\",\"placeIds\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(recorder.published).isEmpty();
        assertThat(recorder.deleted).isEmpty();
        createPost(author, uploadId, placeId).andExpect(status().isCreated());
    }

    @Test
    // This carried BA-082-T3 for a while and should not have: that clause was then
    // "삭제/권리 철회가 기존 cursor·cache에서도 반영된다", which this does not touch. The borrowed id
    // was wrong in both directions - T3 looked covered while nothing tested it, and this property
    // had no card of its own. T15 is the clause that owns it, registered on the backend card. T3 has
    // since been narrowed to its read side (FeedIT), and the withdrawal that feeds it is BA-082-T16.
    @DisplayName("BA-082-T15 one ticket produces at most one post")
    void aTicketIsSpentOnce() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        byte[] image = jpeg(32, 32);
        UUID uploadId = issueTicket(author, image);
        recorder.put(quarantineKeyOf(uploadId), image);

        createPost(author, uploadId, placeId).andExpect(status().isCreated());
        // Same answer as a ticket that never existed: the contract carries one code for both.
        createPost(author, uploadId, placeId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(recorder.published).hasSize(1);
        // The clause counts posts, so this counts post rows too: an object published once says nothing
        // about a second row that reused it. Sequential calls only - two concurrent calls race on the
        // conditional claim, and that race is not measured here.
        assertThat(postsAuthoredBy(author)).as("one post row").isOne();
    }

    @Test
    @DisplayName("BA-082 a lost publish response replays the same post for the same key")
    void sameKeyReplaysPublishedPost() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        byte[] image = jpeg(32, 32);
        UUID uploadId = issueTicket(author, image);
        recorder.put(quarantineKeyOf(uploadId), image);
        String key = "post-" + UUID.randomUUID();

        String first = createPost(author, uploadId, placeId, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = createPost(author, uploadId, placeId, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(recorder.published).hasSize(1);
        assertThat(postsAuthoredBy(author)).isOne();
    }

    @Test
    @DisplayName("BA-082 a publish key cannot be reused with changed text")
    void sameKeyWithDifferentPostIsRejected() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        UUID placeId = place();
        byte[] image = jpeg(32, 32);
        UUID uploadId = issueTicket(author, image);
        recorder.put(quarantineKeyOf(uploadId), image);
        String key = "post-" + UUID.randomUUID();
        createPost(author, uploadId, placeId, key).andExpect(status().isCreated());

        mvc.perform(post("/api/v1/posts")
                        .cookie(new Cookie("__Host-nullnull_session", author.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", author.csrf.token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"uploadId\":\"" + uploadId + "\",\"title\":\"다른 글\","
                                + "\"body\":\"좋았다\",\"altText\":\"단풍\","
                                + "\"placeIds\":[\"" + placeId + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(recorder.published).hasSize(1);
        assertThat(postsAuthoredBy(author)).isOne();
    }

    @Test
    @DisplayName("BA-082 an upload reservation retry returns the same signed ticket")
    void sameKeyReplaysUploadTicket() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        String key = "upload-" + UUID.randomUUID();
        String first = createUpload(author, key, 2048)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = createUpload(author, key, 2048)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID firstId = uploadIdOf(first);
        UUID replayId = uploadIdOf(replay);
        issued.add(firstId);
        if (!replayId.equals(firstId)) issued.add(replayId);
        assertThat(replay).isEqualTo(first);
    }

    @Test
    @DisplayName("BA-082 an upload reservation key cannot be reused with changed length")
    void sameUploadKeyWithDifferentLengthIsRejected() throws Exception {
        var author = sessions.bootstrap(null, null, null);
        String key = "upload-" + UUID.randomUUID();
        String first = createUpload(author, key, 2048)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        issued.add(uploadIdOf(first));
        createUpload(author, key, 2049).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    /**
     * Removes what this class wrote, naming its own rows.
     *
     * <p>Not a blanket delete: the gate shares one database, so a statement that does not point at
     * this test's rows either takes somebody else's fixture with it or dies on a foreign key that
     * another suite's rows hold. places in particular does not cascade - a place must not vanish
     * from under the candidates that reference it - so it needs its own targeted statement.
     *
     * <p>The ORDER is not arbitrary either: a published post is only allowed to exist with exactly
     * one primary place, so its places cannot be taken away first.
     */
    @AfterEach
    void removeWhatThisTestWrote() {
        for (UUID uploadId : issued) {
            // posts first, and post_places NOT separately: they cascade from the post, and removing
            // them on their own leaves a PUBLISHED post with no primary place - which V015's
            // trigger refuses ("a published post must name exactly one primary place"). The guard
            // is right; the cleanup was doing the thing it exists to stop.
            jdbc.update("DELETE FROM posts WHERE cover_asset_id IN"
                    + " (SELECT id FROM media_assets WHERE source_external_id = ?)",
                    uploadId.toString());
            jdbc.update("DELETE FROM media_assets WHERE source_external_id = ?", uploadId.toString());
            jdbc.update("DELETE FROM upload_intents WHERE id = ?", uploadId);
        }
        for (UUID placeId : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
        issued.clear();
        places.clear();
    }

    private final List<UUID> issued = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();

    private UUID issueTicket(SessionService.Bootstrap owner) throws Exception {
        return issueTicket(owner, jpeg(64, 48));
    }

    private UUID issueTicket(SessionService.Bootstrap owner, byte[] image) throws Exception {
        String body = createUpload(owner, "upload-" + UUID.randomUUID(), image.length,
                sha256(image))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID uploadId = uploadIdOf(body);
        issued.add(uploadId);
        return uploadId;
    }

    private org.springframework.test.web.servlet.ResultActions createUpload(
            SessionService.Bootstrap owner, String key, int length) throws Exception {
        return createUpload(owner, key, length, "a".repeat(64));
    }

    private org.springframework.test.web.servlet.ResultActions createUpload(
            SessionService.Bootstrap owner, String key, int length, String checksum) throws Exception {
        return mvc.perform(post("/api/v1/posts/images/uploads")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":" + length
                                + ",\"checksumSha256\":\"" + checksum + "\"}"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static UUID uploadIdOf(String body) {
        return UUID.fromString(
                body.replaceAll(".*\"uploadId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    private org.springframework.test.web.servlet.ResultActions createPost(
            SessionService.Bootstrap owner, UUID uploadId, UUID placeId) throws Exception {
        return createPost(owner, uploadId, placeId, "post-" + UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.ResultActions createPost(
            SessionService.Bootstrap owner, UUID uploadId, UUID placeId, String key) throws Exception {
        return mvc.perform(post("/api/v1/posts")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"uploadId\":\"" + uploadId + "\",\"title\":\"가을 산책\",\"body\":\"좋았다\","
                        + "\"altText\":\"단풍\",\"placeIds\":[\"" + placeId + "\"]}"));
    }

    private String quarantineKeyOf(UUID uploadId) {
        return jdbc.queryForObject("SELECT quarantine_key FROM upload_intents WHERE id = ?",
                String.class, uploadId);
    }

    /**
     * How many posts THIS author has.
     *
     * <p>Not {@code count(*) FROM posts}. The gate runs every suite against one database, so an
     * unscoped aggregate is an assertion about every test that ran before this one - and taking a
     * before/after delta of one does not fix that, it only assumes nothing else wrote in between.
     * The author is an owner this test created moments ago, so naming it names our rows.
     */
    private long postsAuthoredBy(SessionService.Bootstrap author) {
        return jdbc.queryForObject("SELECT count(*) FROM posts WHERE author_owner_id = ?",
                Long.class, author.owner.id());
    }

    private UUID place() {
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '업로드 test 장소', 'HS', '11', 'ACTIVE', now(), now())",
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

    /** Stands in front of {@link io.nullnull.social.infrastructure.storage.UnconfiguredObjectStorage}. */
    @TestConfiguration
    static class RecordingStorage {
        // @Primary rather than replacing the fallback: both definitions exist (the fallback is
        // present precisely because no bucket is configured here), and primary is the part of
        // Spring that settles that without depending on which class was read first.
        @Bean
        @org.springframework.context.annotation.Primary
        ObjectStorage objectStorage() {
            return new Recorder();
        }
    }

    /** Remembers what was asked of it, so assertions can count rather than assume. */
    static final class Recorder implements ObjectStorage {
        private final Map<String, byte[]> quarantine = new ConcurrentHashMap<>();
        final Map<String, byte[]> published = new HashMap<>();
        final List<String> deleted = new ArrayList<>();

        void reset() {
            quarantine.clear();
            published.clear();
            deleted.clear();
        }

        void put(String key, byte[] bytes) {
            quarantine.put(key, bytes);
        }

        @Override
        public PresignedUpload presignQuarantinePut(String key, String contentType,
                long contentLength, Duration ttl) {
            return new PresignedUpload("https://storage.test/" + key, "PUT",
                    Map.of("Content-Type", contentType), Instant.now().plus(ttl));
        }

        @Override
        public byte[] readQuarantined(String key) {
            byte[] bytes = quarantine.get(key);
            if (bytes == null) {
                throw new ObjectNotFoundException("nothing at " + key);
            }
            return bytes;
        }

        @Override
        public String publish(String key, byte[] bytes, String contentType) {
            published.put(key, bytes);
            return "https://cdn.test/" + key;
        }

        @Override
        public void deleteQuarantined(String key) {
            deleted.add(key);
            quarantine.remove(key);
        }
    }
}
