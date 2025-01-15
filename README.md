# Implementation of a reactive Hazelcast cache for Spring Caching

The
current [HazelcastCache](https://github.com/hazelcast/hazelcast/blob/master/hazelcast-spring/src/main/java/com/hazelcast/spring/cache/HazelcastCache.java)
implementation is missing the implementations of the
two [reactive methods](https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/cache/Cache.java#L136-L165).

Therefore, Hazelcast cannot be used as Cache Provider for Spring Caching when using a reactive architecture such as
webflux.

The Hazelcast Team is currently working on a
solution ([Slack](https://hazelcastcommunity.slack.com/archives/C0159HP69E3/p1736356879585879), [PR](https://github.com/hazelcast/hazelcast/pull/26434)),
but we needed a solution now.

Also, we needed a solution which supports synchronized access, which the mentioned PR does not implement.

This repo provides a reactive HazelcastCache implementation and an example app using the cache reactively and
synchronized.

To try run

```shell
curl http://localhost:8080/time &
  sleep 1; curl http://localhost:8080/time &
  wait 
```

The REST endpoint intentionally delays its response by 5 seconds.
Even though the two calls are made one second apart, both return the same timestamp.
This is because the time endpoint uses a synchronized cache to store the response.
The first call fetches and caches the response, while the second call waits until its cached and retrieves it
directly from the cache.

The provided implementation supports synchronized access but has two inelegancies due to current Hazelcast limitations.
The main issue is the lack of non-blocking lock methods.
To work around this, a second Hazelcast map is used to asynchronously store lock objects.
Since we cannot non-blockingly wait for the locks to be released,
we also need to regularly poll to check if the lock has been released.

The implementation can be found at [AsyncHazelcastCache.java](src/main/java/com/example/webfluxhazelcast/hazelcast/AsyncHazelcastCache.java)  
Plenty of tests are in [AsyncHazelcastCacheTest.java](src/test/java/com/example/webfluxhazelcast/hazelcast/AsyncHazelcastCacheTest.java)
