package io.nullnull.live.api;

import io.nullnull.live.application.LiveAreaProjection.LiveAreaResultResponse;
import io.nullnull.live.application.LiveAreaQueryService;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** queryLiveAreas. The other two Live operations have no route yet. */
@RestController
public class LiveController {

    private final LiveAreaQueryService areas;

    public LiveController(LiveAreaQueryService areas) {
        this.areas = areas;
    }

    /**
     * A read-only POST for the same reason {@code searchPlaces} and {@code queryPlaceCrowdForecasts}
     * are: it changes nothing, so it carries neither CSRF token nor Idempotency-Key, and the coarse
     * viewport stays out of the access log's URL. {@code Cache-Control: private, no-store} comes from
     * {@code SessionHttpConfiguration} like every session operation's.
     *
     * <p>The body is taken as BigDecimal and never as double: the contract's coordinates are decimal
     * to three places and {@code CoarseViewport} refuses a fourth, which is a question binary
     * floating point cannot be asked.
     */
    @PostMapping(value = "/live/areas", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "queryLiveAreas", security = Security.SESSION)
    public LiveAreaResultResponse query(@RequestBody(required = false) LiveAreaQueryBody body) {
        ViewportBody viewport = body == null ? null : body.viewport();
        return areas.query(body == null ? null : body.mode(), body == null ? null : body.regionCode(),
                viewport == null ? null : viewport.west(), viewport == null ? null : viewport.south(),
                viewport == null ? null : viewport.east(), viewport == null ? null : viewport.north());
    }

    public record LiveAreaQueryBody(String mode, String regionCode, ViewportBody viewport) { }

    public record ViewportBody(BigDecimal west, BigDecimal south, BigDecimal east, BigDecimal north) { }
}
