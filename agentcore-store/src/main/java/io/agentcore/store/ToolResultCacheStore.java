/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.store;

import java.util.Optional;

import io.agentcore.model.BaseToolResultCache;

/**
 * Service-provider interface that abstracts all tool-result cache persistence operations
 * for the agent framework.
 *
 * <p>The tool cache service interacts exclusively with this interface, so the backing
 * storage technology — relational database (JPA), document store (MongoDB), Redis, or
 * any other backend — can be swapped without modifying framework code.
 *
 * <p>Each cache entry is uniquely identified by the combination of {@code sessionId} and
 * {@code cacheKey}. The cache key encodes the tool name and a hash of its input arguments
 * (format: {@code sessionId::toolName::inputHash}) so that the same tool called with
 * different inputs produces independent cache entries.
 *
 * <h3>Framework contract</h3>
 * <ul>
 *   <li>{@link #save} must return the stored entity, including any store-generated fields.</li>
 *   <li>Implementations must be thread-safe.</li>
 *   <li>A cache miss must return {@link Optional#empty()}, never throw.</li>
 * </ul>
 *
 * <h3>Built-in implementations</h3>
 * <ul>
 *   <li>{@link JpaToolResultCacheStore} — JPA-backed default, activated when
 *       {@code spring-boot-starter-data-jpa} is on the classpath and a concrete
 *       {@code BaseToolResultCacheRepository} bean is registered</li>
 *   <li>{@code io.agentcore.test.InMemoryToolResultCacheStore} — for unit and integration tests</li>
 * </ul>
 *
 * <h3>Custom implementation example (Redis)</h3>
 * <pre>{@code
 * @Service
 * @Primary
 * public class RedisToolResultCacheStore implements ToolResultCacheStore<MyToolCacheEntry> {
 *
 *     private final StringRedisTemplate redisTemplate;
 *     private final ObjectMapper objectMapper;
 *
 *     @Override
 *     public Optional<MyToolCacheEntry> findBySessionIdAndCacheKey(String sessionId, String cacheKey) {
 *         String value = redisTemplate.opsForValue().get(sessionId + ":" + cacheKey);
 *         if (value == null) return Optional.empty();
 *         return Optional.of(objectMapper.readValue(value, MyToolCacheEntry.class));
 *     }
 *
 *     // ... remaining methods
 * }
 * }</pre>
 *
 * @param <C> the tool result cache entity type, must extend {@link BaseToolResultCache}
 * @see JpaToolResultCacheStore
 */
public interface ToolResultCacheStore<C extends BaseToolResultCache> {

    /**
     * Creates or updates a cache entry in the backing store.
     *
     * <p>The returned entity reflects the persisted state and may differ from the input
     * when the store sets generated fields such as database primary keys or timestamps.
     *
     * @param entry the cache entry to persist; never null
     * @return the persisted cache entry as returned by the store; never null
     */
    C save(C entry);

    /**
     * Looks up a previously cached tool result by session and cache key.
     *
     * <p>This is the hot-path read used on every LLM retry or session resume to determine
     * whether the tool needs to be re-executed. Returns empty on a cache miss.
     *
     * @param sessionId the session identifier; never null
     * @param cacheKey  the compound key ({@code sessionId::toolName::inputHash}); never null
     * @return the cached entity if present; {@link Optional#empty()} on a cache miss
     */
    Optional<C> findBySessionIdAndCacheKey(String sessionId, String cacheKey);

    /**
     * Permanently removes all cache entries that belong to the given session.
     *
     * <p>Callers should invoke this whenever a session is deleted or transitions to a
     * terminal state so that orphaned cache rows do not accumulate.
     *
     * @param sessionId the session identifier whose cache rows should be removed; never null
     * @return the number of entries deleted; {@code 0} if no entries existed for that session
     */
    long deleteBySessionId(String sessionId);

    /**
     * Counts the number of cached tool results stored for the given session.
     *
     * <p>Primarily used for diagnostics and observability.
     *
     * @param sessionId the session identifier to count entries for; never null
     * @return the number of cache entries for that session; {@code 0} if none exist
     */
    long countBySessionId(String sessionId);
}
