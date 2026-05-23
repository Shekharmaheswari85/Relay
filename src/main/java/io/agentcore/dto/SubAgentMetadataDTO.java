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
package io.agentcore.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes a single sub-agent within an orchestrated agent pipeline for use in
 * REST API responses and MCP tool exposure.
 *
 * <p>Instances appear in the {@link AgentMetadataDTO#subAgents} list returned
 * by agent metadata endpoints.  UIs consume this type to render per-agent capability
 * panels, attribution labels, and routing-context summaries.
 *
 * <p>The {@code name} field is the primary key for sub-agent lookup in logs, SSE
 * events, and audit rows; always populate it and keep it stable across releases.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentMetadataDTO {

    /**
     * Carries the stable internal identifier used in log messages, SSE event
     * {@code agent} fields, and audit trail rows (e.g. {@code "config-agent"}).
     * Must be unique within the owning orchestrated agent.  Required.
     */
    private String name;

    /**
     * Carries the human-readable label shown in capability panels and
     * attribution UI components (e.g. {@code "Configuration Agent"}).
     * Optional — falls back to {@link #name} when absent.
     */
    private String displayName;

    /**
     * Carries a one-line description of what this sub-agent is responsible for,
     * written for a developer or product audience
     * (e.g. {@code "Registers markets and configures assortment rules"}).
     * Optional.
     */
    private String responsibility;

    /**
     * Carries the list of high-level user goals and intents this sub-agent can
     * fulfil, expressed as plain-language phrases
     * (e.g. {@code ["create a market", "update banner settings"]}).
     * Used by orchestrators and UIs to surface routing context.
     * May be empty but never {@code null}.
     */
    private List<String> intents;

    /**
     * Carries the canonical MCP tool names ({@code @Tool(name = ...)}) that this
     * sub-agent primarily invokes.  Allows UIs to display tool attribution and
     * helps orchestrators reason about tool ownership.
     * May be empty but never {@code null}.
     */
    private List<String> ownedTools;

    /**
     * Carries the workflow step names this sub-agent is responsible for
     * (e.g. {@code ["CONFIGURE_ASSORTMENT", "REGISTER_MARKET"]}).  Used by the
     * orchestrator to determine which sub-agent should receive control at each
     * step in the pipeline.  May be empty but never {@code null}.
     */
    private List<String> handledSteps;
}
