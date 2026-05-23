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
package io.agentcore.memory.entity;

import java.util.List;
import java.util.Optional;

/**
 * SPI for persisting and retrieving {@link EntityFact entity facts} — the agent's structured
 * knowledge about named entities across sessions.
 *
 * <p>Entity memory stores the factual record of what the agent has learned about specific
 * entities (products, stores, customers, etc.) during prior interactions. Unlike the broader
 * {@link io.agentcore.memory.AgentMemoryManager}, the entity store supports structured lookups
 * by entity ID, entity type, and attribute name.
 *
 * <h3>Default implementation</h3>
 * <p>{@link InMemoryEntityMemoryStore} is auto-configured when no bean is registered. It is
 * suitable for development and single-node deployments. Production systems should provide a
 * JPA- or Redis-backed implementation.
 *
 * @see EntityFact
 * @see InMemoryEntityMemoryStore
 */
public interface EntityMemoryStore {

    /**
     * Persists an entity fact, replacing any existing fact with the same
     * ({@code entityId}, {@code attribute}) combination.
     *
     * @param fact the fact to store; never {@code null}
     */
    void storeFact(EntityFact fact);

    /**
     * Retrieves all known facts for the given entity, across all attributes and sessions.
     *
     * @param entityId the entity identifier; never {@code null}
     * @return a non-null, possibly empty list of facts for the entity
     */
    List<EntityFact> recallByEntity(String entityId);

    /**
     * Retrieves all facts of the given entity type observed by the given user.
     *
     * @param userId     the user context; never {@code null}
     * @param entityType the entity category (e.g. {@code "PRODUCT"}); never {@code null}
     * @return a non-null, possibly empty list of matching facts
     */
    List<EntityFact> recallByType(String userId, String entityType);

    /**
     * Retrieves all facts observed during the given session.
     *
     * @param sessionId the session to query; never {@code null}
     * @return a non-null, possibly empty list of facts from that session
     */
    List<EntityFact> recallForSession(String sessionId);

    /**
     * Returns the most recent fact for the given entity and attribute, if any.
     *
     * @param entityId  the entity identifier; never {@code null}
     * @param attribute the attribute name; never {@code null}
     * @return an {@link Optional} containing the most recent fact, or empty if none exists
     */
    Optional<EntityFact> recallAttribute(String entityId, String attribute);

    /**
     * Removes all facts observed during the given session.
     *
     * @param sessionId the session to clear; never {@code null}
     */
    void forgetSession(String sessionId);

    /**
     * Removes all facts known about the given entity, across all sessions.
     *
     * @param entityId the entity to forget; never {@code null}
     */
    void forgetEntity(String entityId);
}
