package com.example.webfluxhazelcast.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AsyncHazelcastCacheManager implements CacheManager {

    private static final Duration LOCK_POLLING_INTERVAL = Duration.ofMillis(100);
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();
    private final HazelcastInstance hazelcastInstance;

    public AsyncHazelcastCacheManager(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = caches.get(name);
        if (cache == null) {
            String lockMapName = name + "-locks";

            Assert.isTrue(
                    hazelcastInstance.getConfig().getMapConfig(lockMapName).getTimeToLiveSeconds() > 0,
                    "TTL for lock map " + lockMapName + " missing");

            IMap<Object, Object> valuesMap = hazelcastInstance.getMap(name);
            IMap<Object, Object> lockMap = hazelcastInstance.getMap(lockMapName);

            cache = new AsyncHazelcastCache(valuesMap, lockMap, LOCK_POLLING_INTERVAL);
            Cache currentCache = caches.putIfAbsent(name, cache);
            if (currentCache != null) {
                cache = currentCache;
            }
        }
        return cache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableCollection(caches.keySet());
    }
}
