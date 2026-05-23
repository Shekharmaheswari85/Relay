/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flat DTO projection of {@link AgentMetadata} used for REST API serialisation and
 * MCP tool exposure via {@code io.agentcore.executor.AgentExecutor#metadata()}.
 *
 * <p>Unlike {@link AgentMetadata}, which uses inner static classes for its nested
 * types, this DTO references the standalone {@link AgentIntentDTO} and
 * {@link SubAgentMetadataDTO} types so that they can be independently documented,
 * validated, and referenced from multiple places.
 *
 * <p>Construct instances using the provided Lombok builder:
 * <pre>{@code
 * AgentMetadataDTO metadata = AgentMetadataDTO.builder()
 *     .agentId("onboarding-agent")
 *     .name("Onboarding Agent")
 *     .description("Handles new-market onboarding workflows end to end.")
 *     .version("1.0.0")
 *     .status("active")
 *     .build();
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMetadataDTO {

    /**
     * Carries the stable machine-readable identifier for this agent
     * (e.g. {@code "onboarding-agent"}).  Must be unique across all agents
     * registered in the same application context.  Required.
     */
    private String agentId;

    /**
     * Carries the short human-readable display name used in UIs and log messages
     * (e.g. {@code "Onboarding Agent"}).  Required.
     */
    private String name;

    /**
     * Carries a one-paragraph description of the business problems this agent
     * solves, written for a developer or product audience.  Required.
     */
    private String description;

    /**
     * Carries the list of high-level business responsibilities this agent owns.
     * Used by orchestrators and UIs to display capability summaries.
     * May be empty but never {@code null}.
     */
    private List<String> responsibilities;

    /**
     * Carries the list of named capabilities or MCP tool categories this agent
     * exposes.  May be empty but never {@code null}.
     */
    private List<String> capabilities;

    /**
     * Carries the list of intents this agent recognises, each described by an
     * {@link AgentIntentDTO} with routing instructions and example utterances.
     * May be empty for agents that use free-form routing.
     */
    private List<AgentIntentDTO> intents;

    /**
     * Carries the ordered list of sub-agents that compose this orchestrated agent.
     * The order reflects the default routing sequence.  {@code null} or empty for
     * single-agent (non-orchestrated) deployments.
     */
    private List<SubAgentMetadataDTO> subAgents;

    /**
     * Carries the operational status of this agent (e.g. {@code "active"},
     * {@code "deprecated"}, {@code "beta"}).  Informational only.  Optional.
     */
    private String status;

    /**
     * Carries the semantic version string of this agent's implementation
     * (e.g. {@code "1.2.0"}).  Optional.
     */
    private String version;
}
