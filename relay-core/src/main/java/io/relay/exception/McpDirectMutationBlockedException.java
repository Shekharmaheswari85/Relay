/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.exception;

import lombok.Getter;

/**
 * Thrown by {@code io.relay.guard.MutationToolGuard#assertSessionContext(String)} when a
 * mutation tool is invoked directly through the MCP SSE endpoint — or by any other path that
 * bypasses the {@code ChatClient} pipeline — without an active session bound to the current
 * thread.
 *
 * <p>Mutation tools (annotated with
 * {@code @AgentTool(category = ToolCategory.MUTATION, requiresSession = true)}) must run inside
 * the {@code ChatClient} advisor chain so that the confirmation gate, step gate, and audit
 * advisor can apply their safety checks before the tool modifies any state.  A raw MCP SSE
 * call skips all of those advisors, making direct mutation tool access a security and
 * auditability risk.
 *
 * <p>The {@code io.relay.aspect.BaseMcpCallInterceptor} enforces this rule at the
 * {@code ToolCallback} layer; {@code MutationToolGuard} provides an additional line of defense
 * inside each tool method itself so that the rule holds even if the interceptor is bypassed
 * (e.g. via direct bean invocation in tests or a future Spring AI API change).
 *
 * <p><strong>Callers should not catch this exception.</strong>  Instead:
 * <ol>
 *   <li>Create a session via {@code POST /sessions}.</li>
 *   <li>Send all mutation-triggering messages through
 *       {@code POST /sessions/{sessionId}/messages}.</li>
 *   <li>Read-only discovery and monitoring tools (
 *       {@code ToolCategory.DISCOVERY} and {@code ToolCategory.MONITORING}) may be
 *       called directly via MCP without a session.</li>
 * </ol>
 *
 * @see DirectToolCallBlockedException
 */
@Getter
public class McpDirectMutationBlockedException extends IllegalStateException {

    /**
     * -- GETTER --
     *  Returns the canonical name of the mutation tool whose invocation was blocked.
     *
     */
    private final String toolName;

    /**
     * Creates an exception with the standard blocking message for the named tool.
     *
     * @param toolName the canonical {@code @Tool(name = ...)} value of the blocked mutation tool
     */
    public McpDirectMutationBlockedException(final String toolName) {
        super(buildMessage(toolName));
        this.toolName = toolName;
    }

    private static String buildMessage(final String toolName) {
        return "Mutation tool '" + toolName + "' cannot be invoked directly via MCP without an active session. "
                + "Mutation tools require the advisor chain (confirmation gate, step gate) which only runs inside "
                + "the ChatClient pipeline. Create a session first via the agent's session API, then send messages "
                + "through the session endpoint. Direct MCP access is permitted for read-only discovery and monitoring tools.";
    }
}
