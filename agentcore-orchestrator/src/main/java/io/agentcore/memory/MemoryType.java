/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.memory;

/**
 * Classifies the cognitive function of a {@link MemoryEntry}.
 *
 * <p>Each type maps to a distinct storage and retrieval pattern:
 * <ul>
 *   <li>{@link #ENTITY} — stored and retrieved by entity ID and type; supports attribute filtering</li>
 *   <li>{@link #PERSONA} — stored and retrieved by user ID; persists across all sessions</li>
 *   <li>{@link #WORKFLOW} — semantic retrieval for finding similar past task patterns</li>
 *   <li>{@link #KNOWLEDGE} — RAG-style semantic retrieval for domain facts and documents</li>
 *   <li>{@link #SESSION} — exact lookup by session ID; short-lived working state</li>
 * </ul>
 */
public enum MemoryType {

    /**
     * Facts about named entities: people, products, stores, systems, or concepts.
     * Stored as structured attribute-value pairs keyed by entity ID.
     * Example: {@code PRODUCT(sku-123).stockStatus = "OUT_OF_STOCK"}
     */
    ENTITY,

    /**
     * Per-user preferences, communication style, stated goals, and profile facts.
     * Persists across sessions and is injected into every context window for that user.
     * Example: {@code prefers concise responses; manages Electronics department}
     */
    PERSONA,

    /**
     * Learned action patterns, tool call sequences, and multi-step execution plans
     * extracted from past agent runs. Retrieved by semantic similarity to the current task.
     * Example: {@code when asked about reorder quantities, calls InventoryTool then PricingTool}
     */
    WORKFLOW,

    /**
     * General-purpose semantic memory: domain facts, search results, and agent-generated
     * knowledge that does not belong to a specific entity. Retrieved by vector similarity.
     * Example: {@code Q3 electronics returns rate is 4.2% above regional average}
     */
    KNOWLEDGE,

    /**
     * Short-lived per-session working state — decisions made earlier in the current
     * conversation that should influence subsequent turns. Cleared at session end.
     * Example: {@code user confirmed they want bulk pricing applied}
     */
    SESSION
}
