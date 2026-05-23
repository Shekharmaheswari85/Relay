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
package io.agentcore.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Concrete, ready-to-use JPA entity that persists idempotent tool execution results for
 * agents that do not require a domain-specific cache table.
 *
 * <p>When an agent calls an idempotent tool (e.g. a read-only database query or an
 * external API lookup), the framework stores the result here. On an LLM retry or a
 * session resume the framework finds the cached row and returns the stored result without
 * re-executing the tool, saving latency and external API cost.
 *
 * <p>All persistence columns ({@code session_id}, {@code cache_key}, {@code result_json},
 * {@code created_at}, {@code updated_at}) are inherited from {@link BaseToolResultCache}.
 * Agent modules that need extra columns (e.g. a TTL field) should subclass
 * {@link BaseToolResultCache} directly with their own {@code @Entity} and {@code @Table}.
 *
 * <p>Two database indexes are defined on the table:
 * <ul>
 *   <li>{@code idx_atrc_session_key} — unique composite index on
 *       ({@code session_id}, {@code cache_key}) used for cache lookups</li>
 *   <li>{@code idx_atrc_session_id} — non-unique index on {@code session_id} used for
 *       bulk deletion during session cleanup</li>
 * </ul>
 */
@Entity
@Table(
        name = "agent_tool_result_cache",
        indexes = {
            @Index(name = "idx_atrc_session_key", columnList = "session_id, cache_key", unique = true),
            @Index(name = "idx_atrc_session_id", columnList = "session_id")
        })
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AgentToolResultCacheDO extends BaseToolResultCache {
    // All columns are inherited from BaseToolResultCache:
    //   id, session_id, cache_key, result_json, created_at, updated_at
}
