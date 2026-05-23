/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed tool deduplication cache for multi-instance deployments.
 * <p>
 * Provides cross-pod deduplication of tool invocations, ensuring that
 * duplicate tool calls within a session are suppressed cluster-wide.
 * Redis native TTL handles expiration without periodic cleanup.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * wmt:
 *   agent:
 *     cache:
 *       type: redis
 * }</pre>
 *
 * <h3>Key format</h3>
 * Keys are stored as: {@code agent:dedup:{sessionId}|{toolName}|{inputHash}}
 *
 * @see LocalToolDedupCache
 */
@Slf4j
public class RedisToolDedupCache implements ToolDedupCache {

    private static final String KEY_PREFIX = "agent:dedup:";

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisToolDedupCache(final RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("RedisToolDedupCache initialized");
    }

    @Override
    public Optional<byte[]> get(final String key) {
        try {
            String redisKey = Objects.requireNonNull(prefixed(key), "Redis key must not be null");
            byte[] value = redisTemplate.opsForValue().get(redisKey);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("Redis dedup cache get failed for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(final String key, final byte[] value, final Duration ttl) {
        try {
            String redisKey = Objects.requireNonNull(prefixed(key), "Redis key must not be null");
            byte[] redisValue = Objects.requireNonNull(value, "Redis dedup value must not be null");
            redisTemplate.opsForValue().set(redisKey, redisValue, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Redis dedup cache put failed for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictSession(final String sessionId) {
        try {
            String pattern = prefixed(sessionId + "|*");
            int removed = 0;
            ScanOptions scanOptions = ScanOptions.scanOptions()
                    .match(Objects.requireNonNull(pattern, "Redis scan pattern must not be null"))
                    .count(100)
                    .build();
            try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String redisKey = Objects.requireNonNull(cursor.next(), "Scanned Redis key must not be null");
                    redisTemplate.delete(redisKey);
                    removed++;
                }
            }
            if (removed > 0) {
                log.debug("Evicted {} Redis dedup entries for session {}", removed, sessionId);
            }
        } catch (Exception e) {
            log.warn("Redis dedup cache evictSession failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    private String prefixed(final String key) {
        return KEY_PREFIX + key;
    }
}
