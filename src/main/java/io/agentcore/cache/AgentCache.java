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

/**
 * Core cache abstraction for agent tool result caching.
 * <p>
 * Implementations can use in-memory (Caffeine), Redis, Memcached, Hazelcast,
 * or any other cache backend. The implementation is selected via configuration.
 *
 * <pre>{@code
 * wmt:
 *   agent:
 *     cache:
 *       type: redis  # inmemory | redis | memcached | hazelcast
 *       ttl: 30m
 * }</pre>
 */
public interface AgentCache {

    /**
     * Get cached value by key.
     *
     * @param key  the cache key
     * @param type the expected value type
     * @return the cached value, or empty if not found or expired
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Put value with default TTL.
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    void put(String key, Object value);

    /**
     * Put value with custom TTL.
     *
     * @param key   the cache key
     * @param value the value to cache
     * @param ttl   time-to-live duration
     */
    void put(String key, Object value, Duration ttl);

    /**
     * Remove a single key from cache.
     *
     * @param key the cache key to evict
     */
    void evict(String key);

    /**
     * Remove all keys matching a pattern.
     * <p>
     * Pattern syntax depends on the backend:
     * <ul>
     *   <li>Redis: glob patterns (e.g., "session:abc123:*")</li>
     *   <li>In-memory: prefix match</li>
     * </ul>
     *
     * @param pattern the pattern to match
     * @return number of keys evicted
     */
    int evictByPattern(String pattern);

    /**
     * Check if this is a distributed cache (cluster-safe).
     * <p>
     * In-memory caches return false; Redis/Hazelcast return true.
     *
     * @return true if distributed across nodes
     */
    boolean isDistributed();

    /**
     * Get cache statistics for monitoring.
     *
     * @return current cache statistics
     */
    CacheStats getStats();

    /**
     * Cache statistics record.
     */
    record CacheStats(
            long hits,
            long misses,
            long evictions,
            long size,
            long memoryUsedBytes,
            double hitRate,
            Instant lastCleared) {

        public static CacheStats empty() {
            return new CacheStats(0, 0, 0, 0, 0, 0.0, null);
        }
    }
}
