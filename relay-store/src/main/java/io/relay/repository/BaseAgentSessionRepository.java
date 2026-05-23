/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import io.relay.model.BaseAgentSession;

/**
 * Generic Spring Data base repository for agent session entities.
 *
 * <p>Annotated with {@code @NoRepositoryBean} so that Spring Data does not attempt to
 * instantiate this interface directly.  Every agent module must declare a concrete
 * sub-interface that binds the type parameter to its own session entity.
 *
 * <pre>{@code
 * @Repository
 * public interface MyAgentSessionRepository
 *         extends BaseAgentSessionRepository<MyAgentSessionDO> {
 *
 *     // Domain-specific query methods go here, e.g.:
 *     List<MyAgentSessionDO> findByTicketId(String ticketId);
 * }
 * }</pre>
 *
 * <p>In addition to the full {@link JpaRepository} API this interface declares the
 * session-management queries that every agent implementation relies on.
 *
 * @param <T> the concrete session entity type that extends {@link BaseAgentSession}
 */
@NoRepositoryBean
public interface BaseAgentSessionRepository<T extends BaseAgentSession> extends JpaRepository<T, Long> {

    /**
     * Looks up a session by its application-level identifier.
     * <p>
     * This is the primary lookup used by the framework when routing an inbound
     * message to the correct session.
     *
     * @param sessionId the externally visible session identifier
     *                  (matches {@code BaseAgentSession#sessionId})
     * @return the matching session wrapped in an {@link Optional}, or
     *         {@link Optional#empty()} if no session exists with that ID
     */
    Optional<T> findBySessionId(String sessionId);

    /**
     * Returns all sessions that are currently in the given lifecycle status.
     * <p>
     * Valid status values are {@code ACTIVE}, {@code PAUSED}, {@code COMPLETED},
     * and {@code FAILED}, as documented on {@code BaseAgentSession#status}.
     * Result order is not guaranteed; use
     * {@link #findByStatusOrderByUpdatedAtDesc(String)} when recency matters.
     *
     * @param status the session status to filter by; must not be {@code null}
     * @return a possibly-empty list of matching sessions
     */
    List<T> findByStatus(String status);

    /**
     * Returns all sessions in the given status, sorted by most-recently-updated first.
     * <p>
     * Intended for administrative dashboards and monitoring endpoints that need to
     * display the most active or recently changed sessions at the top.
     *
     * @param status the session status to filter by; must not be {@code null}
     * @return a possibly-empty list of matching sessions, newest first
     */
    List<T> findByStatusOrderByUpdatedAtDesc(String status);

    /**
     * Returns every session in the table, sorted by most-recently-updated first.
     * <p>
     * Intended for administrative listing endpoints.  For large tables, prefer a
     * paginated variant backed by {@link org.springframework.data.domain.Pageable}.
     *
     * @return a possibly-empty list of all sessions, newest first
     */
    List<T> findAllByOrderByUpdatedAtDesc();

    /**
     * Batch-fetches sessions by a set of session IDs.
     * <p>
     * Useful when a calling service holds a list of session IDs (e.g. from a cache
     * or message header) and needs to hydrate them in a single database round-trip.
     *
     * @param sessionIds the collection of session identifiers to retrieve;
     *                   an empty list returns an empty result
     * @return a possibly-empty list of sessions whose {@code sessionId} is in the
     *         given collection; order is not guaranteed
     */
    List<T> findAllBySessionIdIn(List<String> sessionIds);

    /**
     * Finds sessions that have not been modified since the given timestamp and whose
     * status is one of the supplied values.
     * <p>
     * Used by {@code BaseSessionExpiryScheduler} to identify stale or abandoned
     * sessions that should be expired or archived.  A typical invocation filters on
     * {@code ["ACTIVE", "PAUSED"]} with a cutoff of {@code now() minus idle-timeout}.
     *
     * @param statuses a non-empty list of status values to include in the search
     * @param cutoff   sessions last updated strictly before this timestamp are returned
     * @return a possibly-empty list of sessions matching both conditions
     */
    List<T> findByStatusInAndUpdatedAtBefore(List<String> statuses, LocalDateTime cutoff);

    /**
     * Permanently removes the session row identified by the given session ID.
     * <p>
     * This method is transactional by Spring Data convention.  Callers should also
     * arrange deletion of related rows (audit logs, tool cache entries) in the same
     * transaction to maintain referential consistency.
     *
     * @param sessionId the externally visible identifier of the session to remove
     * @return {@code 1} if a row was deleted, {@code 0} if no session with that ID exists
     */
    long deleteBySessionId(String sessionId);
}
