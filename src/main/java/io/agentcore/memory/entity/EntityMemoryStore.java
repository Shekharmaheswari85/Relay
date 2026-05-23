/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
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
