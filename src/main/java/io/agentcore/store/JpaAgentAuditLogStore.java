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
