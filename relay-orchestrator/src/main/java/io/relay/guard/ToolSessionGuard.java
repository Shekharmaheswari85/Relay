/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.guard;

import java.util.function.Function;

import io.relay.exception.DirectToolCallBlockedException;
import io.relay.session.SessionContextHolder;

/**
 * Static guard that prevents any protected tool from executing without an active session.
 *
 * <p>This guard operates as a second line of defense inside each tool's own method body,
 * complementing the {@link io.relay.aspect.BaseMcpCallInterceptor} that guards at the
 * Spring AI {@code ToolCallback} layer. Even if the interceptor is bypassed — for example,
 * by direct bean invocation in a test or a Spring AI API change — this guard ensures the
 * tool itself refuses to run without a session bound to the current thread via
 * {@link SessionContextHolder}.
 *
 * <p>The threat this guard mitigates is unauthorized or untracked tool invocation: without
 * a session context, advisor chains (confirmation gating, audit logging, rate limiting) would
 * never have run, so the call is unconditionally blocked.
 *
 * <h3>Usage — simple</h3>
 * <pre>{@code
 * @AgentTool(category = ToolCategory.MUTATION, requiresSession = true)
 * public class CreateTicketTool {
 *
 *     @Tool(description = "Creates a support ticket")
 *     public String createTicket(CreateTicketRequest request) {
 *         ToolSessionGuard.assertSessionContext("createTicket");
 *         // safe to proceed
 *     }
 * }
 * }</pre>
 *
 * <h3>Usage — custom exception</h3>
 * <pre>{@code
 * ToolSessionGuard.assertSessionContext("createTicket",
 *         name -> new MyDomainBlockedException(name, "session required"));
 * }</pre>
 *
 * @see io.relay.aspect.BaseMcpCallInterceptor
 * @see io.relay.guard.MutationToolGuard
 * @see DirectToolCallBlockedException
 */
public final class ToolSessionGuard {

    private ToolSessionGuard() {}

    /**
     * Verifies that an active session is bound to the current thread.
     *
     * <p>Call this as the first statement of any tool method that must not execute
     * outside a session context.
     *
     * @param toolName the {@code @Tool(name = ...)} value for the calling tool, used in the
     *                 exception message to identify which tool was blocked
     * @throws DirectToolCallBlockedException if no session ID is present in
     *         {@link SessionContextHolder} for the current thread
     */
    public static void assertSessionContext(final String toolName) {
        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            throw new DirectToolCallBlockedException(toolName);
        }
    }

    /**
     * Verifies that an active session is bound to the current thread, throwing a
     * caller-supplied exception when the check fails.
     *
     * <p>Use this overload when the calling domain requires a specific exception type
     * instead of the default {@link DirectToolCallBlockedException}.
     *
     * @param toolName         the {@code @Tool(name = ...)} value for the calling tool
     * @param exceptionFactory a function that receives the tool name and returns the
     *                         exception to throw; must not return {@code null}
     * @throws RuntimeException the exception produced by {@code exceptionFactory} if no
     *         session ID is present on the current thread
     */
    public static void assertSessionContext(
            final String toolName, final Function<String, RuntimeException> exceptionFactory) {
        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            throw exceptionFactory.apply(toolName);
        }
    }

    /**
     * Returns the session ID currently bound to this thread, or {@code null} if none.
     *
     * <p>Does not throw. Use {@link #assertSessionContext(String)} when the absence of a
     * session should be treated as an error.
     *
     * @return the active session ID, or {@code null} if no session is bound
     */
    public static String getSessionIdOrNull() {
        return SessionContextHolder.get();
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
}
