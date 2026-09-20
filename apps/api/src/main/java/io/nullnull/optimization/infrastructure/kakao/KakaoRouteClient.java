package io.nullnull.optimization.infrastructure.kakao;

import io.nullnull.optimization.application.RouteMatrixGateway;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Pair;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Infrastructure adapter for Kakao Mobility car directions; the only route operation callable here.
 *
 * <p><b>One call per ordered pair.</b> The provider has no N&times;N matrix operation. The two batch
 * shapes it does have (multi-destination 1-&gt;N, multi-origin N-&gt;1) are POST with a JSON body,
 * which this codebase's provider transport cannot send, and they buy a whole row whether or not the
 * caller needs it. 1-&gt;1 is a GET, costs only the pairs actually asked about, and has the larger
 * free tier (10,000/day against 1,000).
 *
 * <p><b>Requests go out one at a time.</b> {@link ProviderHttpClient} guards each source with a
 * semaphore that <em>rejects</em> rather than queues, so firing a whole matrix at once would turn a
 * concurrency limit into failed legs: with four permits, the fifth concurrent request is a
 * {@code CAPACITY} failure, not a wait. Sequential dispatch is the one shape a single caller cannot
 * do that to itself with. The cost is wall-clock, and this runs inside a job whose lease is extended
 * by a heartbeat every third of a lease, so there is no deadline to beat.
 *
 * <p><b>Nothing is stored.</b> A-055 forbids persisting a Kakao response, so there is no snapshot
 * type for this source - which is why the source code lives on this adapter rather than on a domain
 * record the way {@code KtoPlaceSnapshot.SOURCE_CODE} does.
 */
@Component
public class KakaoRouteClient implements RouteMatrixGateway {

    /**
     * Names the product and the operation, not just the company. A-051 records why: Kakao Map and
     * Kakao Mobility are different products with different hosts, terms and quotas, and a shared
     * label is how one product's free tier gets cited as evidence for the other's.
     */
    public static final String SOURCE_CODE = "KAKAO_MOBILITY_DIRECTIONS";

    private final ProviderHttpClient provider;
    private final KakaoRouteProperties properties;
    private final JsonMapper json;
    private final boolean testEndpointAllowed;

    public KakaoRouteClient(ProviderHttpClient provider, KakaoRouteProperties properties,
            @Value("${nullnull.env}") String environment) {
        this.provider = provider;
        this.properties = properties;
        this.json = JsonMapper.builder().build();
        this.testEndpointAllowed = "test".equals(environment);
    }

    @Override
    public DirectedRouteMatrix legsFor(Collection<RouteLeg> legs) {
        Map<Pair, Duration> available = new LinkedHashMap<>();
        for (RouteLeg leg : legs) {
            if (Thread.currentThread().isInterrupted()) {
                // Stop asking rather than spend the rest of the matrix on a cancelled attempt. The
                // legs not reached stay Absent, which is what they are: nobody asked.
                break;
            }
            travelTime(leg).ifPresent(
                    duration -> available.put(new Pair(leg.from().key(), leg.to().key()), duration));
        }
        // The unavailable set is empty on purpose and BA-083-T23 pins that it is. Leg.Unavailable
        // means "the provider answered for this pair and said there is no route", and naming it
        // requires knowing which result_code values mean that. The one live call this design rests on
        // succeeded, so that vocabulary is unconfirmed - and calling an unrecognised failure "there
        // is no road" would be inventing the provider's answer. Absent is the weaker, true statement.
        return DirectedRouteMatrix.of(available, Set.of());
    }

    /**
     * Empty for any leg this adapter could not turn into a duration it stands behind - a refused
     * request, a transport failure, a non-success {@code result_code}, a body that does not carry the
     * field, or a negative value. The caller cannot act differently on those, and
     * {@code RouteFeasibility} stops the walk at the first gap either way.
     */
    private Optional<Duration> travelTime(RouteLeg leg) {
        ProviderResponse response;
        try {
            response = provider.get(SOURCE_CODE,
                            properties.directionsUri(leg.from(), leg.to(), testEndpointAllowed),
                            properties.authorizationHeader(testEndpointAllowed))
                    .join();
        } catch (RuntimeException failure) {
            // Not IllegalStateException: that one is this gateway saying it has no credential or no
            // approved endpoint, and swallowing it would turn a deployment error into an outage that
            // looks like the provider's.
            if (failure instanceof IllegalStateException) {
                throw failure;
            }
            return Optional.empty();
        }
        return parse(response.body());
    }

    private Optional<Duration> parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
        JsonNode route = root.path("routes").path(0);
        JsonNode code = route.path("result_code");
        if (!code.isNumber() || code.intValue() != 0) {
            return Optional.empty();
        }
        JsonNode duration = route.path("summary").path("duration");
        if (!duration.isNumber()) {
            return Optional.empty();
        }
        // Seconds. Measured 2026-09-20: 6308 m answered 1114, which is 18.6 minutes across central
        // Seoul. A negative value is refused here rather than at Leg.Available's constructor, so a
        // provider defect becomes one Absent leg instead of an exception out of the whole matrix.
        long seconds = duration.longValue();
        return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    }
}
