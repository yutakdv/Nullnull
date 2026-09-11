package io.nullnull.recommendation.infrastructure;

import io.nullnull.operations.application.ReadinessProbe;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.shared.http.RequestIdFilter;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Wires the two clients to apps/ai: the gateway with the configured budget, the probe with its own. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecommendationClientProperties.class)
public class RecommendationClientConfiguration {

    /** The readiness probe answers load balancer health checks, so it never waits on the gateway budget. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    RecommendationGateway recommendationGateway(RestClient.Builder builder, RecommendationClientProperties properties,
            JsonMapper json) {
        RestClient client = client(builder, properties.baseUrl(), properties.connectTimeout(), properties.readTimeout(),
                json);
        return new HttpRecommendationGateway(client, RecommendationClientConfiguration::currentRequestId);
    }

    @Bean
    ReadinessProbe recommendationServiceProbe(RestClient.Builder builder, RecommendationClientProperties properties,
            JsonMapper json) {
        return new RecommendationServiceProbe(
                client(builder, properties.baseUrl(), PROBE_TIMEOUT, PROBE_TIMEOUT, json));
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
            Duration readTimeout, JsonMapper json) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory)
                .configureMessageConverters(converters ->
                        converters.withJsonConverter(new JacksonJsonHttpMessageConverter(tolerantOfNewFields(json))))
                .build();
    }

    /**
     * The apps/ai response mapper, and the ONLY place unknown-field strictness is relaxed.
     *
     * <p>{@code spring.jackson.deserialization.fail-on-unknown-properties} is on because the PUBLIC
     * contract promises closed request schemas, and it must stay on for inbound requests. But the same
     * Boot-managed mapper reaches the internal gateway through {@code RestClient.Builder}, and there
     * the identical setting is a deploy-order hazard rather than a guarantee: apps/ai and apps/api roll
     * out as separate ECS services, so an ordinary rolling deploy runs a new apps/ai carrying one
     * additive response field against an apps/api that has not been redeployed yet. Strict parsing
     * would fail conversion on every {@code rankFeed}, {@code proposeItem} and {@code evaluateSlots}
     * for the length of the rollout - a fleet-wide fallback whose only trace is a {@code log.warn}.
     *
     * <p>This does NOT weaken the internal contract. Response identifiers, caps, ordering and state
     * are checked field by field in {@link HttpRecommendationGateway}, and DTO parity with the frozen
     * contract is asserted by the {@code recommendationTest} suite; a field that goes MISSING still
     * fails, because the records require it. Only an ADDED field is tolerated.
     */
    private static JsonMapper tolerantOfNewFields(JsonMapper json) {
        // rebuild() keeps every module and setting Boot configured; only this one feature is flipped.
        return json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    /** Worker (job) contexts have no servlet request; the call still carries the filter's placeholder id. */
    private static String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                ? RequestIdFilter.current(servlet.getRequest())
                : "unassigned";
    }
}
