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
package io.agentcore.test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.agentcore.model.BaseToolResultCache;
import io.agentcore.store.ToolResultCacheStore;

/**
 * In-memory implementation of {@link ToolResultCacheStore} for unit and integration tests.
 *
 * <p>Provides a lightweight, database-free tool result cache backed by a
 * {@link ConcurrentHashMap} keyed on {@code sessionId::cacheKey}. Suitable for all test
 * scenarios that do not require persistence across JVM restarts.
 *
 * <h3>Usage in a Spring test</h3>
 * <pre>{@code
 * @TestConfiguration
 * class TestConfig {
 *
 *     @Bean @Primary
 *     public ToolResultCacheStore<AgentToolResultCacheDO> toolResultCacheStore() {
 *         return new InMemoryToolResultCacheStore<>();
 *     }
 * }
 * }</pre>
 *
 * <h3>Usage in a plain unit test</h3>
 * <pre>{@code
 * InMemoryToolResultCacheStore<AgentToolResultCacheDO> store = new InMemoryToolResultCacheStore<>();
 * store.save(AgentToolResultCacheDO.builder()
 *         .sessionId("sess-001")
 *         .cacheKey("myTool::abc123")
 *         .resultJson("{\"value\":42}")
 *         .build());
 * Optional<AgentToolResultCacheDO> hit = store.findBySessionIdAndCacheKey("sess-001", "myTool::abc123");
 * assertThat(hit).isPresent();
 * }</pre>
 *
 * @param <C> the tool result cache entity type
 */
public class InMemoryToolResultCacheStore<C extends BaseToolResultCache> implements ToolResultCacheStore<C> {

    private final Map<String, C> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    @Override
    public C save(final C entry) {
        if (entry.getId() == null) {
            entry.setId(idSequence.getAndIncrement());
        }
        LocalDateTime now = LocalDateTime.now();
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(now);
        }
        entry.setUpdatedAt(now);
        store.put(storeKey(entry.getSessionId(), entry.getCacheKey()), entry);
        return entry;
    }

    @Override
    public Optional<C> findBySessionIdAndCacheKey(final String sessionId, final String cacheKey) {
        return Optional.ofNullable(store.get(storeKey(sessionId, cacheKey)));
    }

    @Override
    public long deleteBySessionId(final String sessionId) {
        long[] count = {0};
        store.entrySet().removeIf(e -> {
            if (sessionId.equals(e.getValue().getSessionId())) {
                count[0]++;
                return true;
            }
            return false;
        });
        return count[0];
    }

    @Override
    public long countBySessionId(final String sessionId) {
        return store.values().stream()
                .filter(c -> sessionId.equals(c.getSessionId()))
                .count();
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    /** Returns the total number of cache entries in the store. */
    public int size() {
        return store.size();
    }

    /** Returns {@code true} if the store contains no entries. */
    public boolean isEmpty() {
        return store.isEmpty();
    }

    /** Clears all entries from the store. Useful for resetting state between tests. */
    public void clear() {
        store.clear();
        idSequence.set(1);
    }

    private String storeKey(final String sessionId, final String cacheKey) {
        return sessionId + "::" + cacheKey;
    }
}
