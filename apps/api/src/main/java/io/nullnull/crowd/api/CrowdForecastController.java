package io.nullnull.crowd.api;

import io.nullnull.crowd.application.CrowdForecastProjectionService;
import io.nullnull.crowd.application.CrowdForecastProjectionService.CrowdSeries;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Approved getPlaceCrowdForecast and queryPlaceCrowdForecasts; only normalized local snapshot sets are projected. */
@RestController
public class CrowdForecastController {

    private final CrowdForecastProjectionService forecasts;

    public CrowdForecastController(CrowdForecastProjectionService forecasts) {
        this.forecasts = forecasts;
    }

    @GetMapping(value = "/places/{placeId}/crowd-forecast", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "getPlaceCrowdForecast", security = Security.SESSION)
    public CrowdSeries forecast(OwnerContext owner, @PathVariable UUID placeId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return forecasts.forecast(owner, placeId, from, to);
    }

    /**
     * queryPlaceCrowdForecasts (#105). A read-only POST like searchPlaces: it changes nothing, so it
     * takes neither a CSRF token nor an Idempotency-Key, and fifty place ids stay out of URL logs.
     * {@code Cache-Control: private, no-store} comes from {@code SessionHttpConfiguration}, as it does
     * for every session operation.
     */
    @PostMapping(value = "/places/crowd-forecasts/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "queryPlaceCrowdForecasts", security = Security.SESSION)
    public PlaceCrowdForecastQueryResult query(OwnerContext owner, @RequestBody PlaceCrowdForecastQueryBody body) {
        return new PlaceCrowdForecastQueryResult(forecasts.forecastMany(owner,
                body == null ? null : body.placeIds(), body == null ? null : body.from(),
                body == null ? null : body.to()));
    }

    public record PlaceCrowdForecastQueryBody(List<UUID> placeIds, Instant from, Instant to) { }

    public record PlaceCrowdForecastQueryResult(List<CrowdSeries> items) { }
}
