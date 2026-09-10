package io.nullnull.shared.provider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Coalesces concurrent work for the same stable key; failures are never cached. */
public final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> flights = new ConcurrentHashMap<>();

    public V execute(K key, Supplier<V> work) {
        CompletableFuture<V> created = new CompletableFuture<>();
        CompletableFuture<V> active = flights.putIfAbsent(key, created);
        if (active == null) {
            try {
                V value = work.get();
                created.complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                created.completeExceptionally(failure);
                throw failure;
            } finally {
                flights.remove(key, created);
            }
        }
        try {
            return active.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }
}
