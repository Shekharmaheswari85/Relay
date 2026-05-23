/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed cache implementation for multi-instance deployments.
 * <p>
 * Provides cluster-safe caching with native TTL support via Redis SETEX.
 * Suitable for horizontally-scaled agent deployments where cross-pod
 * cache consistency is required.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * wmt:
 *   agent:
 *     cache:
 *       type: redis
 *       ttl: 30m
 *       key-prefix: "agent:cache:"
 *       redis:
 *         host: redis.internal
 *         port: 6379
 *         password: secret
 *         database: 0
 * }</pre>
 *
 * <h3>Key format</h3>
 * All keys are prefixed with the configured {@code key-prefix} to enable
 * namespacing in shared Redis clusters.
 *
 * @see InMemoryAgentCache
 */
@Slf4j
public class RedisAgentCache implements AgentCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;
    private final String keyPrefix;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private volatile Instant lastCleared;

    public RedisAgentCache(final StringRedisTemplate redisTemplate,
                           final ObjectMapper objectMapper,
                           final Duration defaultTtl,
                           final String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.defaultTtl = defaultTtl;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agent:cache:";
        log.info("RedisAgentCache initialized: keyPrefix={}, defaultTtl={}", this.keyPrefix, defaultTtl);
    }

    @Override
    public <T> Optional<T> get(final String key, final Class<T> type) {
        try {
            String fullKey = Objects.requireNonNull(prefixed(key), "Redis key must not be null");
            String json = redisTemplate.opsForValue().get(fullKey);
            if (json == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            if (type == String.class) {
                @SuppressWarnings("unchecked")
                T result = (T) json;
                return Optional.of(result);
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Redis cache get failed for key {}: {}", key, e.getMessage());
            misses.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void put(final String key, final Object value) {
        put(key, value, defaultTtl);
    }

    @Override
    public void put(final String key, final Object value, final Duration ttl) {
        try {
            String json = value instanceof String str ? str : objectMapper.writeValueAsString(value);
            String fullKey = Objects.requireNonNull(prefixed(key), "Redis key must not be null");
            String redisJson = Objects.requireNonNull(json, "Serialized Redis value must not be null");
            Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
            redisTemplate.opsForValue().set(fullKey, redisJson, effectiveTtl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis cache put failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evict(final String key) {
        try {
            String fullKey = Objects.requireNonNull(prefixed(key), "Redis key must not be null");
            Boolean deleted = redisTemplate.delete(fullKey);
            if (deleted.equals(Boolean.TRUE)) {
                evictions.incrementAndGet();
            }
        } catch (Exception e) {
            log.warn("Redis cache evict failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public int evictByPattern(final String pattern) {
        try {
            String fullPattern = Objects.requireNonNull(prefixed(pattern), "Redis pattern must not be null");
            int count = 0;
            ScanOptions scanOptions = ScanOptions.scanOptions().match(fullPattern).count(100).build();
            try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String redisKey = Objects.requireNonNull(cursor.next(), "Scanned Redis key must not be null");
                    redisTemplate.delete(redisKey);
                    count++;
                }
            }
            if (count > 0) {
                evictions.addAndGet(count);
                log.debug("Evicted {} Redis keys matching pattern '{}'", count, fullPattern);
            }
            return count;
        } catch (Exception e) {
            log.warn("Redis cache evictByPattern failed for pattern {}: {}", pattern, e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean isDistributed() {
        return true;
    }

    @Override
    public CacheStats getStats() {
        long h = hits.get();
        long m = misses.get();
        double rate = (h + m) > 0 ? (double) h / (h + m) : 0.0;
        long size = estimateSize();
        return new CacheStats(h, m, evictions.get(), size, -1L, rate, lastCleared);
    }

    /**
     * Clear all keys under this cache's prefix (for admin operations).
     * <p>
     * Uses SCAN to avoid blocking Redis with a full KEYS command.
     */
    public void clear() {
        try {
            int count = evictByPattern("*");
            lastCleared = Instant.now();
            log.info("RedisAgentCache cleared: {} keys removed", count);
        } catch (Exception e) {
            log.warn("Redis cache clear failed: {}", e.getMessage());
        }
    }

    private String prefixed(final String key) {
        return keyPrefix + key;
    }

    private long estimateSize() {
        try {
            ScanOptions scanOptions = ScanOptions.scanOptions().match(keyPrefix + "*").count(1).build();
            long count = 0;
            try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.debug("Could not estimate Redis cache size: {}", e.getMessage());
            return -1L;
        }
    }
}
