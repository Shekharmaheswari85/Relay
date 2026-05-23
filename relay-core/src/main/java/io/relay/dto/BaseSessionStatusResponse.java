/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base response body returned by {@code GET /sessions/{sessionId}} and each entry
 * in the {@code GET /sessions} list — describes the current state and execution
 * progress of a single agent session.
 *
 * <p>Use {@link #currentStep} and {@link #progressPercent} as the authoritative
 * source for progress-bar and step-indicator UI components.  Use
 * {@link #reasoningTimeline} to render a chronological trace of LLM reasoning
 * and tool invocations.  Use {@link #agentWorkflow} for per-sub-agent attribution
 * panels in orchestrated agents.
 *
 * <p>Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, {@code @AllArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to append domain-specific output
 * fields (e.g. structured results produced by the agent).
 *
 * <h3>Extending this class</h3>
 * <pre>{@code
 * @Data
 * @SuperBuilder
 * @NoArgsConstructor
 * @AllArgsConstructor
 * @EqualsAndHashCode(callSuper = true)
 * public class MySessionStatusResponse extends BaseSessionStatusResponse {
 *     private String market;
 *     private String banner;
 *     private Map<String, Object> whereClauseProposals;
 * }
 * }</pre>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseSessionStatusResponse {

    /**
     * Carries the globally unique identifier of the session.  Always present.
     */
    private String sessionId;

    /**
     * Carries the identifier of the agent currently handling this session.
     * Always present.
     */
    private String agentId;

    /**
     * Carries the current lifecycle status of the session.  Valid values are
     * defined by {@link io.relay.session.SessionStatus}: {@code "ACTIVE"},
     * {@code "PAUSED"}, {@code "COMPLETED"}, {@code "FAILED"}, or
     * {@code "EXPIRED"}.  Always present.
     */
    private String status;

    /**
     * Carries the name of the workflow step the agent is currently executing or
     * waiting on (e.g. {@code "INTENT_ROUTING"}, {@code "CONFIRMATION_GATE"},
     * {@code "EXECUTING_TOOL"}).  Use this field as the primary label for
     * step-indicator UI components.  May be {@code null} between steps.
     */
    private String currentStep;

    /**
     * Carries a 0–100 integer representing how far the agent has progressed
     * through its expected workflow.  The agent implementation controls the exact
     * mapping from step to percentage.  May be {@code null} for agents that do
     * not report granular progress.
     */
    private Integer progressPercent;

    /**
     * Carries the name or key of the most recently saved checkpoint, which
     * identifies the point from which a paused session can be resumed.  May be
     * {@code null} when no checkpoint has been saved yet.
     */
    private String lastCheckpoint;

    /**
     * Carries a free-form map of session-scoped key-value pairs that accumulate
     * as the agent executes (e.g. collected intent parameters, intermediate
     * results).  May be {@code null} or empty when no context has been written.
     */
    private Map<String, Object> context;

    /**
     * Carries the ordered list of reasoning steps and tool invocations that have
     * occurred during this session, suitable for rendering a chronological
     * trace in the UI.  Each entry is a free-form map whose structure depends on
     * the agent implementation.  May be {@code null} or empty.
     */
    private List<Map<String, Object>> reasoningTimeline;

    /**
     * Carries one block per sub-agent that has participated in this session,
     * populated by orchestrated agents to provide per-agent attribution.  May be
     * {@code null} or empty for single-agent sessions.
     */
    private List<AgentWorkflowBlock> agentWorkflow;

    /**
     * Carries the ISO-8601 UTC timestamp at which the session was first created
     * (e.g. {@code "2025-05-23T14:30:00Z"}).  Always present.
     */
    private String createdAt;

    /**
     * Carries the ISO-8601 UTC timestamp of the most recent update to the session
     * record (e.g. status change, context write, or checkpoint save).
     * Always present after the first update.
     */
    private String updatedAt;

    /**
     * Summarizes a single sub-agent's participation in an orchestrated workflow.
     * One instance is produced per sub-agent that was activated during the session.
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentWorkflowBlock {

        /**
         * Carries the unique identifier of the sub-agent (e.g.
         * {@code "config-agent"}).
         */
        private String agentId;

        /**
         * Carries the human-readable display name of the sub-agent.
         */
        private String agentName;

        /**
         * Carries the execution status of this sub-agent within the workflow
         * (e.g. {@code "RUNNING"}, {@code "COMPLETED"}, {@code "FAILED"}).
         */
        private String status;

        /**
         * Indicates whether this sub-agent is the one currently executing.
         * {@code true} while the sub-agent holds control; {@code false} once it
         * has handed off or completed.
         */
        private Boolean active;

        /**
         * Carries the ISO-8601 UTC timestamp at which this sub-agent began
         * execution.  May be {@code null} if it has not started yet.
         */
        private String startedAt;

        /**
         * Carries the ISO-8601 UTC timestamp at which this sub-agent finished
         * execution.  May be {@code null} if it is still running.
         */
        private String completedAt;

        /**
         * Carries the total number of tool calls this sub-agent has made during
         * its turn.  May be {@code null} before any tool is invoked.
         */
        private Integer toolExecutionCount;

        /**
         * Carries the canonical names of every tool this sub-agent has invoked,
         * in call order, for attribution and audit display.
         * Never {@code null} — an empty list is used when no tools were called.
         */
        private List<String> toolsExecuted;
    }
}
