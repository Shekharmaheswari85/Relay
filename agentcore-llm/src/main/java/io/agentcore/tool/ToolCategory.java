/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.tool;

/**
 * Classifies an {@link AgentTool} by the type of impact its operations have on the system.
 *
 * <p>The category affects two framework behaviors:
 * <ol>
 *   <li><b>Model-tier routing</b> — {@link #MUTATION} tools are routed to the
 *       {@link io.agentcore.llm.ModelTier#REASONING} model tier because state-changing
 *       operations warrant more capable, careful reasoning. {@link #DISCOVERY} and
 *       {@link #MONITORING} tools are routed to the cost-effective
 *       {@link io.agentcore.llm.ModelTier#UTILITY} tier.</li>
 *   <li><b>MCP session guard</b> — {@link #MUTATION} tools are automatically wrapped by
 *       {@link io.agentcore.aspect.BaseMcpCallInterceptor}, which rejects direct MCP
 *       invocations that arrive without an active session context. This ensures mutation
 *       operations only execute through the agent's advisor pipeline.</li>
 * </ol>
 *
 * @see AgentTool#category()
 */
public enum ToolCategory {

    /**
     * Read-only tools that fetch or explore data without modifying any state.
     *
     * <p>Typical examples: searching a catalog, looking up an order, listing available options.
     * These tools are safe to call without user confirmation and are routed to the
     * {@link io.agentcore.llm.ModelTier#UTILITY} model tier.
     */
    DISCOVERY,

    /**
     * Tools that create, update, or delete data, or that trigger side-effecting operations.
     *
     * <p>Typical examples: placing an order, canceling a shipment, updating account settings.
     * These tools are automatically protected by the MCP session guard and are routed to the
     * {@link io.agentcore.llm.ModelTier#REASONING} model tier to reduce the risk of
     * unintended actions.
     */
    MUTATION,

    /**
     * Read-only tools focused on observability — checking status, progress, logs, or
     * audit trails rather than exploring domain data.
     *
     * <p>Typical examples: polling a job's completion status, retrieving an audit trail,
     * checking system health. Like {@link #DISCOVERY}, these tools carry no side effects
     * and are routed to the {@link io.agentcore.llm.ModelTier#UTILITY} model tier.
     */
    MONITORING
}
