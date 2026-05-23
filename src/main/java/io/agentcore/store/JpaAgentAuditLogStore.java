/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.store;

import java.util.List;
import java.util.Objects;

import io.agentcore.model.BaseAgentAuditLog;
import io.agentcore.repository.BaseAgentAuditLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * JPA-backed implementation of {@link AgentAuditLogStore} that delegates all operations
 * to a {@link BaseAgentAuditLogRepository}.
 *
 * <p>This is the default audit log store when {@code spring-boot-starter-data-jpa} is on
 * the classpath. It acts as a thin adapter between the storage-technology-agnostic
 * {@link AgentAuditLogStore} interface and the Spring Data JPA repository.
 *
 * <h3>Replacing this store</h3>
 * <p>Declare a custom {@link AgentAuditLogStore} bean and annotate it with {@code @Primary}
 * to make the framework use it instead of this class:
 * <pre>{@code
 * @Bean
 * @Primary
 * public AgentAuditLogStore<MyAuditLog> myCustomAuditLogStore(MongoTemplate mongoTemplate) {
 *     return new MongoAuditLogStore(mongoTemplate);
 * }
 * }</pre>
 *
 * @param <A> the audit log entity type, must extend {@link BaseAgentAuditLog}
 * @see AgentAuditLogStore
 * @see BaseAgentAuditLogRepository
 */
@RequiredArgsConstructor
public class JpaAgentAuditLogStore<A extends BaseAgentAuditLog> implements AgentAuditLogStore<A> {

    private final BaseAgentAuditLogRepository<A> repository;

    @Override
    public A save(final A log) {
        return repository.save(Objects.requireNonNull(log, "Log must not be null"));
    }

    @Override
    public List<A> findBySessionId(final String sessionId) {
        return repository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Override
    public List<A> findBySessionIdAndEventType(final String sessionId, final String eventType) {
        return repository.findBySessionIdAndEventTypeOrderByCreatedAtAsc(sessionId, eventType);
    }

    @Override
    public void deleteAll(final Iterable<? extends A> logs) {
        repository.deleteAll(Objects.requireNonNull(logs, "Logs must not be null"));
    }

    @Override
    public long deleteBySessionId(final String sessionId) {
        return repository.deleteBySessionId(sessionId);
    }
}
