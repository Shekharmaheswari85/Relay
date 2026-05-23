/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable unit of agent memory — a single fact, preference, or experience that the agent
 * can store and retrieve across turns and sessions.
 *
 * <p>Every entry belongs to exactly one {@link MemoryType} and is scoped to a session, a user,
 * or both. The {@code key} field is optional and enables exact lookups when content identity
 * matters (e.g. a unique entity ID). The {@code score} field is populated during retrieval to
 * indicate relevance; newly written entries carry a score of {@code 1.0}.
 *
 * <h3>Creating entries</h3>
 * <p>Use the static factory methods rather than the canonical constructor:
 * <pre>{@code
 * // Entity fact scoped to a session
 * MemoryEntry.forSession(MemoryType.ENTITY, sessionId, "Product sku-123 is out of stock");
 *
 * // Persona preference scoped to a user
 * MemoryEntry.forUser(MemoryType.PERSONA, userId, "Prefers concise bullet-point responses");
 *
 * // Full entry with all fields
 * MemoryEntry.of(MemoryType.WORKFLOW, sessionId, userId, "Checked inventory before pricing");
 * }</pre>
 *
 * @param type      the memory classification; never {@code null}
 * @param sessionId the session this entry is associated with; may be {@code null} for user-scoped entries
 * @param userId    the user this entry is associated with; may be {@code null} for session-scoped entries
 * @param key       optional discriminator for exact lookups (e.g. an entity ID); may be {@code null}
 * @param content   the natural-language memory content; never {@code null}
 * @param metadata  additional key-value pairs for filtering or enrichment; never {@code null}, may be empty
 * @param score     relevance score in [0.0, 1.0]; {@code 1.0} for newly written entries
 * @param timestamp when this entry was created; never {@code null}
 */
public record MemoryEntry(
        MemoryType type,
        String sessionId,
        String userId,
        String key,
        String content,
        Map<String, Object> metadata,
        double score,
        Instant timestamp) {

    /** Canonical constructor — validates required fields. */
    public MemoryEntry {
        Objects.requireNonNull(type, "MemoryType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    // ─── Factory methods ──────────────────────────────────────────────────────

    /**
     * Creates a session-scoped entry without a user or key.
     *
     * @param type      the memory type; never {@code null}
     * @param sessionId the session identifier; never {@code null}
     * @param content   the memory content; never {@code null}
     * @return a new entry with score {@code 1.0} and the current timestamp
     */
    public static MemoryEntry forSession(final MemoryType type, final String sessionId, final String content) {
        return new MemoryEntry(type, sessionId, null, null, content, Map.of(), 1.0, Instant.now());
    }

    /**
     * Creates a user-scoped entry without a session or key.
     *
     * @param type    the memory type; never {@code null}
     * @param userId  the user identifier; never {@code null}
     * @param content the memory content; never {@code null}
     * @return a new entry with score {@code 1.0} and the current timestamp
     */
    public static MemoryEntry forUser(final MemoryType type, final String userId, final String content) {
        return new MemoryEntry(type, null, userId, null, content, Map.of(), 1.0, Instant.now());
    }

    /**
     * Creates an entry scoped to both a session and a user.
     *
     * @param type      the memory type; never {@code null}
     * @param sessionId the session identifier; may be {@code null}
     * @param userId    the user identifier; may be {@code null}
     * @param content   the memory content; never {@code null}
     * @return a new entry with score {@code 1.0} and the current timestamp
     */
    public static MemoryEntry of(final MemoryType type, final String sessionId, final String userId,
                                  final String content) {
        return new MemoryEntry(type, sessionId, userId, null, content, Map.of(), 1.0, Instant.now());
    }

    /**
     * Creates a fully specified entry with an explicit key and metadata.
     *
     * @param type      the memory type; never {@code null}
     * @param sessionId the session identifier; may be {@code null}
     * @param userId    the user identifier; may be {@code null}
     * @param key       the discriminator key for exact lookups; may be {@code null}
     * @param content   the memory content; never {@code null}
     * @param metadata  additional key-value metadata; {@code null} is treated as empty
     * @return a new entry with score {@code 1.0} and the current timestamp
     */
    public static MemoryEntry withKey(final MemoryType type, final String sessionId, final String userId,
                                       final String key, final String content, final Map<String, Object> metadata) {
        return new MemoryEntry(type, sessionId, userId, key, content, metadata, 1.0, Instant.now());
    }

    /**
     * Returns a copy of this entry with the given relevance score.
     * Used during recall to attach retrieval scores to returned entries.
     *
     * @param newScore the relevance score in [0.0, 1.0]
     * @return a new entry identical to this one except for the score
     */
    public MemoryEntry withScore(final double newScore) {
        return new MemoryEntry(type, sessionId, userId, key, content, metadata, newScore, timestamp);
    }
}
