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
