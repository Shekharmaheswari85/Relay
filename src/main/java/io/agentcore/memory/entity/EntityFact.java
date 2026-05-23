/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.memory.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable structured fact about a named entity known to the agent.
 *
 * <p>Entity facts give the agent persistent knowledge about specific entities — products,
 * stores, customers, systems, or concepts — beyond what is available in the current
 * context window. Facts are keyed by entity ID and type, making them retrievable by both
 * exact lookup and type-based queries.
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * EntityFact.of("sku-123",    "PRODUCT",  "stockStatus",    "OUT_OF_STOCK",  sessionId, userId);
 * EntityFact.of("store-4521", "STORE",    "region",         "Southeast",     sessionId, userId);
 * EntityFact.of("user-xyz",   "CUSTOMER", "preferredStore", "store-4521",    sessionId, userId);
 * EntityFact.of("q3-2026",    "PERIOD",   "returnsRate",    "4.2% above avg",sessionId, userId);
 * }</pre>
 *
 * @param entityId   the canonical identifier of the entity (e.g. {@code "sku-123"});
 *                   never {@code null}
 * @param entityType the category of the entity in uppercase (e.g. {@code "PRODUCT"},
 *                   {@code "STORE"}, {@code "CUSTOMER"}); never {@code null}
 * @param attribute  the property being recorded (e.g. {@code "stockStatus"}); never
 *                   {@code null}
 * @param value      the attribute's current value; never {@code null}
 * @param sessionId  the session in which this fact was observed; may be {@code null} for
 *                   facts derived outside a session context
 * @param userId     the user associated with this fact; may be {@code null}
 * @param recordedAt when this fact was recorded; never {@code null}
 */
public record EntityFact(
        String entityId,
        String entityType,
        String attribute,
        String value,
        String sessionId,
        String userId,
        Instant recordedAt) {

    /** Validates required fields. */
    public EntityFact {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(attribute, "attribute must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }

    /**
     * Creates an entity fact with the current timestamp.
     *
     * @param entityId   canonical entity identifier
     * @param entityType entity category in uppercase
     * @param attribute  the attribute being recorded
     * @param value      the attribute's current value
     * @param sessionId  the originating session; may be {@code null}
     * @param userId     the associated user; may be {@code null}
     * @return a new {@code EntityFact} timestamped to now
     */
    public static EntityFact of(final String entityId, final String entityType,
                                 final String attribute, final String value,
                                 final String sessionId, final String userId) {
        return new EntityFact(entityId, entityType, attribute, value, sessionId, userId, Instant.now());
    }

    /**
     * Returns a human-readable representation suitable for LLM prompt injection.
     * Format: {@code ENTITYTYPE(entityId).attribute = value}
     *
     * @return formatted string; never {@code null}
     */
    public String toPromptFragment() {
        return entityType + "(" + entityId + ")." + attribute + " = " + value;
    }
}
