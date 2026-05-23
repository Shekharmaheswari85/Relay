/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory implementation of {@link AgentMemoryManager} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Entries are indexed under two key schemes:
 * <ul>
 *   <li>{@code SESSION:<TYPE>:<sessionId>} — for session-scoped entries</li>
 *   <li>{@code USER:<TYPE>:<userId>} — for user-scoped entries</li>
 * </ul>
 *
 * <p>Recall uses simple case-insensitive keyword matching: a query is tokenised on whitespace
 * and an entry matches if any token longer than three characters appears in the entry's content.
 * When {@code query} is blank or {@code null}, all entries in scope are returned.
 *
 * <p>This implementation is thread-safe for concurrent reads and writes within a single JVM.
 * It is suitable for development and single-instance deployments. For production, replace it
 * with a vector-store-backed implementation registered as a {@code @Bean} of type
 * {@link AgentMemoryManager}.
 *
 * @see AgentMemoryManager
 * @see MemoryEntry
 */
@Slf4j
public class InMemoryAgentMemoryManager implements AgentMemoryManager {

    // Two indexes: by session and by user
    // key: "SESSION:TYPE:sessionId" -> List<MemoryEntry>
    // key: "USER:TYPE:userId" -> List<MemoryEntry>
    private final Map<String, List<MemoryEntry>> store = new ConcurrentHashMap<>();

    /**
     * Writes a memory entry into the store under the session key, user key, or both,
     * depending on which identifiers are non-null on the entry.
     *
     * @param entry the memory entry to persist; never {@code null}
     */
    @Override
    public void remember(final MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.sessionId() != null) {
            storeUnder(sessionKey(entry.type(), entry.sessionId()), entry);
        }
        if (entry.userId() != null) {
            storeUnder(userKey(entry.type(), entry.userId()), entry);
        }
        log.debug("Memory stored: type={} sessionId={} userId={}", entry.type(), entry.sessionId(), entry.userId());
    }

    /**
     * Retrieves memory entries of the given type that match the query, limited to {@code topK}
     * results. Entries from both the session index and the user index are merged with
     * deduplication (an entry already found under the session key is not added again from
     * the user key).
     *
     * @param sessionId the session to scope recall to; may be {@code null}
     * @param userId    the user to scope recall to; may be {@code null}
     * @param type      the memory type to retrieve; never {@code null}
     * @param query     the keyword query for matching; may be {@code null} to return all entries
     * @param topK      the maximum number of entries to return; must be positive
     * @return a non-null, possibly empty list of matching entries
     */
    @Override
    public List<MemoryEntry> recall(final String sessionId, final String userId, final MemoryType type,
                                     final String query, final int topK) {
        List<MemoryEntry> candidates = new ArrayList<>();
        if (sessionId != null) {
            candidates.addAll(store.getOrDefault(sessionKey(type, sessionId), List.of()));
        }
        if (userId != null) {
            store.getOrDefault(userKey(type, userId), List.of()).stream()
                    .filter(e -> !candidates.contains(e))
                    .forEach(candidates::add);
        }
        return candidates.stream()
                .filter(e -> matches(e, query))
                .limit(Math.max(1, topK))
                .toList();
    }

    /**
     * Assembles a formatted {@code [AGENT MEMORY]} block from all context-relevant memories
     * for the given session and user. Returns an empty string when no memories are found.
     *
     * <p>The format is:
     * <pre>
     * [AGENT MEMORY]
     * ENTITY: Product sku-123 is out of stock in SE region
     * PERSONA: User prefers concise bullet-point responses
     * WORKFLOW: For pricing queries, checked inventory first
     * [END AGENT MEMORY]
     * </pre>
     *
     * @param sessionId the session context; may be {@code null}
     * @param userId    the user context; may be {@code null}
     * @param query     the user's current message, used for relevance ranking
     * @return the formatted memory block, or an empty string if no memories exist
     */
    @Override
    public String assembleMemoryContext(final String sessionId, final String userId, final String query) {
        List<MemoryEntry> entries = recallForContext(sessionId, userId, query);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n[AGENT MEMORY]\n");
        for (MemoryEntry e : entries) {
            sb.append(e.type().name()).append(": ").append(e.content()).append("\n");
        }
        sb.append("[END AGENT MEMORY]\n");
        return sb.toString();
    }

    /**
     * Removes all memory entries of the given type from the session index.
     *
     * @param sessionId the session whose memories to clear; never {@code null}
     * @param type      the memory type to clear; never {@code null}
     */
    @Override
    public void forget(final String sessionId, final MemoryType type) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        store.remove(sessionKey(type, sessionId));
        log.debug("Memory forgotten: type={} sessionId={}", type, sessionId);
    }

    /**
     * Removes all memory entries for the given session across every {@link MemoryType}.
     *
     * @param sessionId the session to wipe; never {@code null}
     */
    @Override
    public void forgetSession(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        for (MemoryType type : MemoryType.values()) {
            store.remove(sessionKey(type, sessionId));
        }
        log.debug("All memory forgotten for session: {}", sessionId);
    }

    /**
     * Removes all user-scoped memory entries of the given type for the specified user.
     *
     * @param userId the user whose memories to clear; never {@code null}
     * @param type   the memory type to clear; never {@code null}
     */
    @Override
    public void forgetUser(final String userId, final MemoryType type) {
        Objects.requireNonNull(userId, "userId must not be null");
        store.remove(userKey(type, userId));
        log.debug("User memory forgotten: type={} userId={}", type, userId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void storeUnder(final String storeKey, final MemoryEntry entry) {
        store.computeIfAbsent(storeKey, k -> new ArrayList<>()).add(entry);
    }

    private boolean matches(final MemoryEntry entry, final String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String lowerQuery = query.toLowerCase();
        String lowerContent = entry.content() != null ? entry.content().toLowerCase() : "";
        // Simple token overlap: match if any word from query appears in content
        for (String token : lowerQuery.split("\\s+")) {
            if (token.length() > 3 && lowerContent.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String sessionKey(final MemoryType type, final String sessionId) {
        return "SESSION:" + type.name() + ":" + sessionId;
    }

    private String userKey(final MemoryType type, final String userId) {
        return "USER:" + type.name() + ":" + userId;
    }
}
