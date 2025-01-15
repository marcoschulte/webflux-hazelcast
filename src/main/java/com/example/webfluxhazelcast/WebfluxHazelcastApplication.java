package com.example.webfluxhazelcast;

import com.example.webfluxhazelcast.hazelcast.AsyncHazelcastCacheManager;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Date;

@SpringBootApplication
public class WebfluxHazelcastApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebfluxHazelcastApplication.class, args);
    }

    @Configuration
    @EnableCaching
    class CacheConfig {
        @Bean
        public CacheManager cacheManager(HazelcastInstance hazelcastInstance) {
            return new AsyncHazelcastCacheManager(hazelcastInstance);
        }
    }

    @RestController
    class TimeController {

        @GetMapping(path = "time")
        @Cacheable(cacheNames = "time-cache", sync = true)
        public Mono<String> time() {
            return Mono.delay(Duration.ofSeconds(5))
                    .thenReturn(new Date().toString());
        }
    }
}
