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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import io.agentcore.model.BaseAgentSession;
import io.agentcore.store.AgentSessionStore;

/**
 * In-memory implementation of {@link AgentSessionStore} for unit and integration tests.
 *
 * <p>Provides a lightweight, database-free session store backed by a {@link ConcurrentHashMap}.
 * Suitable for all test scenarios that don't require persistence across JVM restarts.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @TestConfiguration
 * class TestConfig {
 *
 *     @Bean @Primary
 *     public AgentSessionStore<MySessionDO> sessionStore() {
 *         return new InMemoryAgentSessionStore<>();
 *     }
 * }
 * }</pre>
 *
 * <p>Or instantiate directly in unit tests:
 * <pre>{@code
 * InMemoryAgentSessionStore<MySessionDO> store = new InMemoryAgentSessionStore<>();
 * store.save(mySession);
 * Optional<MySessionDO> found = store.findBySessionId("sess-001");
 * assertThat(found).isPresent();
 * }</pre>
 *
 * @param <S> the session entity type
 */
public class InMemoryAgentSessionStore<S extends BaseAgentSession> implements AgentSessionStore<S> {

    private final Map<String, S> store = new ConcurrentHashMap<>();

    @Override
    public S save(final S session) {
        store.put(session.getSessionId(), session);
        return session;
    }

    @Override
    public Optional<S> findBySessionId(final String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public List<S> findByStatusOrderByUpdatedAtDesc(final String status) {
        return store.values().stream()
                .filter(s -> status.equalsIgnoreCase(s.getStatus()))
                .sorted((a, b) -> {
                    LocalDateTime ta = a.getUpdatedAt();
                    LocalDateTime tb = b.getUpdatedAt();
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return 1;
                    if (tb == null) return -1;
                    return tb.compareTo(ta);
                })
                .toList();
    }

    @Override
    public List<S> findAllByOrderByUpdatedAtDesc() {
        return store.values().stream()
                .sorted((a, b) -> {
                    LocalDateTime ta = a.getUpdatedAt();
                    LocalDateTime tb = b.getUpdatedAt();
                    if (ta == null && tb == null) return 0;
                    if (ta == null) return 1;
                    if (tb == null) return -1;
                    return tb.compareTo(ta);
                })
                .toList();
    }

    @Override
    public List<S> findAllBySessionIdIn(final List<String> sessionIds) {
        return sessionIds.stream()
                .map(store::get)
                .filter(s -> s != null)
                .toList();
    }

    @Override
    public List<S> findByStatusInAndUpdatedAtBefore(
            final List<String> statuses, final LocalDateTime cutoff) {
        return store.values().stream()
                .filter(s -> statuses.stream().anyMatch(status -> status.equalsIgnoreCase(s.getStatus())))
                .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(cutoff))
                .toList();
    }

    @Override
    public void delete(final S session) {
        store.remove(session.getSessionId());
    }

    @Override
    public void deleteAll(final Iterable<? extends S> sessions) {
        StreamSupport.stream(sessions.spliterator(), false)
                .forEach(s -> store.remove(s.getSessionId()));
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the total number of sessions currently in the store.
     */
    public int size() {
        return store.size();
    }

    /**
     * Returns true if the store contains no sessions.
     */
    public boolean isEmpty() {
        return store.isEmpty();
    }

    /**
     * Returns all sessions in the store (unordered).
     */
    public List<S> findAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * Clears all sessions from the store. Useful for resetting state between tests.
     */
    public void clear() {
        store.clear();
    }
}
