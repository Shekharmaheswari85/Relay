/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.exception;

/**
 * Thrown by {@link io.agentcore.guard.ToolSessionGuard#assertSessionContext(String)} when a
 * protected tool's {@code apply()} method is invoked without an active session bound
 * to the current thread.
 *
 * <p>Protected tools — those whose owners call
 * {@code ToolSessionGuard.assertSessionContext(toolName)} as their first line — require a
 * valid session context so that the framework can enforce authorisation, apply the advisor
 * chain (confirmation gate, step gate), and write an audit log entry.  A missing session
 * context indicates that the tool was called directly (e.g. via the raw MCP SSE endpoint
 * or a bare bean method invocation in a test) rather than through the normal
 * {@code ChatClient} pipeline.
 *
 * <p><strong>Callers should not catch this exception.</strong>  Instead:
 * <ol>
 *   <li>Create a session via {@code POST /sessions}.</li>
 *   <li>Send subsequent interactions through
 *       {@code POST /sessions/{sessionId}/messages}.</li>
 *   <li>If writing tests that exercise tool logic directly, bind a session ID with
 *       {@link io.agentcore.session.SessionContextHolder#set(String)} before calling the
 *       tool and clear it in a {@code finally} block.</li>
 * </ol>
 *
 * @see io.agentcore.guard.ToolSessionGuard
 * @see McpDirectMutationBlockedException
 */
public class DirectToolCallBlockedException extends RuntimeException {

    private final String toolName;

    /**
     * Creates an exception with the standard "no active session context" message.
     *
     * @param toolName the canonical {@code @Tool(name = ...)} value of the blocked tool
     */
    public DirectToolCallBlockedException(final String toolName) {
        super("Direct call to protected tool '" + toolName + "' blocked — no active session context. "
                + "Use the agent chat interface or ensure session context is propagated.");
        this.toolName = toolName;
    }

    /**
     * Creates an exception with a caller-supplied reason appended to the standard
     * message prefix.  Use this constructor when the blocking decision was made for
     * a reason other than a missing session (e.g. an expired or unauthorised session).
     *
     * @param toolName the canonical {@code @Tool(name = ...)} value of the blocked tool
     * @param reason   a short human-readable explanation of why the call was blocked
     */
    public DirectToolCallBlockedException(final String toolName, final String reason) {
        super("Direct call to protected tool '" + toolName + "' blocked: " + reason);
        this.toolName = toolName;
    }

    /**
     * Returns the canonical name of the tool whose invocation was blocked.
     *
     * @return the tool name passed to the constructor; never {@code null}
     */
    public String getToolName() {
        return toolName;
    }
}
