package io.nullnull.shared.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, redirect-free provider transport with per-source concurrency isolation. */
public final class ProviderHttpClient {

    public record ProviderResponse(int status, byte[] body, java.net.http.HttpHeaders headers) {
        public ProviderResponse {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final Executor executor;
    private final RetryPolicy retry;
    private final Map<String, Set<String>> allowedHosts;
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> circuits;
    private final int perSourceConcurrency;

    public ProviderHttpClient(HttpClient client, Duration requestTimeout, int maxResponseBytes,
            Executor executor, RetryPolicy retry, Map<String, Set<String>> allowedHosts,
            Map<String, CircuitBreaker> circuits, int perSourceConcurrency) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.executor = executor;
        this.retry = retry;
        this.allowedHosts = Map.copyOf(allowedHosts);
        this.circuits = Map.copyOf(circuits);
        this.perSourceConcurrency = perSourceConcurrency;
    }

    public CompletableFuture<ProviderResponse> get(String sourceCode, URI uri) {
        validateTarget(sourceCode, uri);
        CircuitBreaker circuit = circuits.get(sourceCode);
        if (circuit == null || !circuit.allowRequest()) {
            return CompletableFuture.failedFuture(new ProviderException(
                    ProviderException.Category.CIRCUIT_OPEN, ProviderException.StatusClass.NONE));
        }
        Semaphore semaphore = permits.computeIfAbsent(sourceCode, ignored -> new Semaphore(perSourceConcurrency));
        if (!semaphore.tryAcquire()) {
            return CompletableFuture.failedFuture(new ProviderException(
                    ProviderException.Category.CAPACITY, ProviderException.StatusClass.NONE));
        }
        CompletableFuture<ProviderResponse> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    ProviderResponse response = retry.execute(() -> send(uri), ProviderResponse::status,
                            ProviderResponse::headers);
                    if (response.status() < 200 || response.status() > 299) {
                        if (response.status() == 429 || response.status() >= 500) {
                            circuit.failure();
                        }
                        throw new ProviderException(ProviderException.Category.HTTP_STATUS,
                                ProviderException.classify(response.status()));
                    }
                    circuit.success();
                    result.complete(response);
                } catch (ProviderException failure) {
                    if (failure.category() == ProviderException.Category.IO
                            || failure.category() == ProviderException.Category.TIMEOUT
                            || failure.category() == ProviderException.Category.RESPONSE_TOO_LARGE) {
                        circuit.failure();
                    }
                    result.completeExceptionally(failure);
                } catch (RuntimeException failure) {
                    result.completeExceptionally(new ProviderException(ProviderException.Category.IO,
                            ProviderException.StatusClass.NONE));
                } finally {
                    semaphore.release();
                }
            });
        } catch (RejectedExecutionException failure) {
            semaphore.release();
            result.completeExceptionally(new ProviderException(ProviderException.Category.CAPACITY,
                    ProviderException.StatusClass.NONE));
        }
        return result;
    }

    private ProviderResponse send(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxResponseBytes) {
                throw new ProviderException(ProviderException.Category.RESPONSE_TOO_LARGE,
                        ProviderException.classify(response.statusCode()));
            }
            byte[] body = input.readNBytes(maxResponseBytes + 1);
            if (body.length > maxResponseBytes) {
                throw new ProviderException(ProviderException.Category.RESPONSE_TOO_LARGE,
                        ProviderException.classify(response.statusCode()));
            }
            return new ProviderResponse(response.statusCode(), body, response.headers());
        }
    }

    private void validateTarget(String sourceCode, URI uri) {
        Set<String> hosts = allowedHosts.get(sourceCode);
        String host = uri == null ? null : uri.getHost();
        boolean loopbackHttp = "http".equals(uri == null ? null : uri.getScheme())
                && "127.0.0.1".equals(host);
        boolean https = "https".equals(uri == null ? null : uri.getScheme());
        if (hosts == null || host == null || !hosts.contains(host) || (!https && !loopbackHttp)
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new ProviderException(ProviderException.Category.HOST_NOT_ALLOWED,
                    ProviderException.StatusClass.NONE);
        }
    }
}
