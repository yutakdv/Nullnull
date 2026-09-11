package io.nullnull.crowd.api;

import io.nullnull.crowd.application.CrowdForecastProjectionService;
import io.nullnull.crowd.application.CrowdForecastProjectionService.CrowdSeries;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Approved getPlaceCrowdForecast operation; only normalized local snapshot sets are projected. */
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
}
