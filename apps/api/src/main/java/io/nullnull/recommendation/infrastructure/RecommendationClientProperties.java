package io.nullnull.recommendation.infrastructure;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for apps/ai. A missing value fails startup instead of falling back to a default host. */
@ConfigurationProperties(prefix = "nullnull.ai")
public record RecommendationClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public RecommendationClientProperties {
        Objects.requireNonNull(baseUrl, "nullnull.ai.base-url is required");
        Objects.requireNonNull(connectTimeout, "nullnull.ai.connect-timeout is required");
        Objects.requireNonNull(readTimeout, "nullnull.ai.read-timeout is required");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("nullnull.ai.base-url must be an http(s) URL");
        }
    }
}
