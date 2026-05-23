/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.store;

import java.util.Objects;
import java.util.Optional;

import io.agentcore.model.BaseToolResultCache;
import io.agentcore.repository.BaseToolResultCacheRepository;

import lombok.RequiredArgsConstructor;

/**
 * JPA-backed implementation of {@link ToolResultCacheStore} that delegates all operations
 * to a {@link BaseToolResultCacheRepository}.
 *
 * <p>This is the default tool result cache store when {@code spring-boot-starter-data-jpa}
 * is on the classpath. It acts as a thin adapter between the storage-technology-agnostic
 * {@link ToolResultCacheStore} interface and the Spring Data JPA repository.
 *
 * <h3>Replacing this store</h3>
 * <p>Declare a custom {@link ToolResultCacheStore} bean and annotate it with {@code @Primary}
 * to make the framework use it instead of this class:
 * <pre>{@code
 * @Bean
 * @Primary
 * public ToolResultCacheStore<MyToolCache> myRedisToolCacheStore(RedisTemplate<String, String> template) {
 *     return new RedisToolResultCacheStore(template);
 * }
 * }</pre>
 *
 * @param <C> the tool result cache entity type, must extend {@link BaseToolResultCache}
 * @see ToolResultCacheStore
 * @see BaseToolResultCacheRepository
 */
@RequiredArgsConstructor
public class JpaToolResultCacheStore<C extends BaseToolResultCache> implements ToolResultCacheStore<C> {

    private final BaseToolResultCacheRepository<C> repository;

    @Override
    public C save(final C entry) {
        return repository.save(Objects.requireNonNull(entry, "Entry must not be null"));
    }

    @Override
    public Optional<C> findBySessionIdAndCacheKey(final String sessionId, final String cacheKey) {
        return repository.findBySessionIdAndCacheKey(sessionId, cacheKey);
    }

    @Override
    public long deleteBySessionId(final String sessionId) {
        return repository.deleteBySessionId(sessionId);
    }

    @Override
    public long countBySessionId(final String sessionId) {
        return repository.countBySessionId(sessionId);
    }
}
