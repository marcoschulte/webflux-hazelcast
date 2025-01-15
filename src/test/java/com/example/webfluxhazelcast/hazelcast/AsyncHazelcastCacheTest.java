package com.example.webfluxhazelcast.hazelcast;


import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.TWO_HUNDRED_MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith({SpringExtension.class})
@ContextConfiguration(locations = {"classpath:async-cache-config.xml"})
class AsyncHazelcastCacheTest {

    @Autowired
    HazelcastInstance hazelcastInstance;

    AsyncHazelcastCache cache;

    @BeforeEach
    void evictCache() {
        hazelcastInstance.getMap("values").clear();
        hazelcastInstance.getMap("lock-without-ttl").clear();
        hazelcastInstance.getMap("lock-wit-ttl").clear();

        cache = new AsyncHazelcastCache(
                hazelcastInstance.getMap("values"),
                hazelcastInstance.getMap("lock-without-ttl"),
                Duration.ofMillis(25));
    }

    @Test
    void retrieve() throws Exception {
        assertThat(cache.get("key"), is(nullValue()));
        cache.put("key", "value");
        assertThat(cache.retrieve("key").get(), is(equalTo("value")));
    }

    @Test
    void retrieve_nullValue() throws Exception {
        assertThat(cache.get("key"), is(nullValue()));
        cache.put("key", null);
        assertThat(cache.retrieve("key").get(), is(equalTo(null)));
    }

    @Test
    void retrieveWithValueloader_serializedAccess_valueLoaderCalledOnceThanCached() throws Exception {
        assertThat(cache.get("key"), is(nullValue()));

        assertThat(cache.retrieve("key", () -> CompletableFuture.completedFuture("value")).get(), is(equalTo("value")));
        assertThat(cache.retrieve("key", () -> CompletableFuture.completedFuture("value2")).get(), is(equalTo("value")));
    }

    @Test
    void retrieveWithValueloader_concurrentAccess_valueLoaderCalledOnceThanCached() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Supplier<CompletableFuture<Integer>> valueLoader = () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return CompletableFuture.completedFuture(counter.incrementAndGet());
        };


        final List<Integer> results = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(10);

        final String key = "key";
        Runnable run = () -> {
            try {
                results.add(cache.retrieve(key, valueLoader).get(1, TimeUnit.SECONDS));
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
            }
            latch.countDown();
        };

        for (int i = 0; i < 10; i++) {
            new Thread(run).start();
        }
        latch.await(1, TimeUnit.SECONDS);

        assertThat(results, hasSize(10));
        for (Integer i : results) {
            assertThat(i, is(equalTo(1)));
        }
    }

    @Test
    void retrieveWithValueloader_nullValue() throws Exception {
        assertThat(cache.get("key"), is(nullValue()));

        assertThat(cache.retrieve("key", () -> CompletableFuture.completedFuture(null)).get(), is(equalTo(null)));
        assertThat(cache.retrieve("key", () -> CompletableFuture.completedFuture("value2")).get(), is(equalTo(null)));
    }

    @Test
    void retrieveWithValueloader_notInvokedWhenCacheHit() throws Exception {
        cache.put("key-with-value", "value");
        cache.put("key-with-null", null);

        assertThat(cache.retrieve("key-with-value", () -> {
            throw new IllegalStateException("Should not be invoked");
        }).get(), is(equalTo("value")));

        assertThat(cache.retrieve("key-with-null", () -> {
            throw new IllegalStateException("Should not be invoked");
        }).get(), is(nullValue()));
    }

    @Test
    void retrieveWithValueloader_valueLoaderFails() throws Exception {
        try {
            cache.retrieve("key", () -> {
                throw new UnsupportedOperationException("Expected");
            }).get();
            fail("No exception was thrown");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(notNullValue()));
            assertThat(e.getCause(), is(instanceOf(UnsupportedOperationException.class)));
        }
    }

    @Test
    void retrieveWithValueloader_valueLoaderFails_lockIsRemovedWhenFailing() throws Exception {
        try {
            cache.retrieve("key", () -> {
                throw new UnsupportedOperationException("Expected");
            }).get();
            fail("No exception was thrown");
        } catch (ExecutionException e) {
        }

        // lock should already be removed
        CompletableFuture<?> future = cache.retrieve("key", () -> CompletableFuture.completedFuture("value"));
        await().atMost(TWO_HUNDRED_MILLISECONDS).until(future::get, is(equalTo("value")));
    }

    @Test
    void retrieveWithValueloader_lockIsRemovedAfterTimeout() throws Exception {
        cache = new AsyncHazelcastCache(
                hazelcastInstance.getMap("values"),
                hazelcastInstance.getMap("lock-with-ttl"),
                Duration.ofMillis(25));

        final String key = "key";
        ReentrantLock lock = new ReentrantLock();
        lock.lock();

        Thread neverCompletingRetrieval = new Thread(() -> {
            try {
                cache.retrieve(key, () -> {
                    lock.lock();
                    throw new IllegalStateException("Should not be invoked");
                }).get();
            } catch (InterruptedException | ExecutionException e) {
            }
        });
        neverCompletingRetrieval.start();

        await().until(() -> lock.getQueueLength() == 1);

        assertThat(cache.retrieve(key, () -> CompletableFuture.completedFuture("expected")).get(),
                is(equalTo("expected")));

        neverCompletingRetrieval.interrupt();
    }

    @Test
    void retrieveWithValueloader_lockIsPerKey() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();

        Thread neverCompletingRetrieval = new Thread(() -> {
            try {
                cache.retrieve("never-completing-key", () -> {
                    lock.lock();
                    throw new IllegalStateException("Should not be invoked");
                }).get();
            } catch (InterruptedException | ExecutionException e) {
            }
        });
        neverCompletingRetrieval.start();

        await().until(() -> lock.getQueueLength() == 1);

        assertThat(cache.retrieve("unlocked-key", () -> CompletableFuture.completedFuture("expected")).get(),
                is(equalTo("expected")));

        neverCompletingRetrieval.interrupt();
    }
}
