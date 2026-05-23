/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes an agent's identity, capabilities, and structure for registry and
 * discovery purposes.
 *
 * <p>Instances are typically constructed inside an {@code io.relay.executor.AgentExecutor}
 * implementation's {@code metadata()} method and returned by the registry API so that
 * orchestrators, UIs, and other agents can understand what each agent does and how to
 * route requests to it.
 *
 * <p>For MCP tool exposure and REST API serialization, prefer the flat
 * {@link AgentMetadataDTO} variant, which uses the DTO-layer nested types
 * ({@link AgentIntentDTO}, {@link SubAgentMetadataDTO}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMetadata {

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
     * Carries a one-paragraph description of what business problems this agent
     * solves, written for a developer or product audience.  Required.
     */
    private String description;

    /**
     * Carries the list of high-level business responsibilities this agent owns
     * (e.g. {@code ["Market configuration", "Banner registration"]}).  Used by
     * orchestrators to determine which agent should handle a given task.
     * May be empty but never {@code null}.
     */
    private List<String> responsibilities;

    /**
     * Carries the list of named capabilities or MCP tool categories this agent
     * exposes (e.g. {@code ["registerMarket", "configureAssortment"]}).
     * May be empty but never {@code null}.
     */
    private List<String> capabilities;

    /**
     * Carries the list of intents this agent recognizes, each paired with routing
     * instructions and example utterances.  Used by the {@code LlmIntentRouter} to
     * match incoming messages.  May be empty for agents that use free-form routing.
     */
    private List<AgentIntent> intents;

    /**
     * Carries the ordered list of sub-agents that compose this orchestrated agent.
     * The order reflects the default routing sequence.  {@code null} or empty for
     * single-agent (non-orchestrated) deployments.
     */
    private List<SubAgentMetadata> subAgents;

    /**
     * Carries the operational status of this agent (e.g. {@code "active"},
     * {@code "deprecated"}, {@code "beta"}).  Informational — does not affect
     * routing or execution.  Optional.
     */
    private String status;

    /**
     * Carries the semantic version string of this agent's implementation
     * (e.g. {@code "1.2.0"}).  Optional.
     */
    private String version;

    /**
     * Represents a single user intent that this agent can detect and fulfil,
     * together with representative example utterances for LLM few-shot routing.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentIntent {

        /**
         * Carries the canonical intent name used for routing (e.g.
         * {@code "create-market"}).  Must be unique within the owning agent.
         */
        private String intent;

        /**
         * Carries a plain-language description of what the agent does when this
         * intent is matched.
         */
        private String description;

        /**
         * Carries example user utterances that map to this intent, used for
         * few-shot examples in LLM-based intent classifiers.
         * May be empty but never {@code null}.
         */
        private List<String> examples;
    }

    /**
     * Describes a sub-agent within an orchestrated pipeline, recording which
     * workflow steps and tools it owns.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubAgentMetadata {

        /**
         * Carries the unique identifier of the sub-agent bean
         * (e.g. {@code "config-agent"}).
         */
        private String agentId;

        /**
         * Carries the display name of the sub-agent used in logs and the UI.
         */
        private String name;

        /**
         * Carries a one-line description of what this sub-agent handles.
         */
        private String description;

        /**
         * Carries the workflow step names this sub-agent is responsible for
         * (e.g. {@code ["CONFIGURE_ASSORTMENT", "REGISTER_MARKET"]}).
         * May be empty but never {@code null}.
         */
        private List<String> handledSteps;

        /**
         * Carries the canonical names of MCP tools this sub-agent primarily
         * invokes.  May be empty but never {@code null}.
         */
        private List<String> tools;
    }
}
