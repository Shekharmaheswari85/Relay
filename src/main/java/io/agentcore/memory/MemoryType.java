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
