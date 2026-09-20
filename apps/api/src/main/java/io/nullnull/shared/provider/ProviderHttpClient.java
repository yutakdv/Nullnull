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
        return get(sourceCode, uri, Map.of());
    }

    /**
     * The same call with request headers. It exists for a source reached through a proxy we run: the
     * proxy holds the provider credential and refuses a request that does not carry our shared token,
     * so the token travels in a header rather than in the path. A path would be the wrong place twice
     * - it is what access logs record, and BA-070-T2 only pins that the query string stays out of them.
     *
     * <p>Header values are never logged here, and this class logs no URI either: the credential's only
     * appearance is in the request this method builds.
     */
    public CompletableFuture<ProviderResponse> get(String sourceCode, URI uri, Map<String, String> headers) {
        Map<String, String> requestHeaders = Map.copyOf(headers);
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
                    ProviderResponse response = retry.execute(() -> send(uri, requestHeaders), ProviderResponse::status,
                            ProviderResponse::headers);
                    if (response.status() < 200 || response.status() > 299) {
                        if (response.status() == 429 || response.status() >= 500) {
                            circuit.failure();
                        }
                        throw new ProviderException(ProviderException.Category.HTTP_STATUS,
                                ProviderException.classify(response.status()), response.status());
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

    private ProviderResponse send(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET();
        headers.forEach(builder::header);
        HttpRequest request = builder.build();
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
