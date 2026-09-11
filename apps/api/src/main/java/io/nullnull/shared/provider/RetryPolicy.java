package io.nullnull.shared.provider;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/** Retries IO, 429 and 5xx only. The attempt count includes the initial request. */
public final class RetryPolicy {

    @FunctionalInterface
    public interface Attempt<T> {
        T run() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface Status<T> {
        int status(T response);
    }

    @FunctionalInterface
    public interface Headers<T> {
        HttpHeaders headers(T response);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int attempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final Sleeper sleeper;

    public RetryPolicy(int attempts, Duration baseDelay, Duration maxDelay, Clock clock,
            DoubleSupplier jitter, Sleeper sleeper) {
        if (attempts < 1 || baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("invalid retry policy");
        }
        this.attempts = attempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.clock = clock;
        this.jitter = jitter;
        this.sleeper = sleeper;
    }

    public <T> T execute(Attempt<T> attempt, Status<T> status, Headers<T> headers) {
        for (int number = 1; number <= attempts; number++) {
            try {
                T response = attempt.run();
                int code = status.status(response);
                if (!retryable(code) || number == attempts) {
                    return response;
                }
                pause(delay(number, headers.headers(response)));
            } catch (HttpTimeoutException failure) {
                if (number == attempts) {
                    throw new ProviderException(ProviderException.Category.TIMEOUT,
                            ProviderException.StatusClass.NONE);
                }
                pause(delay(number, null));
            } catch (IOException failure) {
                if (number == attempts) {
                    throw new ProviderException(ProviderException.Category.IO,
                            ProviderException.StatusClass.NONE);
                }
                pause(delay(number, null));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ProviderException(ProviderException.Category.INTERRUPTED,
                        ProviderException.StatusClass.NONE);
            }
        }
        throw new IllegalStateException("retry loop exhausted without a result");
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500 && status <= 599;
    }

    private Duration delay(int failedAttempt, HttpHeaders headers) {
        Optional<Duration> retryAfter = retryAfter(headers);
        if (retryAfter.isPresent()) {
            return retryAfter.orElseThrow().compareTo(maxDelay) > 0 ? maxDelay : retryAfter.orElseThrow();
        }
        long multiplier = 1L << Math.min(failedAttempt - 1, 30);
        Duration exponential;
        try {
            exponential = baseDelay.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            exponential = maxDelay;
        }
        Duration capped = exponential.compareTo(maxDelay) > 0 ? maxDelay : exponential;
        double factor = 0.5d + Math.max(0d, Math.min(1d, jitter.getAsDouble()));
        return Duration.ofMillis(Math.max(0L, Math.round(capped.toMillis() * factor)));
    }

    private Optional<Duration> retryAfter(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.firstValue("Retry-After").flatMap(value -> {
            try {
                return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value))));
            } catch (NumberFormatException ignored) {
                try {
                    Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    Duration duration = Duration.between(clock.instant(), at);
                    return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
                } catch (DateTimeParseException invalid) {
                    return Optional.empty();
                }
            }
        });
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Category.INTERRUPTED,
                    ProviderException.StatusClass.NONE);
        }
    }
}
