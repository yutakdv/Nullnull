package io.nullnull.trip.api;

import io.nullnull.catalog.api.PlaceController.PlaceSummaryResponse;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.trip.application.TripDraftPreviewService;
import io.nullnull.trip.application.TripDraftPreviewView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * previewTripDraft. A read-only POST like searchPlaces: it changes nothing, so it takes neither a CSRF
 * token nor an Idempotency-Key, and the body keeps the dates out of URL logs. It lives outside
 * {@code /trips/} because no trip exists yet - the path would otherwise read as a trip id.
 *
 * <p>{@code Cache-Control: private, no-store} is set by {@code SessionHttpConfiguration} for every
 * session operation, which is where this response gets it; it is not repeated here so the header has
 * one producer.
 */
@RestController
public class TripDraftController {

    private final TripDraftPreviewService drafts;

    public TripDraftController(TripDraftPreviewService drafts) {
        this.drafts = drafts;
    }

    @PostMapping(value = "/trip-drafts/preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "previewTripDraft", security = Security.SESSION)
    public TripDraftPreviewResponse preview(OwnerContext owner, @RequestBody PreviewTripDraftBody body) {
        return TripDraftPreviewResponse.from(drafts.preview(owner,
                body == null ? null : body.startDate(), body == null ? null : body.endDate(),
                body == null ? null : body.timezone()));
    }

    public record PreviewTripDraftBody(LocalDate startDate, LocalDate endDate, String timezone) { }

    public record TripDraftPreviewResponse(String state, Instant evaluatedAt, String policyVersion,
            List<String> basis, List<TripDraftDayResponse> days, List<String> reasons) {
        static TripDraftPreviewResponse from(TripDraftPreviewView view) {
            return new TripDraftPreviewResponse(view.state(), view.evaluatedAt(), view.policyVersion(),
                    view.basis(), view.days().stream().map(TripDraftDayResponse::from).toList(), view.reasons());
        }
    }

    public record TripDraftDayResponse(LocalDate date, List<TripDraftStopResponse> stops) {
        static TripDraftDayResponse from(TripDraftPreviewView.Day day) {
            return new TripDraftDayResponse(day.date(), day.stops().stream().map(TripDraftStopResponse::from).toList());
        }
    }

    public record TripDraftStopResponse(PlaceSummaryResponse place, LocalDate date, int position,
            String hoursState) {
        static TripDraftStopResponse from(TripDraftPreviewView.Stop stop) {
            return new TripDraftStopResponse(PlaceSummaryResponse.from(stop.place()), stop.date(), stop.position(),
                    stop.hoursState());
        }
    }
}
