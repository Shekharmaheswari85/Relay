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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import io.agentcore.model.BaseAgentSession;

/**
 * Service-provider interface that abstracts all session persistence operations for the
 * agent framework.
 *
 * <p>The orchestration layer interacts exclusively with this interface, so the backing
 * storage technology — relational database (JPA), document store (MongoDB), key-value
 * cache (Redis), DynamoDB, or any other store — can be swapped without modifying
 * framework code.
 *
 * <h3>Framework contract</h3>
 * <ul>
 *   <li>All list-returning methods must return an empty list rather than {@code null}
 *       when no sessions match the query criteria.</li>
 *   <li>{@link #save} must return the stored entity, which may differ from the input when
 *       the store populates generated fields (e.g., database-assigned primary keys or
 *       {@code updatedAt} timestamps).</li>
 *   <li>Implementations must be thread-safe; multiple requests may read and write sessions
 *       concurrently.</li>
 *   <li>Implementations are expected to be Spring beans so that auto-configuration and the
 *       lifecycle service can inject them.</li>
 * </ul>
 *
 * <h3>Built-in implementations</h3>
 * <ul>
 *   <li>{@link JpaAgentSessionStore} — JPA-backed default, activated when
 *       {@code spring-boot-starter-data-jpa} is on the classpath</li>
 *   <li>{@code InMemoryAgentSessionStore} (in the {@code agent-core-test} module) — for
 *       unit and integration tests</li>
 * </ul>
 *
 * <h3>Custom implementation example</h3>
 * <pre>{@code
 * @Service
 * @Primary
 * public class MongoAgentSessionStore implements AgentSessionStore<MySessionDO> {
 *
 *     private final MongoTemplate mongoTemplate;
 *
 *     @Override
 *     public MySessionDO save(MySessionDO session) {
 *         return mongoTemplate.save(session);
 *     }
 *
 *     @Override
 *     public Optional<MySessionDO> findBySessionId(String sessionId) {
 *         Query query = Query.query(Criteria.where("sessionId").is(sessionId));
 *         return Optional.ofNullable(mongoTemplate.findOne(query, MySessionDO.class));
 *     }
 *
 *     // ... remaining methods
 * }
 * }</pre>
 *
 * @param <S> the session entity type, must extend {@link BaseAgentSession}
 * @see JpaAgentSessionStore
 */
public interface AgentSessionStore<S extends BaseAgentSession> {

    /**
     * Creates or updates a session in the backing store.
     *
     * <p>The returned entity reflects the persisted state and may differ from the input
     * when the store sets generated fields such as database primary keys, version columns,
     * or an {@code updatedAt} timestamp.
     *
     * @param session the session entity to persist; never null
     * @return the persisted session entity as returned by the store; never null
     */
    S save(S session);

    /**
     * Retrieves a session by its unique session identifier.
     *
     * @param sessionId the session identifier to look up; never null
     * @return an {@link Optional} containing the session if found, or empty if no
     *         session with the given ID exists in the store
     */
    Optional<S> findBySessionId(String sessionId);

    /**
     * Returns all sessions with the given status, ordered from most-recently-updated to
     * least-recently-updated.
     *
     * @param status the status value to match (e.g. {@code "ACTIVE"}, {@code "COMPLETED"});
     *               never null
     * @return matching sessions in descending update-time order; never null, may be empty
     */
    List<S> findByStatusOrderByUpdatedAtDesc(String status);

    /**
     * Returns all sessions in the store, ordered from most-recently-updated to
     * least-recently-updated.
     *
     * @return all sessions; never null, may be empty
     */
    List<S> findAllByOrderByUpdatedAtDesc();

    /**
     * Returns all sessions whose session ID is contained in {@code sessionIds}.
     *
     * <p>The result may contain fewer entries than requested when some IDs do not exist
     * in the store. The order of results is store-defined.
     *
     * @param sessionIds the session identifiers to look up; never null
     * @return sessions that matched at least one of the given IDs; never null, may be empty
     */
    List<S> findAllBySessionIdIn(List<String> sessionIds);

    /**
     * Returns sessions whose status is one of {@code statuses} and whose last-updated
     * timestamp is strictly before {@code cutoff}.
     *
     * <p>Used by housekeeping jobs to identify stale or abandoned sessions eligible
     * for cleanup or forced termination.
     *
     * @param statuses the set of status values to match; never null
     * @param cutoff   sessions updated strictly before this instant are included; never null
     * @return matching sessions; never null, may be empty
     */
    List<S> findByStatusInAndUpdatedAtBefore(List<String> statuses, LocalDateTime cutoff);

    /**
     * Removes a single session from the store.
     *
     * @param session the session entity to delete; never null
     */
    void delete(S session);

    /**
     * Removes all sessions in the given collection from the store in a single operation.
     *
     * @param sessions the session entities to delete; never null; individual elements
     *                 must not be null
     */
    void deleteAll(Iterable<? extends S> sessions);

    /**
     * Returns {@code true} if a session with the given ID exists in the store.
     *
     * <p>The default implementation delegates to {@link #findBySessionId(String)}.
     * Override with a lighter existence check (e.g. {@code COUNT} query) when the
     * full entity load is undesirable.
     *
     * @param sessionId the session identifier; never null
     * @return {@code true} if the session is present; {@code false} otherwise
     */
    default boolean existsBySessionId(final String sessionId) {
        return findBySessionId(sessionId).isPresent();
    }
}
