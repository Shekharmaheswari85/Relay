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
package io.agentcore.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import io.agentcore.model.BaseAgentAuditLog;

/**
 * Generic Spring Data base repository for agent audit log entities.
 *
 * <p>Annotated with {@code @NoRepositoryBean} so that Spring Data does not instantiate
 * this interface directly.  Each agent module that persists audit events must declare a
 * concrete sub-interface bound to its own audit-log entity:
 *
 * <pre>{@code
 * @Repository
 * public interface MyAgentAuditLogRepository
 *         extends BaseAgentAuditLogRepository<MyAgentAuditLogDO> {
 *
 *     // Optional domain-specific queries, e.g.:
 *     List<MyAgentAuditLogDO> findBySessionIdAndToolName(String sessionId, String toolName);
 * }
 * }</pre>
 *
 * <p>Inherits the full {@link JpaRepository} API and adds the session-scoped audit
 * queries that the framework's audit-trail endpoints and cleanup scheduler require.
 *
 * @param <T> the concrete audit log entity type that extends {@link BaseAgentAuditLog}
 */
@NoRepositoryBean
public interface BaseAgentAuditLogRepository<T extends BaseAgentAuditLog> extends JpaRepository<T, Long> {

    /**
     * Returns the complete audit trail for a session in chronological order.
     * <p>
     * The resulting list, ordered ascending by {@code createdAt}, reconstructs the
     * exact sequence of LLM calls, tool invocations, and state transitions that
     * occurred during the session.  Used by the audit-trail REST endpoint and the
     * observability service.
     *
     * @param sessionId the session identifier whose events should be retrieved
     * @return a possibly-empty list of audit entries ordered oldest-first
     */
    List<T> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * Returns audit events for a session that match a specific event type, in
     * chronological order.
     * <p>
     * Typical use cases: retrieve only {@code TOOL_CALL} events to compute tool
     * execution cost, or only {@code ERROR} events for failure analysis.
     * Valid event type values are documented on {@code BaseAgentAuditLog#eventType}.
     *
     * @param sessionId the session identifier whose events should be retrieved
     * @param eventType the classifier to filter on (e.g. {@code "TOOL_CALL"},
     *                  {@code "LLM_CALL"}, {@code "ERROR"})
     * @return a possibly-empty list of matching audit entries ordered oldest-first
     */
    List<T> findBySessionIdAndEventTypeOrderByCreatedAtAsc(String sessionId, String eventType);

    /**
     * Permanently removes all audit entries that belong to the given session.
     * <p>
     * Intended to be called as part of single-session cleanup, typically when a
     * completed or failed session is purged from the system.  This operation is
     * transactional by Spring Data convention.
     *
     * @param sessionId the session identifier whose audit trail should be erased
     * @return the number of rows deleted; {@code 0} if no entries existed for that session
     */
    long deleteBySessionId(String sessionId);

    /**
     * Permanently removes all audit entries that belong to any of the given sessions.
     * <p>
     * Used by the bulk-delete endpoint and the expiry scheduler to purge multiple
     * sessions in a single database round-trip rather than issuing one delete per session.
     * This operation is transactional by Spring Data convention.
     *
     * @param sessionIds the collection of session identifiers whose audit trails should
     *                   be erased; an empty list results in no deletions
     * @return the total number of rows deleted across all given sessions
     */
    long deleteBySessionIdIn(List<String> sessionIds);
}
