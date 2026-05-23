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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory implementation of {@link EntityMemoryStore} backed by two {@link ConcurrentHashMap}
 * indexes: one by entity ID and one by session ID.
 *
 * <p>All write operations are reflected immediately in both indexes. Lookups by entity ID are
 * O(n) within the entity's fact list; lookups by session are O(n) on the session's fact list.
 * This is sufficient for development and single-node agent runs.
 *
 * <p>This implementation is thread-safe for concurrent reads and writes from multiple virtual
 * threads within the same JVM. It does not support cross-pod memory sharing — use a
 * Redis- or JPA-backed implementation for horizontally-scaled deployments.
 */
@Slf4j
public class InMemoryEntityMemoryStore implements EntityMemoryStore {

    /** Index: entityId -> list of all facts for that entity */
    private final Map<String, List<EntityFact>> byEntity = new ConcurrentHashMap<>();

    /** Index: sessionId -> list of all facts observed in that session */
    private final Map<String, List<EntityFact>> bySession = new ConcurrentHashMap<>();

    /**
     * Persists an entity fact, replacing any existing fact with the same
     * ({@code entityId}, {@code attribute}) combination in both the entity and session indexes.
     *
     * @param fact the fact to store; never {@code null}
     */
    @Override
    public void storeFact(final EntityFact fact) {
        Objects.requireNonNull(fact, "fact must not be null");

        // Update entity index — replace existing fact for same (entityId, attribute) pair
        byEntity.compute(fact.entityId(), (id, existing) -> {
            List<EntityFact> facts = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            facts.removeIf(f -> f.attribute().equals(fact.attribute()));
            facts.add(fact);
            return facts;
        });

        // Update session index
        if (fact.sessionId() != null) {
            bySession.compute(fact.sessionId(), (sid, existing) -> {
                List<EntityFact> facts = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
                facts.removeIf(f -> f.entityId().equals(fact.entityId()) && f.attribute().equals(fact.attribute()));
                facts.add(fact);
                return facts;
            });
        }

        log.debug("EntityFact stored: {}({}).{} = {}", fact.entityType(), fact.entityId(),
                fact.attribute(), fact.value());
    }

    /**
     * Retrieves all known facts for the given entity, across all attributes and sessions.
     *
     * @param entityId the entity identifier; never {@code null}
     * @return a non-null, possibly empty immutable list of facts for the entity
     */
    @Override
    public List<EntityFact> recallByEntity(final String entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        return List.copyOf(byEntity.getOrDefault(entityId, List.of()));
    }

    /**
     * Retrieves all facts of the given entity type observed by the given user, scanning
     * across all entity IDs.
     *
     * @param userId     the user context; never {@code null}
     * @param entityType the entity category (e.g. {@code "PRODUCT"}); never {@code null}
     * @return a non-null, possibly empty list of matching facts
     */
    @Override
    public List<EntityFact> recallByType(final String userId, final String entityType) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        return byEntity.values().stream()
                .flatMap(List::stream)
                .filter(f -> entityType.equalsIgnoreCase(f.entityType())
                        && userId.equals(f.userId()))
                .toList();
    }

    /**
     * Retrieves all facts observed during the given session.
     *
     * @param sessionId the session to query; never {@code null}
     * @return a non-null, possibly empty immutable list of facts from that session
     */
    @Override
    public List<EntityFact> recallForSession(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return List.copyOf(bySession.getOrDefault(sessionId, List.of()));
    }

    /**
     * Returns the most recent fact for the given entity and attribute, if any.
     * Matching is case-insensitive on the attribute name.
     *
     * @param entityId  the entity identifier; never {@code null}
     * @param attribute the attribute name; never {@code null}
     * @return an {@link Optional} containing the most recent fact, or empty if none exists
     */
    @Override
    public Optional<EntityFact> recallAttribute(final String entityId, final String attribute) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
        return byEntity.getOrDefault(entityId, List.of()).stream()
                .filter(f -> attribute.equalsIgnoreCase(f.attribute()))
                .findFirst();
    }

    /**
     * Removes all facts observed during the given session from both the session index and
     * the entity index.
     *
     * @param sessionId the session to clear; never {@code null}
     */
    @Override
    public void forgetSession(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        List<EntityFact> sessionFacts = bySession.remove(sessionId);
        if (sessionFacts != null) {
            for (EntityFact fact : sessionFacts) {
                byEntity.computeIfPresent(fact.entityId(), (id, facts) -> {
                    List<EntityFact> updated = facts.stream()
                            .filter(f -> !sessionId.equals(f.sessionId()))
                            .toList();
                    return updated.isEmpty() ? null : updated;
                });
            }
        }
        log.debug("EntityFacts forgotten for session: {}", sessionId);
    }

    /**
     * Removes all facts known about the given entity from both the entity index and any
     * session indexes that reference it.
     *
     * @param entityId the entity to forget; never {@code null}
     */
    @Override
    public void forgetEntity(final String entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        List<EntityFact> removed = byEntity.remove(entityId);
        if (removed != null) {
            removed.stream()
                    .filter(f -> f.sessionId() != null)
                    .forEach(f -> bySession.computeIfPresent(f.sessionId(), (sid, facts) -> {
                        List<EntityFact> updated = facts.stream()
                                .filter(f2 -> !entityId.equals(f2.entityId()))
                                .toList();
                        return updated.isEmpty() ? null : updated;
                    }));
        }
        log.debug("EntityFacts forgotten for entity: {}", entityId);
    }
}
