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

/**
 * Central SPI for all agent memory operations — the Memory Manager described in the
 * <em>Agent Memory Architecture</em> framework.
 *
 * <p>{@code AgentMemoryManager} is the software abstraction that unifies read and write
 * operations across all {@link MemoryType memory types} behind a single consistent API.
 * It decides what to write, when to retrieve, and how to format memory for injection into
 * the context window — hiding the underlying storage (in-memory, relational, or vector)
 * from the rest of the framework.
 *
 * <h3>Architecture position</h3>
 * <p>Memory operations fire at two points in the agent harness:
 * <ol>
 *   <li><strong>Pre-LLM</strong> — {@link #assembleMemoryContext} retrieves and formats
 *       all relevant memories. The assembled block is injected into the system prompt by
 *       {@link io.agentcore.advisor.MemoryAdvisor}.</li>
 *   <li><strong>Post-LLM</strong> — {@link #remember} stores new facts extracted from
 *       the assistant response back into long-term memory.</li>
 * </ol>
 *
 * <h3>Default implementation</h3>
 * <p>An {@link InMemoryAgentMemoryManager} is auto-configured as a {@code @Bean} when no
 * custom implementation is present on the classpath. It is suitable for development and
 * single-instance deployments. For production, provide a vector-store-backed implementation.
 *
 * <h3>Registration example</h3>
 * <pre>{@code
 * @Bean
 * public AgentMemoryManager myMemoryManager(VectorStore vectorStore, ObjectMapper mapper) {
 *     return new VectorStoreMemoryManager(vectorStore, mapper);
 * }
 * }</pre>
 *
 * @see MemoryEntry
 * @see MemoryType
 * @see io.agentcore.advisor.MemoryAdvisor
 */
public interface AgentMemoryManager {

    /**
     * Writes a memory entry to the appropriate backing store.
     *
     * <p>Implementations must be non-blocking and must not throw checked exceptions.
     * Any storage failure should be caught internally and logged at {@code WARN} level.
     *
     * @param entry the memory entry to persist; never {@code null}
     */
    void remember(MemoryEntry entry);

    /**
     * Retrieves memory entries of a specific type matching the given query, in descending
     * relevance order.
     *
     * <p>The {@code query} parameter is used for semantic similarity search in
     * vector-backed implementations and for substring/keyword matching in simple
     * implementations. If neither {@code sessionId} nor {@code userId} is provided, the
     * behaviour is implementation-defined.
     *
     * @param sessionId the session to scope recall to; may be {@code null}
     * @param userId    the user to scope recall to; may be {@code null}
     * @param type      the memory type to retrieve; never {@code null}
     * @param query     the semantic query used for relevance ranking; may be {@code null}
     *                  to return all entries for the session/user scope
     * @param topK      the maximum number of entries to return; must be positive
     * @return a non-null, possibly empty list of entries ordered by descending relevance
     */
    List<MemoryEntry> recall(String sessionId, String userId, MemoryType type, String query, int topK);

    /**
     * Recalls entries across all memory types relevant for context assembly.
     *
     * <p>Retrieves {@link MemoryType#ENTITY}, {@link MemoryType#PERSONA}, and
     * {@link MemoryType#WORKFLOW} memories for the given session and user, then merges
     * the results. The default implementation calls {@link #recall} three times.
     *
     * @param sessionId the session context; may be {@code null}
     * @param userId    the user context; may be {@code null}
     * @param query     the user's current message, used for relevance ranking
     * @return a merged list of entries from all context-relevant memory types
     */
    default List<MemoryEntry> recallForContext(final String sessionId, final String userId, final String query) {
        var results = new ArrayList<MemoryEntry>();
        results.addAll(recall(sessionId, userId, MemoryType.ENTITY, query, 5));
        results.addAll(recall(sessionId, userId, MemoryType.PERSONA, query, 3));
        results.addAll(recall(sessionId, userId, MemoryType.WORKFLOW, query, 3));
        return results;
    }

    /**
     * Assembles all relevant memories for a session and user into a formatted text block
     * ready for injection into the LLM context window.
     *
     * <p>Returns an empty string when no relevant memories exist, so callers can safely
     * skip injection without checking for {@code null}.
     *
     * @param sessionId the session context; may be {@code null}
     * @param userId    the user context; may be {@code null}
     * @param query     the user's current message, used for relevance ranking
     * @return a formatted memory context string, or an empty string if nothing to inject
     */
    String assembleMemoryContext(String sessionId, String userId, String query);

    /**
     * Forgets all memory entries of the given type associated with the session.
     *
     * @param sessionId the session whose memories to clear; never {@code null}
     * @param type      the memory type to clear; never {@code null}
     */
    void forget(String sessionId, MemoryType type);

    /**
     * Clears all memory entries for the given session across all types.
     *
     * @param sessionId the session to wipe; never {@code null}
     */
    void forgetSession(String sessionId);

    /**
     * Forgets all entries of the given type associated with the user, across all sessions.
     *
     * @param userId the user whose memories to clear; never {@code null}
     * @param type   the memory type to clear; never {@code null}
     */
    void forgetUser(String userId, MemoryType type);
}
