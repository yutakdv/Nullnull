package io.nullnull.shared.provider;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nullnull.provider")
public record ProviderClientProperties(Duration connectTimeout, Duration requestTimeout,
        int maxResponseBytes, int executorThreads, int executorQueueCapacity, int perSourceConcurrency,
        Retry retry, Circuit circuit) {

    public ProviderClientProperties {
        Objects.requireNonNull(connectTimeout, "nullnull.provider.connect-timeout is required");
        Objects.requireNonNull(requestTimeout, "nullnull.provider.request-timeout is required");
        Objects.requireNonNull(retry, "nullnull.provider.retry is required");
        Objects.requireNonNull(circuit, "nullnull.provider.circuit is required");
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout.isZero() || requestTimeout.isNegative()
                || maxResponseBytes < 1024 || executorThreads < 1 || executorQueueCapacity < 1
                || perSourceConcurrency < 1 || perSourceConcurrency > executorThreads) {
            throw new IllegalArgumentException("nullnull.provider limits are invalid");
        }
    }

    public record Retry(int attempts, Duration baseDelay, Duration maxDelay) {
        public Retry {
            Objects.requireNonNull(baseDelay, "nullnull.provider.retry.base-delay is required");
            Objects.requireNonNull(maxDelay, "nullnull.provider.retry.max-delay is required");
            if (attempts < 1 || baseDelay.isNegative() || maxDelay.isNegative()
                    || maxDelay.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException("nullnull.provider.retry limits are invalid");
            }
        }
    }

    public record Circuit(int failureThreshold, Duration failureWindow, Duration openDuration) {
        public Circuit {
            Objects.requireNonNull(failureWindow, "nullnull.provider.circuit.failure-window is required");
            Objects.requireNonNull(openDuration, "nullnull.provider.circuit.open-duration is required");
            if (failureThreshold < 1 || failureWindow.isZero() || failureWindow.isNegative()
                    || openDuration.isZero() || openDuration.isNegative()) {
                throw new IllegalArgumentException("nullnull.provider.circuit limits are invalid");
            }
        }
    }
}
