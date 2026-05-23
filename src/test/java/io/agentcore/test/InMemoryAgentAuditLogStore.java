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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import io.agentcore.model.BaseAgentAuditLog;
import io.agentcore.store.AgentAuditLogStore;

/**
 * In-memory implementation of {@link AgentAuditLogStore} for unit and integration tests.
 *
 * <p>Provides a lightweight, database-free audit log store backed by a
 * {@link CopyOnWriteArrayList}. Suitable for all test scenarios that do not require
 * persistence across JVM restarts.
 *
 * <h3>Usage in a Spring test</h3>
 * <pre>{@code
 * @TestConfiguration
 * class TestConfig {
 *
 *     @Bean @Primary
 *     public AgentAuditLogStore<MyAuditLogDO> auditLogStore() {
 *         return new InMemoryAgentAuditLogStore<>();
 *     }
 * }
 * }</pre>
 *
 * <h3>Usage in a plain unit test</h3>
 * <pre>{@code
 * InMemoryAgentAuditLogStore<MyAuditLogDO> store = new InMemoryAgentAuditLogStore<>();
 * store.save(MyAuditLogDO.builder().sessionId("sess-001").eventType("TOOL_CALL").build());
 * List<MyAuditLogDO> logs = store.findBySessionId("sess-001");
 * assertThat(logs).hasSize(1);
 * }</pre>
 *
 * @param <A> the audit log entity type
 */
public class InMemoryAgentAuditLogStore<A extends BaseAgentAuditLog> implements AgentAuditLogStore<A> {

    private final CopyOnWriteArrayList<A> store = new CopyOnWriteArrayList<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    @Override
    public A save(final A log) {
        if (log.getId() == null) {
            log.setId(idSequence.getAndIncrement());
        }
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(LocalDateTime.now());
        }
        store.removeIf(existing -> existing.getId() != null && existing.getId().equals(log.getId()));
        store.add(log);
        return log;
    }

    @Override
    public List<A> findBySessionId(final String sessionId) {
        return store.stream()
                .filter(a -> sessionId.equals(a.getSessionId()))
                .sorted(Comparator.comparing(BaseAgentAuditLog::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public List<A> findBySessionIdAndEventType(final String sessionId, final String eventType) {
        return store.stream()
                .filter(a -> sessionId.equals(a.getSessionId())
                        && eventType.equalsIgnoreCase(a.getEventType()))
                .sorted(Comparator.comparing(BaseAgentAuditLog::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public void deleteAll(final Iterable<? extends A> logs) {
        List<Long> idsToRemove = StreamSupport.stream(logs.spliterator(), false)
                .map(BaseAgentAuditLog::getId)
                .toList();
        store.removeIf(a -> a.getId() != null && idsToRemove.contains(a.getId()));
    }

    @Override
    public long deleteBySessionId(final String sessionId) {
        List<A> toRemove = store.stream()
                .filter(a -> sessionId.equals(a.getSessionId()))
                .toList();
        store.removeAll(toRemove);
        return toRemove.size();
    }

    // ─── Test helpers ─────────────────────────────────────────────────────────

    /** Returns all log entries currently in the store (unordered). */
    public List<A> findAll() {
        return new ArrayList<>(store);
    }

    /** Returns the total number of entries in the store. */
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
}
