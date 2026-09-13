package io.nullnull.importer.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.catalog.application.CatalogPlaceSearchRequest;
import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.IdempotencyGuard.GuardedResponse;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.importer.domain.ImportDraft;
import io.nullnull.importer.domain.ImportDraftContent;
import io.nullnull.importer.domain.ImportDraftItem;
import io.nullnull.importer.domain.ItineraryParser;
import io.nullnull.importer.domain.UnresolvedToken;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.application.CreateTripCommand;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.application.TripView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * remapTripImport and confirmTripImport: correcting a parsed itinerary, and turning it into a trip.
 *
 * <p><b>The order the refusals are checked in is a decision, not an accident.</b> A draft is read,
 * and then: it is not this owner's (404), its 24 hours are up (410), it has already become a trip
 * (409), the caller's ETag names a version that is no longer current (409). Existence comes first
 * because any other answer would confirm that a draft id belongs to somebody (invariant 11). Expiry
 * comes before status because an expired draft is gone whatever state it was in. Status comes before
 * the version because a CONFIRMED draft cannot be edited at any version - answering "it changed"
 * would send the caller to refetch and retry something that can never succeed.
 *
 * <p><b>Confirm checks twice, and the two checks do different jobs.</b> The first runs before the
 * idempotency guard reserves anything: a request that is going to be refused must not consume the
 * caller's key, and - the case that forced it - an expired draft has to answer 410 even when that key
 * already holds a stored 201 from before the expiry. Replaying a stored success for a resource the
 * contract no longer keeps would be the guard answering for a draft that is gone. The second runs
 * inside the guard's transaction, against the row it has locked, and that is the check that decides:
 * the first is a read of state that anyone could change a microsecond later.
 *
 * <p><b>The pre-check deliberately does not look at CONFIRMED, and remap does.</b> A client whose
 * first confirm timed out retries with the same key, and by then the draft IS confirmed - that is the
 * ordinary replay, and refusing it here would make the header useless on the one command it exists
 * for. Whether a second confirm is a replay or a second attempt is a question only the guard can
 * answer, because the key is the only thing that distinguishes them; the locked re-check is what
 * refuses the one that arrives under a different key. Remap has no such case - it is not retried
 * under a key - so for it a CONFIRMED draft is simply not editable.
 */
@Service
public class TripImportService {

    private static final String PARSE_ROUTE = "POST /trip-imports/parse";
    private static final String CONFIRM_ROUTE = "POST /trip-imports/{draftId}/confirm";

    /** ERD §6: a draft lives 24 hours. The sweep reads the column; every operation compares against it. */
    private static final Duration DRAFT_TTL = Duration.ofHours(24);

    /**
     * The confidence an exactly-one match carries. One, because the catalog matched the line and
     * nothing was guessed - it is not a score the parser produced, and a lower number would be one
     * nobody measured. Anything less certain than this is not an item at all; it is a token.
     */
    private static final BigDecimal RESOLVED = BigDecimal.ONE;
    private static final Pattern IF_MATCH = Pattern.compile("^\"[1-9][0-9]*\"$");

    private final ImportDraftStore drafts;
    private final CatalogPlaceProjectionService places;
    private final TripService trips;
    private final OwnerPreferencesService owners;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public TripImportService(ImportDraftStore drafts, CatalogPlaceProjectionService places,
            TripService trips, OwnerPreferencesService owners, IdempotencyGuard idempotency,
            PlatformTransactionManager transactionManager, ObjectMapper json, Clock clock) {
        this.drafts = drafts;
        this.places = places;
        this.trips = trips;
        this.owners = owners;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
    }

    /**
     * Corrects the mappings in a draft and hands back the whole draft at its new version.
     *
     * <p>The gate is applied before the transaction opens, not inside it: the answer embeds a
     * PlaceSummary for every resolved item, so a closed catalog cannot answer this operation at all,
     * and discovering that after the write would leave the correction stored, the version raised and
     * the caller holding a 503 for a change that happened.
     */
    public ImportDraftView remap(OwnerContext owner, UUID draftId, String ifMatch,
            RemapImportCommand command) {
        long expected = parseIfMatch(ifMatch);
        places.requirePublicProjection();
        return transactions.execute(status -> {
            ImportDraft current = drafts.findForUpdate(owner.ownerId(), draftId)
                    .orElseThrow(TripImportService::notFound);
            requireUsable(current, expected);

            RemapImportCommand.Result applied = command.applyTo(current.content(), current.unresolved());
            ImportDraft next = new ImportDraft(current.id(), current.ownerId(),
                    ImportDraft.statusFor(applied.content(), applied.unresolved()), current.version() + 1,
                    applied.content(), applied.unresolved(), null, null, current.expiresAt(),
                    current.createdAt());
            if (!drafts.saveRemap(next, current.version())) {
                // The row was locked for this transaction, so this cannot be a lost race; it would
                // mean the version this transaction read is not the one it is writing over.
                throw new IllegalStateException("the locked draft did not hold the version it was read at");
            }
            return view(owner, next);
        });
    }

    /**
     * Parses a pasted itinerary into a structured draft, keeping nothing of the paste.
     *
     * <p>The gate is applied first, before a line is read and long before a row is written: the answer
     * embeds a PlaceSummary for every resolved entry, so a closed catalog cannot answer this at all,
     * and a draft written before finding that out would be a row nobody asked for (BA-060-T18).
     *
     * <p>What survives the parse is a line number, a fragment that matched a strict pattern, and ids
     * read back from the catalog. The text itself reaches exactly one place - the catalog's LIKE
     * parameter - and is neither stored nor logged. A line that resolves to one active place becomes
     * an item; one that resolves to several becomes a token carrying those places as suggestions; one
     * that resolves to none becomes a token with an empty label, which is #223's answer for a
     * free-memo line.
     *
     * <p>Nothing is completed on the traveller's behalf. A date with no year stays a token rather than
     * being given this year, and a place that matched nothing stays unresolved rather than being given
     * the nearest name - which is the same rule {@code CuratedHoursPlan} follows one card over.
     */
    public ImportDraftView parse(OwnerContext owner, String idempotencyKey, ParseImportCommand command) {
        places.requirePublicProjection();
        // The contract makes Idempotency-Key required here, and the reason is that parse WRITES: a
        // client that retries a timed-out parse would otherwise hold two drafts of one paste and no
        // way to tell which is which. The fingerprint covers the paste, so the same text retried
        // replays and different text is a reused key rather than a silent second draft - and only the
        // hash is stored, never the text.
        String fingerprint = RequestFingerprint.of("parseTripImport", Map.of(),
                json.writeValueAsString(command)).sha256Hex();
        GuardedResponse guarded = idempotency.execute(owner.ownerId(), PARSE_ROUTE, idempotencyKey,
                fingerprint, () -> new CommandOutcome<>(200, parseOnce(owner, command)), value -> value);
        return get(owner, json.readValue(guarded.body(), ParsedDraft.class).draftId());
    }

    /** What the guard stores for a replay: the draft's identity, never a line of the paste. */
    public record ParsedDraft(UUID draftId) {
    }

    private ParsedDraft parseOnce(OwnerContext owner, ParseImportCommand command) {
        List<ItineraryParser.ParsedLine> lines = ItineraryParser.parse(command.rawText());

        List<ImportDraftItem> items = new ArrayList<>();
        List<UnresolvedToken> unresolved = new ArrayList<>();
        LocalDate current = null;
        LocalDate earliest = null;
        LocalDate latest = null;
        for (ItineraryParser.ParsedLine line : lines) {
            if (items.size() >= ImportDraftContent.MAX_ITEMS
                    && unresolved.size() >= ImportDraftContent.MAX_ITEMS) {
                break;
            }
            switch (line.kind()) {
                case DATE_HEADER -> {
                    current = line.date();
                    earliest = earliest == null || current.isBefore(earliest) ? current : earliest;
                    latest = latest == null || current.isAfter(latest) ? current : latest;
                }
                case AMBIGUOUS_DATE -> add(unresolved, new UnresolvedToken(key("t", line.line()),
                        UnresolvedToken.Kind.DATE, line.line(), line.label(), List.of()));
                case PLACE -> {
                    List<CatalogPlaceSummary> matches = resolve(owner, line, command.locale());
                    if (matches.size() == 1 && items.size() < ImportDraftContent.MAX_ITEMS) {
                        items.add(new ImportDraftItem(key("i", line.line()), matches.getFirst().id(),
                                // No originalLabel. The only two things it could hold are the person's
                                // own words, which must not be stored, and the catalog's name, which
                                // the embedded place already carries.
                                null, current, line.startTime(), items.size(), RESOLVED));
                    } else {
                        add(unresolved, new UnresolvedToken(key("t", line.line()),
                                UnresolvedToken.Kind.PLACE, line.line(), "",
                                matches.stream().map(CatalogPlaceSummary::id).toList()));
                    }
                }
                default -> throw new IllegalStateException("unhandled parsed line kind");
            }
        }

        Instant now = clock.instant();
        ImportDraftContent content = new ImportDraftContent(null, earliest, latest, command.timezone(),
                List.copyOf(items));
        ImportDraft draft = new ImportDraft(UUID.randomUUID(), owner.ownerId(),
                ImportDraft.statusFor(content, unresolved), 1L, content, List.copyOf(unresolved), null,
                null, now.plus(DRAFT_TTL), now);
        drafts.insert(draft);
        return new ParsedDraft(draft.id());
    }

    /**
     * The catalog's answer for one line, capped at the contract's suggestion limit.
     *
     * <p>Eleven are asked for so that "more than ten matched" is distinguishable from "ten matched";
     * either way the person is choosing, and a line that matches eleven places is not resolved.
     */
    private List<CatalogPlaceSummary> resolve(OwnerContext owner, ItineraryParser.ParsedLine line,
            String locale) {
        if (line.lookup() == null) {
            return List.of();
        }
        List<CatalogPlaceSummary> found = places.search(owner, CatalogPlaceSearchRequest.of(line.lookup(),
                locale, null, null, UnresolvedToken.MAX_SUGGESTIONS + 1)).items();
        return found.size() > UnresolvedToken.MAX_SUGGESTIONS
                ? found.subList(0, UnresolvedToken.MAX_SUGGESTIONS)
                : found;
    }

    private static void add(List<UnresolvedToken> tokens, UnresolvedToken token) {
        if (tokens.size() < ImportDraftContent.MAX_ITEMS) {
            tokens.add(token);
        }
    }

    /** Derived from the line number alone, so no part of the paste reaches a client key. */
    private static String key(String prefix, int line) {
        return prefix + line;
    }

    /** The draft as it stands, for a caller that only wants to look at it. */
    public ImportDraftView get(OwnerContext owner, UUID draftId) {
        places.requirePublicProjection();
        ImportDraft draft = drafts.find(owner.ownerId(), draftId).orElseThrow(TripImportService::notFound);
        requireAlive(draft);
        return view(owner, draft);
    }

    /**
     * Turns a READY draft into a trip, once.
     *
     * <p>Two different things make "once" true, against two different races. The same key twice is the
     * idempotency guard replaying its stored answer. Two <em>different</em> keys at the same moment is
     * the row lock: the loser waits on {@code findForUpdate}, reads CONFIRMED when the winner commits
     * and is refused. The guard cannot help there - different keys are different slots - and the
     * {@code confirmed_trip_id} unique index behind it would only refuse the second write after a trip
     * had already been created.
     *
     * <p>What the guard stores is the trip's id, not the trip. A replay re-reads the trip, so the
     * answer is the trip as it stands rather than a copy taken at confirm time that nothing keeps in
     * step with later edits.
     */
    public TripView confirm(OwnerContext owner, UUID draftId, String ifMatch, String idempotencyKey,
            ConfirmImportCommand command) {
        long expected = parseIfMatch(ifMatch);
        places.requirePublicProjection();
        ImportDraft seen = drafts.find(owner.ownerId(), draftId).orElseThrow(TripImportService::notFound);
        requireAlive(seen);
        requireVersion(seen, expected);

        String fingerprint = new RequestFingerprint("confirmTripImport",
                Map.of("draftId", draftId.toString()), json.writeValueAsString(command), ifMatch)
                .sha256Hex();
        GuardedResponse guarded = idempotency.execute(owner.ownerId(), CONFIRM_ROUTE, idempotencyKey,
                fingerprint, () -> new CommandOutcome<>(201, confirmOnce(owner, draftId, expected, command)),
                value -> value);
        ConfirmedTrip confirmed = json.readValue(guarded.body(), ConfirmedTrip.class);
        return trips.get(owner, confirmed.tripId());
    }

    /** What the guard stores for replay: the identity of the trip, and nothing that can go stale. */
    public record ConfirmedTrip(UUID tripId) {
    }

    private ConfirmedTrip confirmOnce(OwnerContext owner, UUID draftId, long expected,
            ConfirmImportCommand command) {
        ImportDraft draft = drafts.findForUpdate(owner.ownerId(), draftId)
                .orElseThrow(TripImportService::notFound);
        requireUsable(draft, expected);
        if (draft.status() != ImportDraft.Status.READY) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "This draft still has entries waiting for an answer.");
        }
        CreateTripCommand create = command.toCreateTrip(draft, owners.get(owner).locale());
        TripView created = trips.createWithinCallersGuard(owner, create);
        drafts.markConfirmed(draftId, created.trip().id(), clock.instant());
        return new ConfirmedTrip(created.trip().id());
    }

    private ImportDraftView view(OwnerContext owner, ImportDraft draft) {
        return ImportDraftView.of(draft, places, owner);
    }

    private void requireUsable(ImportDraft draft, long expected) {
        requireAlive(draft);
        requireNotConfirmed(draft);
        requireVersion(draft, expected);
    }

    private static void requireNotConfirmed(ImportDraft draft) {
        if (draft.status() == ImportDraft.Status.CONFIRMED) {
            throw new ApiException(ProblemCode.IMPORT_DRAFT_CHANGED,
                    "This draft has already become a trip.");
        }
    }

    private static void requireVersion(ImportDraft draft, long expected) {
        if (draft.version() != expected) {
            throw new ApiException(ProblemCode.IMPORT_DRAFT_CHANGED, "The draft was modified elsewhere.");
        }
    }

    private void requireAlive(ImportDraft draft) {
        Instant now = clock.instant();
        if (draft.expiredAt(now) || draft.status() == ImportDraft.Status.EXPIRED) {
            // 410 rather than 404: the id was real and the caller may have a screen open on it. The
            // stored EXPIRED status and the passed instant are the same fact seen before and after
            // the sweep has run, and neither is allowed to answer differently from the other.
            throw new ApiException(ProblemCode.IMPORT_DRAFT_EXPIRED, "This import draft has expired.");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The requested import draft is unavailable.");
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || !IF_MATCH.matcher(ifMatch).matches()) {
            // 400 rather than 412: a malformed header is a request the server cannot interpret, while
            // a well-formed one that lost the race is a conflict the caller can resolve by refetching.
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "If-Match must be the quoted import draft version.");
        }
        return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
    }
}
