/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

import io.relay.model.BaseAgentSession;
import io.relay.repository.BaseAgentSessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * JPA-backed implementation of {@link AgentSessionStore} that delegates all operations
 * to a {@link BaseAgentSessionRepository}.
 *
 * <p>This is the default session store when {@code spring-boot-starter-data-jpa} is on the
 * classpath. It acts as a thin adapter layer between the storage-technology-agnostic
 * {@link AgentSessionStore} interface and the Spring Data JPA repository, ensuring full
 * backward compatibility with teams that already use {@link BaseAgentSessionRepository}
 * directly.
 *
 * <h3>Replacing this store</h3>
 * <p>Declare a custom {@link AgentSessionStore} bean and annotate it with {@code @Primary}
 * to make the framework use it instead of this class:
 * <pre>{@code
 * @Bean
 * @Primary
 * public AgentSessionStore<MySession> myCustomStore(MongoTemplate mongoTemplate) {
 *     return new MongoAgentSessionStore(mongoTemplate);
 * }
 * }</pre>
 *
 * @param <S> the session entity type, must extend {@link BaseAgentSession}
 * @see AgentSessionStore
 * @see BaseAgentSessionRepository
 */
@RequiredArgsConstructor
public class JpaAgentSessionStore<S extends BaseAgentSession> implements AgentSessionStore<S> {

    private final BaseAgentSessionRepository<S> repository;

    @Override
    public S save(final S session) {
        return repository.save(Objects.requireNonNull(session, "Session must not be null"));
    }

    @Override
    public Optional<S> findBySessionId(final String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    @Override
    public List<S> findByStatusOrderByUpdatedAtDesc(final String status) {
        return repository.findByStatusOrderByUpdatedAtDesc(status);
    }

    @Override
    public List<S> findAllByOrderByUpdatedAtDesc() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    @Override
    public List<S> findAllBySessionIdIn(final List<String> sessionIds) {
        return repository.findAllBySessionIdIn(sessionIds);
    }

    @Override
    public List<S> findByStatusInAndUpdatedAtBefore(
            final List<String> statuses, final LocalDateTime cutoff) {
        return repository.findByStatusInAndUpdatedAtBefore(statuses, cutoff);
    }

    @Override
    public void delete(final S session) {
        repository.delete(Objects.requireNonNull(session, "Session must not be null"));
    }

    @Override
    public void deleteAll(final Iterable<? extends S> sessions) {
        repository.deleteAll(Objects.requireNonNull(sessions, "Sessions must not be null"));
    }
}
