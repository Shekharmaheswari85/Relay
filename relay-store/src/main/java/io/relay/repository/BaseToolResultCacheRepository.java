/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.relay.model.BaseToolResultCache;

/**
 * Generic Spring Data base repository for tool result cache entities.
 *
 * <p>Annotated with {@code @NoRepositoryBean} so that Spring Data does not instantiate
 * this interface directly.  Modules that extend {@link BaseToolResultCache} with
 * domain-specific columns should also extend this interface:
 *
 * <pre>{@code
 * @Repository
 * public interface MyToolResultCacheRepository
 *         extends BaseToolResultCacheRepository<MyToolResultCacheDO> {
 * }
 * }</pre>
 *
 * <p>Modules that use the built-in {@link io.relay.model.AgentToolResultCacheDO}
 * entity without extension should use {@link AgentToolResultCacheRepository} directly
 * instead.
 *
 * @param <T> the concrete cache entity type that extends {@link BaseToolResultCache}
 */
@NoRepositoryBean
public interface BaseToolResultCacheRepository<T extends BaseToolResultCache> extends JpaRepository<T, Long> {

    /**
     * Looks up a previously cached tool result by session and cache key.
     * <p>
     * This is the hot-path read used on every LLM retry or session resume to
     * determine whether the tool needs to be re-executed.  The cache key format
     * is {@code toolName::inputHash} as constructed by the framework's
     * {@code DefaultToolResultCache}.
     *
     * @param sessionId the session identifier (matches {@code BaseToolResultCache#sessionId})
     * @param cacheKey  the compound key identifying this specific tool invocation
     *                  within the session ({@code toolName::inputHash})
     * @return the cached entity if a prior result exists for this combination, or
     *         {@link Optional#empty()} on a cache miss
     */
    Optional<T> findBySessionIdAndCacheKey(String sessionId, String cacheKey);

    /**
     * Permanently removes all cache entries that belong to the given session.
     * <p>
     * Executed as a bulk JPQL delete ({@code DELETE FROM ... WHERE sessionId = :sessionId})
     * rather than a derived query to avoid loading entities into memory before deletion.
     * Must be called inside a transaction; the {@code @Transactional} annotation on this
     * method opens one if none is active.
     * <p>
     * Callers should invoke this whenever a session is cleaned up or expired so that
     * orphaned cache rows do not accumulate.
     *
     * @param sessionId the session identifier whose cache rows should be removed
     * @return the number of rows deleted; {@code 0} if no entries existed for that session
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM #{#entityName} e WHERE e.sessionId = :sessionId")
    long deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * Counts the number of cached tool results stored for the given session.
     * <p>
     * Primarily used for diagnostics and observability — e.g. to report cache size
     * in a session status response or to decide whether a session is safe to resume.
     *
     * @param sessionId the session identifier to count entries for
     * @return the number of cache rows associated with that session;
     *         {@code 0} if none exist
     */
    long countBySessionId(String sessionId);
}
