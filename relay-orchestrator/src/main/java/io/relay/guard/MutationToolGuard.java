/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.guard;

import io.relay.exception.McpDirectMutationBlockedException;
import io.relay.session.SessionContextHolder;

/**
 * Static guard that prevents mutation tools from executing without an active session.
 *
 * <p>Mutation tools — those annotated with
 * {@code @AgentTool(category = ToolCategory.MUTATION)} — change persistent state
 * (databases, external systems, configurations) and therefore require the full advisor
 * chain (confirmation gating, step gating, audit logging) to have run before the tool
 * body executes. That advisor chain only fires inside the Spring AI {@code ChatClient}
 * pipeline; a direct MCP call bypasses it entirely.
 *
 * <p>This guard enforces that invariant at the mutation tool level itself, providing a
 * second line of defense behind the {@link io.relay.aspect.BaseMcpCallInterceptor}
 * that wraps {@code ToolCallback} beans. If the interceptor is bypassed — for example,
 * via direct bean injection in a test — this guard blocks the call and throws
 * {@link McpDirectMutationBlockedException} with a message that includes the name of
 * the blocked tool.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @AgentTool(category = ToolCategory.MUTATION, requiresSession = true)
 * public class RegisterMarketTool {
 *
 *     @Tool(description = "Registers a new market in the system")
 *     public String registerMarket(RegisterMarketRequest request) {
 *         MutationToolGuard.assertSessionContext("registerMarket");
 *         // safe to proceed — session and advisor chain are confirmed
 *     }
 * }
 * }</pre>
 *
 * @see io.relay.aspect.BaseMcpCallInterceptor
 * @see io.relay.guard.ToolSessionGuard
 * @see McpDirectMutationBlockedException
 */
public final class MutationToolGuard {

    private MutationToolGuard() {}

    /**
     * Verifies that an active session is bound to the current thread, which indicates
     * the call arrived through the ChatClient pipeline where the advisor chain ran.
     *
     * <p>Call this as the first statement of every mutation tool method.
     *
     * @param toolName the {@code @Tool(name = ...)} value for the calling tool, included
     *                 in the exception message to identify which mutation was blocked
     * @throws McpDirectMutationBlockedException if no session ID is present in
     *         {@link SessionContextHolder} for the current thread, indicating the call
     *         arrived directly through MCP without going through the advisor chain
     */
    public static void assertSessionContext(final String toolName) {
        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            throw new McpDirectMutationBlockedException(toolName);
        }
    }

    /**
     * Returns {@code true} if a non-blank session ID is bound to the current thread.
     *
     * @return {@code true} when a session context is active; {@code false} otherwise
     */
    public static boolean hasSessionContext() {
        String sessionId = SessionContextHolder.get();
        return sessionId != null && !sessionId.isBlank();
    }

    /**
     * Returns the session ID currently bound to this thread, or {@code null} if none.
     *
     * <p>Does not throw. Use {@link #assertSessionContext(String)} when the absence of a
     * session should be treated as an error.
     *
     * @return the active session ID, or {@code null} if no session is bound
     */
    public static String getSessionId() {
        return SessionContextHolder.get();
    }
}
