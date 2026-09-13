package io.nullnull.importer.api;

import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.importer.application.ConfirmImportCommand;
import io.nullnull.importer.application.ImportDraftView;
import io.nullnull.importer.application.RemapImportCommand;
import io.nullnull.importer.application.TripImportService;
import io.nullnull.importer.domain.ImportDraft;
import io.nullnull.importer.domain.ImportDraftItem;
import io.nullnull.importer.domain.UnresolvedToken;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.trip.api.TripController.TripDetailResponse;
import io.nullnull.trip.application.TripView;
import io.nullnull.trip.domain.TripInterest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * remapTripImport and confirmTripImport.
 *
 * <p>{@code parseTripImport} is not here. It is the operation that turns a paste into tokens, and
 * what a token may carry is open in #223: the contract makes {@code UnresolvedImportToken.label} a
 * required free string, which is precisely where a pasted free-text line would land, and this card's
 * safety boundary names free notes and contact details as things that must not survive parsing.
 * Shipping a parser against the shape that is under revision would write those into a jsonb column
 * and a response before the revision lands, and BA-060-T1 is the check that would then be failing
 * against stored rows rather than against a design. The two operations that act on a draft do not
 * depend on that answer, so they are here.
 *
 * <p>The owner comes from {@link OwnerContext}. No route takes a draft id without it: an id alone
 * never identifies a row the caller may see (invariant 11), which is why another owner's draft is a
 * 404 and not a 403 - a 403 would confirm that the id exists.
 */
@RestController
public class TripImportController {

    /** The contract's {@code ^([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$}: seconds are always written. */
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TripImportService imports;

    public TripImportController(TripImportService imports) {
        this.imports = imports;
    }

    @PatchMapping(value = "/trip-imports/{draftId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "remapTripImport", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<ImportDraftResponse> remap(OwnerContext owner, @PathVariable UUID draftId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody RemapImportBody body) {
        ImportDraftView view = imports.remap(owner, draftId, ifMatch, command(body));
        return ResponseEntity.ok()
                .eTag("\"" + view.draft().version() + "\"")
                .header("Cache-Control", "private, no-store")
                .body(ImportDraftResponse.from(view));
    }

    @PostMapping(value = "/trip-imports/{draftId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "confirmTripImport", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<TripDetailResponse> confirm(OwnerContext owner, @PathVariable UUID draftId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfirmImportBody body) {
        TripView created = imports.confirm(owner, draftId, ifMatch, idempotencyKey,
                new ConfirmImportCommand(body.title(), body.planningLevel(), interests(body.interests())));
        TripDetailResponse response = TripDetailResponse.from(created);
        return ResponseEntity.created(java.net.URI.create("/api/v1/trips/" + response.id()))
                .eTag(created.trip().entityTag())
                .header("Cache-Control", "private, no-store")
                .body(response);
    }

    private static RemapImportCommand command(RemapImportBody body) {
        List<RemapImportCommand.Update> updates = new ArrayList<>();
        if (body != null && body.updates() != null) {
            for (RemapUpdateBody update : body.updates()) {
                if (update == null) {
                    throw new io.nullnull.shared.problem.ApiException(
                            io.nullnull.shared.problem.ProblemCode.INVALID_REQUEST,
                            "An update entry is missing.");
                }
                if (update.constraints() != null) {
                    // The same refusal listRelatedPlaces gives an unread filter: a draft stores no
                    // locks yet, because nothing parses one. Accepting them here would answer 200
                    // with a draft that silently dropped what the caller just sent.
                    throw new io.nullnull.shared.problem.ApiException(
                            io.nullnull.shared.problem.ProblemCode.INVALID_REQUEST,
                            "This operation does not read constraints yet.");
                }
                updates.add(new RemapImportCommand.Update(update.clientKey(), update.placeId(),
                        update.date(), update.startTime(), update.position()));
            }
        }
        return new RemapImportCommand(updates);
    }

    private static List<TripInterest> interests(List<TripInterestBody> submitted) {
        if (submitted == null) {
            return List.of();
        }
        return submitted.stream()
                .map(interest -> new TripInterest(interest.code(), interest.weight()))
                .toList();
    }

    /** {@code RemapImportRequest}. */
    public record RemapImportBody(List<RemapUpdateBody> updates) { }

    public record RemapUpdateBody(String clientKey, UUID placeId, LocalDate date, LocalTime startTime,
            Integer position, List<Object> constraints) { }

    /** {@code ConfirmImportRequest}. */
    public record ConfirmImportBody(String title, String planningLevel,
            List<TripInterestBody> interests) { }

    public record TripInterestBody(String code, int weight) { }

    /** {@code ImportDraft}. */
    public record ImportDraftResponse(UUID id, long version, String status, String title,
            DateRangeResponse dates, List<ImportDraftItemResponse> items,
            List<UnresolvedImportTokenResponse> unresolved, Instant expiresAt) {

        static ImportDraftResponse from(ImportDraftView view) {
            ImportDraft draft = view.draft();
            List<ImportDraftItemResponse> items = new ArrayList<>(draft.content().items().size());
            for (ImportDraftItem item : draft.content().items()) {
                CatalogPlaceSummary place = view.place(item.placeId());
                items.add(new ImportDraftItemResponse(item.clientKey(),
                        place == null ? null : PlaceSummaryResponse.from(place), item.originalLabel(),
                        item.date(), wallClock(item.startTime()), item.position(), item.confidence()));
            }
            List<UnresolvedImportTokenResponse> unresolved = new ArrayList<>(draft.unresolved().size());
            for (UnresolvedToken token : draft.unresolved()) {
                List<PlaceSummaryResponse> suggestions = new ArrayList<>();
                for (UUID suggestion : token.suggestionPlaceIds()) {
                    CatalogPlaceSummary summary = view.place(suggestion);
                    if (summary != null) {
                        suggestions.add(PlaceSummaryResponse.from(summary));
                    }
                }
                unresolved.add(new UnresolvedImportTokenResponse(token.clientKey(), token.kind().name(),
                        token.label(), List.copyOf(suggestions)));
            }
            return new ImportDraftResponse(draft.id(), draft.version(), draft.status().name(),
                    draft.content().title(),
                    new DateRangeResponse(draft.content().startDate(), draft.content().endDate()),
                    List.copyOf(items), List.copyOf(unresolved), draft.expiresAt());
        }
    }

    public record DateRangeResponse(LocalDate startDate, LocalDate endDate) { }

    /**
     * {@code ImportDraftItem}. No {@code constraints} field: the schema allows one and nothing
     * produces it, and a field serialised as null would not even be valid against it.
     */
    public record ImportDraftItemResponse(String clientKey, PlaceSummaryResponse place,
            String originalLabel, LocalDate date, String startTime, int position,
            BigDecimal confidence) { }

    public record UnresolvedImportTokenResponse(String clientKey, String kind, String label,
            List<PlaceSummaryResponse> suggestions) { }

    private static String wallClock(LocalTime value) {
        return value == null ? null : WALL_CLOCK.format(value);
    }
}
