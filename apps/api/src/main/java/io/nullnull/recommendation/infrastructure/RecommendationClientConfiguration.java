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
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Wires the two clients to apps/ai: the gateway with the configured budget, the probe with its own. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecommendationClientProperties.class)
public class RecommendationClientConfiguration {

    /** The readiness probe answers load balancer health checks, so it never waits on the gateway budget. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    RecommendationGateway recommendationGateway(RestClient.Builder builder, RecommendationClientProperties properties) {
        RestClient client = client(builder, properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
        return new HttpRecommendationGateway(client, RecommendationClientConfiguration::currentRequestId);
    }

    @Bean
    ReadinessProbe recommendationServiceProbe(RestClient.Builder builder, RecommendationClientProperties properties) {
        return new RecommendationServiceProbe(client(builder, properties.baseUrl(), PROBE_TIMEOUT, PROBE_TIMEOUT));
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
            Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /** Worker (job) contexts have no servlet request; the call still carries the filter's placeholder id. */
    private static String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                ? RequestIdFilter.current(servlet.getRequest())
                : "unassigned";
    }
}
