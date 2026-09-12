package io.nullnull.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only deterministic provider fixture.
 *
 * <p>It records the query parameters it was called with, MINUS every credential-bearing name, which
 * is dropped at capture time rather than filtered on the way out - so a secret cannot reach a test
 * artifact even through a failure message. Before this, nothing could assert what the adapter
 * actually SENT, which is how the concentration forecast adapter spent its whole life sending a
 * region code the provider answers with "0 rows, resultCode 0000" instead of an error (#109).
 */
public final class StubProviderServer implements AutoCloseable {

    public record Response(int status, String body, Duration delay, Map<String, String> headers) {
        public Response(int status, String body) {
            this(status, body, Duration.ZERO, Map.of());
        }

        public Response {
            headers = Map.copyOf(headers);
        }
    }

    /** Never recorded, in any form. Dropped while parsing, not while reading back. */
    private static final Set<String> CREDENTIAL_PARAMETERS = Set.of("serviceKey", "apiKey", "key",
            "token", "authKey", "access_token");

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final ConcurrentLinkedQueue<Map<String, String>> observedQueries = new ConcurrentLinkedQueue<>();

    public StubProviderServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("stub provider could not bind to loopback", failure);
        }
        server.createContext("/provider", this::respond);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public StubProviderServer enqueue(Response response) {
        responses.add(response);
        return this;
    }

    public URI uri(String rawQuery) {
        String suffix = rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery;
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/provider" + suffix);
    }

    public int calls() {
        return calls.get();
    }

    /**
     * The query of the call at {@code index} (0 is the first), with credential parameters absent.
     * Values are URL-decoded so an assertion reads the value the adapter meant to send.
     */
    public Map<String, String> observedQuery(int index) {
        List<Map<String, String>> queries = List.copyOf(observedQueries);
        if (index < 0 || index >= queries.size()) {
            throw new IllegalStateException("no call recorded at index " + index
                    + "; recorded " + queries.size());
        }
        return queries.get(index);
    }

    private static Map<String, String> safeQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.copyOf(parameters);
        }
        for (String pair : rawQuery.split("&")) {
            int split = pair.indexOf('=');
            String name = split < 0 ? pair : pair.substring(0, split);
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            if (CREDENTIAL_PARAMETERS.contains(decodedName)) {
                continue;
            }
            String value = split < 0 ? "" : pair.substring(split + 1);
            parameters.put(decodedName, URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return Map.copyOf(parameters);
    }

    private void respond(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        observedQueries.add(safeQuery(exchange.getRequestURI().getRawQuery()));
        Response response = responses.poll();
        if (response == null) {
            response = new Response(500, "{}");
        }
        if (!response.delay().isZero()) {
            try {
                Thread.sleep(response.delay());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
