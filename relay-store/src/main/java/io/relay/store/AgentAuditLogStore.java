/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.store;

import java.util.List;

import io.relay.model.BaseAgentAuditLog;

/**
 * Service-provider interface that abstracts all audit log persistence operations for the
 * agent framework.
 *
 * <p>The lifecycle service interacts exclusively with this interface, so the backing
 * storage technology — relational database (JPA), document store (MongoDB), key-value
 * store, or any other backend — can be swapped without modifying framework code.
 *
 * <h3>Framework contract</h3>
 * <ul>
 *   <li>All list-returning methods must return an empty list rather than {@code null}
 *       when no entries match the query criteria.</li>
 *   <li>{@link #save} must return the stored entity, which may differ from the input when
 *       the store populates generated fields (e.g., database-assigned primary keys or
 *       {@code createdAt} timestamps).</li>
 *   <li>Implementations must be thread-safe; multiple requests may write audit entries
 *       concurrently.</li>
 *   <li>{@link #findBySessionId} must return entries in chronological order
 *       (oldest first) to reconstruct the event timeline.</li>
 * </ul>
 *
 * <h3>Built-in implementations</h3>
 * <ul>
 *   <li>{@link JpaAgentAuditLogStore} — JPA-backed default, activated when
 *       {@code spring-boot-starter-data-jpa} is on the classpath and a concrete
 *       {@code BaseAgentAuditLogRepository} bean is registered</li>
 *   <li>{@code io.relay.test.InMemoryAgentAuditLogStore} — for unit and integration tests</li>
 * </ul>
 *
 * <h3>Custom implementation example (MongoDB)</h3>
 * <pre>{@code
 * @Service
 * @Primary
 * public class MongoAuditLogStore implements AgentAuditLogStore<MyAuditLogDocument> {
 *
 *     private final MongoTemplate mongoTemplate;
 *
 *     @Override
 *     public MyAuditLogDocument save(MyAuditLogDocument log) {
 *         return mongoTemplate.save(log);
 *     }
 *
 *     @Override
 *     public List<MyAuditLogDocument> findBySessionId(String sessionId) {
 *         Query query = Query.query(Criteria.where("sessionId").is(sessionId))
 *                 .with(Sort.by(Sort.Direction.ASC, "createdAt"));
 *         return mongoTemplate.find(query, MyAuditLogDocument.class);
 *     }
 *
 *     // ... remaining methods
 * }
 * }</pre>
 *
 * @param <A> the audit log entity type, must extend {@link BaseAgentAuditLog}
 * @see JpaAgentAuditLogStore
 */
public interface AgentAuditLogStore<A extends BaseAgentAuditLog> {

    /**
     * Persists a new audit log entry.
     *
     * <p>The returned entity reflects the persisted state and may differ from the input
     * when the store sets generated fields such as database primary keys or
     * {@code createdAt} timestamps.
     *
     * @param log the audit log entity to persist; never null
     * @return the persisted audit log entity as returned by the store; never null
     */
    A save(A log);

    /**
     * Returns the complete audit trail for a session in chronological order (oldest first).
     *
     * <p>Used by audit-trail REST endpoints and the cleanup scheduler to retrieve all
     * events for a session before bulk deletion.
     *
     * @param sessionId the session identifier whose events should be retrieved; never null
     * @return a possibly-empty list of audit entries ordered oldest-first; never null
     */
    List<A> findBySessionId(String sessionId);

    /**
     * Returns audit entries for a session that match a specific event type, in
     * chronological order (oldest first).
     *
     * <p>Typical use cases: retrieve only {@code TOOL_CALL} events to compute tool
     * execution cost, or only {@code ERROR} events for failure analysis.
     *
     * @param sessionId the session identifier; never null
     * @param eventType the classifier to filter on (e.g. {@code "TOOL_CALL"},
     *                  {@code "LLM_CALL"}, {@code "ERROR"}); never null
     * @return a possibly-empty list of matching audit entries ordered oldest-first; never null
     */
    List<A> findBySessionIdAndEventType(String sessionId, String eventType);

    /**
     * Removes all audit log entries in the given collection from the store.
     *
     * @param logs the entries to delete; never null; individual elements must not be null
     */
    void deleteAll(Iterable<? extends A> logs);

    /**
     * Permanently removes all audit entries that belong to the given session.
     *
     * <p>Callers should invoke this as part of session cleanup so that orphaned
     * audit rows do not accumulate.
     *
     * @param sessionId the session identifier whose audit trail should be erased; never null
     * @return the number of entries deleted; {@code 0} if no entries existed for that session
     */
    long deleteBySessionId(String sessionId);
}
