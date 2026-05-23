/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.rag;

import java.util.List;
import java.util.Map;

/**
 * SPI for fetching documents relevant to a user query in a RAG workflow.
 *
 * <p>Implement this interface to connect the agent framework to any backing store —
 * a vector database, a full-text search index, a structured database, or an in-memory
 * corpus. {@link RagAdvisor} calls this SPI before each LLM invocation, filters the
 * results by a configurable minimum score, and injects the surviving documents into the
 * system prompt.
 *
 * <h3>Implementing a retriever</h3>
 * <pre>{@code
 * @Service
 * public class PineconeRetriever implements AgentRetriever {
 *
 *     @Override
 *     public List<RetrievedDocument> retrieve(String query, Map<String, Object> context) {
 *         String tenantId = (String) context.getOrDefault("tenantId", "default");
 *         List<ScoredVector> hits = pineconeIndex.query(embed(query), 10, tenantId);
 *         return hits.stream()
 *                 .map(v -> RetrievedDocument.of(v.getId(), v.getMetadata().get("text"), v.getScore()))
 *                 .toList();
 *     }
 * }
 * }</pre>
 *
 * <h3>Wiring into the advisor chain</h3>
 * <pre>{@code
 * @Bean
 * public RagAdvisor ragAdvisor(AgentRetriever retriever) {
 *     return RagAdvisor.builder(retriever)
 *             .maxDocuments(5)
 *             .minScore(0.75)
 *             .build();
 * }
 * }</pre>
 *
 * @see RagAdvisor
 * @see RetrievedDocument
 */
@FunctionalInterface
public interface AgentRetriever {

    /**
     * Fetches documents relevant to the given query, using the session context for
     * optional filtering or tenant scoping.
     *
     * <p>Implementations must return a list ordered by relevance descending.
     * The list may be empty but must never be {@code null}. Exceptions thrown by
     * the backing store propagate to {@link RagAdvisor}, which logs and suppresses
     * them so the LLM call still proceeds without context.
     *
     * @param query   the user's message or a search query derived from it; never {@code null}
     * @param context the current session context map, which may contain {@code tenantId},
     *                category filters, or other implementation-specific entries; never
     *                {@code null}, but may be empty
     * @return a non-null, possibly empty list of documents ordered by descending relevance
     */
    List<RetrievedDocument> retrieve(String query, Map<String, Object> context);

    /**
     * Fetches documents relevant to the given query without session context.
     *
     * <p>Convenience overload that delegates to {@link #retrieve(String, Map)} with an
     * empty context map. Suitable when retrieval does not depend on session state.
     *
     * @param query the user's message or search query; never {@code null}
     * @return a non-null, possibly empty list of documents ordered by descending relevance
     */
    default List<RetrievedDocument> retrieve(final String query) {
        return retrieve(query, Map.of());
    }
}
