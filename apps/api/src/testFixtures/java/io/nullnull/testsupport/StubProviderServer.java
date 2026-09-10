package io.nullnull.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only deterministic provider fixture. It never records the incoming URI or query. */
public final class StubProviderServer implements AutoCloseable {

    public record Response(int status, String body, Duration delay, Map<String, String> headers) {
        public Response(int status, String body) {
            this(status, body, Duration.ZERO, Map.of());
        }

        public Response {
            headers = Map.copyOf(headers);
        }
    }

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

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

    private void respond(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
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
