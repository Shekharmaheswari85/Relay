/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.cache;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Default local (in-memory) implementation of {@link ToolDedupCache}.
 * <p>
 * Uses a ConcurrentHashMap with TTL-based expiration checked on read and via
 * a periodic cleanup scheduler. This implementation is appropriate for single-pod
 * deployments or scenarios with session stickiness.
 * <p>
 * This bean is automatically replaced by any distributed cache implementation
 * annotated with {@code @Service} and implementing {@link ToolDedupCache}.
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code agent.tool.cache.cleanup-interval-ms} - cleanup interval (default: 300000 = 5 min)</li>
 * </ul>
 */
@Service
@Slf4j
@ConditionalOnMissingBean(ToolDedupCache.class)
public class LocalToolDedupCache implements ToolDedupCache {

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(byte[] value, long expiresAtMs) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMs;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheEntry(byte[] otherValue, long otherExpiresAtMs))) {
                return false;
            }
            return expiresAtMs == otherExpiresAtMs && Arrays.equals(value, otherValue);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(value);
            result = 31 * result + Long.hashCode(expiresAtMs);
            return result;
        }

        @Override
        public String toString() {
            return "CacheEntry[value=" + Arrays.toString(value) + ", expiresAtMs=" + expiresAtMs + "]";
        }
    }

    @Override
    public Optional<byte[]> get(final String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                cache.remove(key);
            }
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(final String key, final byte[] value, final Duration ttl) {
        long expiresAt = System.currentTimeMillis() + ttl.toMillis();
        cache.put(key, new CacheEntry(value, expiresAt));
    }

    @Override
    public void evictSession(final String sessionId) {
        String prefix = sessionId + "|";
        int removed = 0;
        Iterator<String> it = cache.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Evicted {} local cache entries for session {}", removed, sessionId);
        }
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    /**
     * Periodic cleanup of expired entries.
     * Runs every 5 minutes by default.
     */
    @Scheduled(fixedDelayString = "${agent.tool.cache.cleanup-interval-ms:300000}")
    public void purgeExpiredEntries() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs < now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Purged {} expired local cache entries (remaining={})", removed, cache.size());
        }
    }

    /**
     * Returns current cache size (for monitoring/metrics).
     */
    public int size() {
        return cache.size();
    }
}
