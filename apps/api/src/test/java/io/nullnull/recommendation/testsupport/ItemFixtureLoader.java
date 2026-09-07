package io.nullnull.recommendation.testsupport;

import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.LockIn;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.trip.domain.LockType;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the ITEM fixture corpus owned by {@code apps/ai} so the Java safety checks are judged by the
 * same files as the Python evaluator (drift defence, REC-CI-4). This mirrors
 * {@code apps/ai/src/nullnull_ai/evaluation/fixtures.py}: the candidate {@code placeId} defaults to
 * the target's, an opening window is {@code {open, close}} or the strings {@code CLOSED}/{@code
 * UNKNOWN}, and the nested {@code verdict} object becomes the flat {@code verdictEligible} /
 * {@code verdictReasonCode} pair of {@link TemporalCandidateIn}. Fixture bytes are checked against
 * the manifest checksum, so an edited fixture fails loudly instead of testing another scenario.
 */
public final class ItemFixtureLoader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One {@code expected.proposals[]} row; the decimals stay strings, exactly as the fixture writes them. */
    public record ExpectedProposal(LocalDate date, LocalTime startTime, String score, String improvement,
            String changeCost) {
    }

    /** The complete {@code expected} block: nothing here is a subset of the fixture. */
    public record Expected(ItemProposeResponse.Outcome outcome, boolean expectProposals,
            List<ExpectedProposal> proposals, Map<String, Integer> rejectedByReason) {
    }

    /** One ITEM fixture: its manifest identity, the request the service would receive, and the expectation. */
    public record ItemFixture(String id, String path, ItemProposeRequest request, Expected expected) {
    }

    private ItemFixtureLoader() {
    }

    /**
     * Every {@code kind == "ITEM"} fixture listed in {@code apps/ai/tests/recommendation/manifest.json},
     * in manifest order. The manifest sits next to the fixture directory named by the system property
     * {@code nullnull.ai.fixtures.path}.
     */
    public static List<ItemFixture> loadItemFixtures() {
        String directory = System.getProperty("nullnull.ai.fixtures.path");
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("system property nullnull.ai.fixtures.path is not set");
        }
        Path manifestPath = Path.of(directory).getParent().resolve("manifest.json");
        JsonNode manifest = read(manifestPath);
        // manifest.fixtures[].path is relative to apps/ai (tests/recommendation/manifest.json -> apps/ai).
        Path serviceRoot = manifestPath.getParent().getParent().getParent();
        List<ItemFixture> fixtures = new ArrayList<>();
        for (JsonNode entry : manifest.get("fixtures")) {
            if (!"ITEM".equals(entry.get("kind").asString())) {
                continue;
            }
            String path = entry.get("path").asString();
            Path file = serviceRoot.resolve(path);
            verifyChecksum(file, entry.get("sha256").asString());
            JsonNode document = read(file);
            if (!entry.get("id").asString().equals(document.get("id").asString())) {
                throw new IllegalStateException(path + " declares another id than the manifest");
            }
            fixtures.add(new ItemFixture(entry.get("id").asString(), path,
                    requestOf(document), expectedOf(document.get("expected"))));
        }
        return List.copyOf(fixtures);
    }

    private static ItemProposeRequest requestOf(JsonNode document) {
        JsonNode input = document.get("input");
        TargetItemIn target = targetOf(input.get("target"));
        List<LockIn> locks = new ArrayList<>();
        for (JsonNode lock : input.get("locks")) {
            locks.add(lockOf(lock));
        }
        List<NeighbourItemIn> neighbours = new ArrayList<>();
        for (JsonNode neighbour : input.get("neighbours")) {
            neighbours.add(new NeighbourItemIn(uuid(neighbour.get("itemId")), date(neighbour.get("date")),
                    neighbour.get("position").asInt(), time(neighbour.get("startTime")),
                    optionalInt(neighbour.get("durationMinutes"))));
        }
        Map<LocalDate, OpeningWindowIn> openingHours = new LinkedHashMap<>();
        input.get("openingHours").properties()
                .forEach(entry -> openingHours.put(LocalDate.parse(entry.getKey()), windowOf(entry.getValue())));
        List<TemporalCandidateIn> candidates = new ArrayList<>();
        for (JsonNode candidate : input.get("candidates")) {
            candidates.add(candidateOf(candidate, target.placeId()));
        }
        return new ItemProposeRequest(Instant.parse(document.get("fixedClock").asString()),
                uuid(input.get("tripId")), input.get("tripVersion").asInt(), date(input.get("tripStart")),
                date(input.get("tripEnd")), input.get("tripZone").asString(), target, locks, neighbours, openingHours,
                ItemProposeRequest.RouteEvidence.valueOf(input.get("routeEvidence").asString()), candidates);
    }

    private static TargetItemIn targetOf(JsonNode target) {
        return new TargetItemIn(uuid(target.get("itemId")), uuid(target.get("placeId")), date(target.get("date")),
                time(target.get("startTime")), optionalInt(target.get("durationMinutes")),
                target.get("position").asInt());
    }

    private static LockIn lockOf(JsonNode lock) {
        LockType type = LockType.valueOf(lock.get("type").asString());
        return switch (type) {
            case MUST_VISIT -> LockIn.mustVisit();
            case DATE -> LockIn.date(date(lock.get("date")));
            case TIME -> LockIn.time(time(lock.get("startTime")), lock.get("toleranceMinutes").asInt());
            case RESERVATION -> LockIn.reservation(date(lock.get("date")), time(lock.get("startTime")),
                    time(lock.get("endTime")));
        };
    }

    private static OpeningWindowIn windowOf(JsonNode window) {
        if (window.isString()) {
            return switch (window.asString()) {
                case "CLOSED" -> OpeningWindowIn.closed();
                case "UNKNOWN" -> OpeningWindowIn.unknown();
                default -> throw new IllegalStateException("opening window must be CLOSED, UNKNOWN or a window");
            };
        }
        return OpeningWindowIn.open(LocalTime.parse(window.get("open").asString()),
                LocalTime.parse(window.get("close").asString()));
    }

    private static TemporalCandidateIn candidateOf(JsonNode candidate, UUID defaultPlaceId) {
        JsonNode verdict = candidate.get("verdict");
        UUID placeId = candidate.has("placeId") ? uuid(candidate.get("placeId")) : defaultPlaceId;
        return new TemporalCandidateIn(placeId, date(candidate.get("date")), time(candidate.get("time")),
                TemporalCandidateIn.ForecastResolution.valueOf(candidate.get("resolution").asString()),
                new BigDecimal(candidate.get("beforeValue").asString()),
                new BigDecimal(candidate.get("afterValue").asString()),
                candidate.get("metricCode").asString(), verdict.get("eligible").asBoolean(),
                verdict.get("reasonCode").asString(), uuid(candidate.get("beforeSnapshotId")),
                uuid(candidate.get("afterSnapshotId")));
    }

    private static Expected expectedOf(JsonNode expected) {
        List<ExpectedProposal> proposals = new ArrayList<>();
        for (JsonNode proposal : expected.get("proposals")) {
            proposals.add(new ExpectedProposal(date(proposal.get("date")), time(proposal.get("startTime")),
                    proposal.get("score").asString(), proposal.get("improvement").asString(),
                    proposal.get("changeCost").asString()));
        }
        Map<String, Integer> rejected = new LinkedHashMap<>();
        expected.get("rejectedByReason").properties()
                .forEach(entry -> rejected.put(entry.getKey(), entry.getValue().asInt()));
        return new Expected(ItemProposeResponse.Outcome.valueOf(expected.get("outcome").asString()),
                expected.get("expectProposals").asBoolean(), List.copyOf(proposals), Map.copyOf(rejected));
    }

    private static void verifyChecksum(Path file, String expected) {
        try {
            byte[] raw = Files.readAllBytes(file);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            if (!digest.equals(expected)) {
                throw new IllegalStateException(file + " sha256 " + digest + " does not match manifest " + expected);
            }
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static JsonNode read(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static UUID uuid(JsonNode node) {
        return UUID.fromString(node.asString());
    }

    private static LocalDate date(JsonNode node) {
        return LocalDate.parse(node.asString());
    }

    private static LocalTime time(JsonNode node) {
        return node == null || node.isNull() ? null : LocalTime.parse(node.asString());
    }

    private static Integer optionalInt(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }
}
