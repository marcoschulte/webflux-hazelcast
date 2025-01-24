package com.example.webfluxhazelcast.hazelcast;

import com.hazelcast.map.IMap;
import com.hazelcast.spring.cache.HazelcastCache;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class AsyncHazelcastCache extends HazelcastCache {

    private final IMap<Object, Object> values;
    private final IMap<Object, Object> lockObjects;
    private final Duration pollingInterval;

    public AsyncHazelcastCache(IMap<Object, Object> values,
                               IMap<Object, Object> lockObjects,
                               Duration pollingInterval) {
        super(values);
        this.values = values;
        this.lockObjects = lockObjects;
        this.pollingInterval = pollingInterval;
    }

    @Override
    public CompletableFuture<?> retrieve(Object key) {
        return this.values.getAsync(key).thenApply(this::fromStoreValue).toCompletableFuture();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return cacheLookup(key)
                .switchIfEmpty(Mono.firstWithSignal(
                        retryLookup(key),
                        waitForLock(key)
                                .then(cacheLookup(key))
                                .switchIfEmpty(computeAndPut(key, valueLoader))
                                .doFinally(sig -> unlockKey(key))
                ))
                .mapNotNull(val -> (T) fromStoreValue(val))
                .toFuture();
    }

    private Mono<Object> cacheLookup(Object key) {
        return Mono.defer(() -> Mono.fromCompletionStage(this.values.getAsync(key)));
    }

    private Mono<Void> waitForLock(Object key) {
        Flux<Long> interval = Flux.interval(Duration.ZERO, pollingInterval);

        Mono<Boolean> hasLock = Mono.defer(() -> Mono.fromCompletionStage(this.lockObjects.putAsync(key, true))
                        .map(oldValue -> false) // lock object already existing -> we did not acquire lock
                        .switchIfEmpty(Mono.just(true))) // no lock object -> successfully acquired lock
                .filter(it -> it);

        return interval
                .concatMap(__ -> hasLock)
                .next()
                .then();
    }

    private Mono<Object> retryLookup(Object key) {
        Flux<Long> interval = Flux.interval(Duration.ZERO, pollingInterval);

        return interval
                .concatMap(__ -> cacheLookup(key))
                .next();
    }

    private <T> Mono<Object> computeAndPut(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return Mono.defer(() -> Mono.fromCompletionStage(valueLoader.get().thenApply(this::toStoreValue)))
                .flatMap(loaded -> Mono.fromCompletionStage(
                        this.values.putAsync(key, loaded)
                                .thenApply(oldVal -> loaded)));
    }

    private void unlockKey(Object key) {
        this.lockObjects.delete(key);
    }
}
