/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory cache implementation using Caffeine.
 * <p>
 * Suitable for single-instance deployments or local development.
 * For multi-instance deployments, use Redis or Hazelcast.
 */
@Slf4j
public class InMemoryAgentCache implements AgentCache {

    private final Cache<String, CacheEntry> cache;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private volatile Instant lastCleared;

    private record CacheEntry(String json, Instant expiresAt) {}

    public InMemoryAgentCache(final ObjectMapper objectMapper, final int maxEntries, final Duration defaultTtl) {
        this.objectMapper = objectMapper;
        this.defaultTtl = defaultTtl;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(defaultTtl)
                .evictionListener((key, value, cause) -> evictions.incrementAndGet())
                .build();
        log.info("InMemoryAgentCache initialized: maxEntries={}, ttl={}", maxEntries, defaultTtl);
    }

    @Override
    public <T> Optional<T> get(final String key, final Class<T> type) {
        CacheEntry entry = cache.getIfPresent(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (entry.expiresAt != null && Instant.now().isAfter(entry.expiresAt)) {
            cache.invalidate(key);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        try {
            if (type == String.class) {
                @SuppressWarnings("unchecked")
                T result = (T) entry.json;
                return Optional.of(result);
            }
            return Optional.of(objectMapper.readValue(entry.json, type));
        } catch (Exception e) {
            log.warn("Failed to deserialize cached value for key {}: {}", key, e.getMessage());
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
            Instant expiresAt = ttl != null ? Instant.now().plus(ttl) : null;
            cache.put(key, new CacheEntry(json, expiresAt));
        } catch (Exception e) {
            log.warn("Failed to cache value for key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void evict(final String key) {
        cache.invalidate(key);
    }

    @Override
    public int evictByPattern(final String pattern) {
        // Simple prefix matching for in-memory cache
        String prefix = pattern.replace("*", "");
        int count = 0;
        for (String key : cache.asMap().keySet()) {
            if (key.startsWith(prefix)) {
                cache.invalidate(key);
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public CacheStats getStats() {
        long h = hits.get();
        long m = misses.get();
        double rate = (h + m) > 0 ? (double) h / (h + m) : 0.0;
        return new CacheStats(
                h,
                m,
                evictions.get(),
                cache.estimatedSize(),
                0L, // Memory tracking not available in Caffeine without additional config
                rate,
                lastCleared);
    }

    /**
     * Clear all entries (for admin operations).
     */
    public void clear() {
        cache.invalidateAll();
        lastCleared = Instant.now();
        log.info("InMemoryAgentCache cleared");
    }
}
