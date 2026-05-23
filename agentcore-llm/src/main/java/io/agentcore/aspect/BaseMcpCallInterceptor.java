/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.aspect;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;

import io.agentcore.exception.McpDirectMutationBlockedException;
import io.agentcore.session.SessionContextHolder;
import io.agentcore.tool.AgentTool;
import io.agentcore.tool.ToolCategory;

import lombok.extern.slf4j.Slf4j;

/**
 * Base class for wrapping Spring AI {@link ToolCallbackProvider} beans so that
 * session-required tools cannot be invoked directly through the MCP SSE endpoint.
 *
 * <h3>The threat this interceptor mitigates</h3>
 * <p>Spring AI's MCP server exposes every registered {@link ToolCallbackProvider} bean via
 * an SSE endpoint, allowing any MCP client — including a developer's local Claude Desktop —
 * to invoke tools without going through the ChatClient pipeline. Advisor chains
 * (confirmation gating, step gating, rate limiting, audit logging) only execute inside
 * that pipeline, so a direct MCP call bypasses every safeguard.
 *
 * <p>This interceptor closes that gap by wrapping the {@code ToolCallback} for each
 * protected tool with a {@code GuardedToolCallback} that checks for an active session
 * before delegating. When no session is present the call is rejected with
 * {@link McpDirectMutationBlockedException} before the tool body is ever entered.
 *
 * <h3>What triggers the guard</h3>
 * <p>A tool is automatically protected when:
 * <ul>
 *   <li>Its declaring class carries {@code @AgentTool(category = ToolCategory.MUTATION)}, or</li>
 *   <li>Its declaring class carries {@code @AgentTool(requiresSession = true)}, or</li>
 *   <li>Its name is returned by {@link #getAdditionalProtectedToolNames()}.</li>
 * </ul>
 * The set of protected tools is computed once and cached on first use.
 *
 * <h3>Extending — auto-detection only (most common)</h3>
 * <pre>{@code
 * @Component
 * public class AppMcpCallInterceptor extends BaseMcpCallInterceptor {
 *
 *     public AppMcpCallInterceptor(ApplicationContext ctx) {
 *         super(ctx);
 *     }
 * }
 * }</pre>
 *
 * <h3>Extending — adding extra protected tools</h3>
 * <pre>{@code
 * @Component
 * public class AppMcpCallInterceptor extends BaseMcpCallInterceptor {
 *
 *     public AppMcpCallInterceptor(ApplicationContext ctx) {
 *         super(ctx);
 *     }
 *
 *     @Override
 *     protected Set<String> getAdditionalProtectedToolNames() {
 *         return Set.of("sensitiveReadTool", "exportDataTool");
 *     }
 * }
 * }</pre>
 *
 * @see DefaultMcpCallInterceptor
 * @see McpDirectMutationBlockedException
 */
@Slf4j
public abstract class BaseMcpCallInterceptor {

    private final ApplicationContext applicationContext;

    private Set<String> cachedMutationToolNames;

    protected BaseMcpCallInterceptor() {
        this(null);
    }

    protected BaseMcpCallInterceptor(final ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Returns the set of tool names that must be guarded in addition to those
     * detected automatically from {@code @AgentTool} annotations.
     *
     * <p>Override this method to protect tools that cannot be annotated with
     * {@code @AgentTool} (for example, tools from a third-party library) or to
     * protect read-only tools whose caller context must still be validated.
     * The default implementation returns an empty set.
     *
     * @return additional tool names to protect; never {@code null}
     */
    protected Set<String> getAdditionalProtectedToolNames() {
        return Collections.emptySet();
    }

    /**
     * Returns the exception to throw when a protected tool is invoked without an active
     * session.
     *
     * <p>Override to substitute a domain-specific exception type. The default returns
     * {@link McpDirectMutationBlockedException}.
     *
     * @param toolName the name of the tool that was blocked
     * @return the exception to throw; never {@code null}
     */
    protected RuntimeException createBlockedException(final String toolName) {
        return new McpDirectMutationBlockedException(toolName);
    }

    /**
     * Returns the complete set of tool names whose callbacks must enforce session context.
     *
     * <p>The result is the union of auto-detected names (from {@code @AgentTool} annotations)
     * and any names returned by {@link #getAdditionalProtectedToolNames()}.
     *
     * @return the combined set of protected tool names; never {@code null}
     */
    protected final Set<String> getProtectedToolNames() {
        Set<String> autoDetected = getAutoDetectedMutationToolNames();
        Set<String> additional = getAdditionalProtectedToolNames();

        if (additional.isEmpty()) {
            return autoDetected;
        }

        // Combine both sets
        return Stream.concat(autoDetected.stream(), additional.stream())
                .collect(Collectors.toSet());
    }

    /**
     * Auto-detects mutation tools from @AgentTool annotations.
     */
    private Set<String> getAutoDetectedMutationToolNames() {
        if (cachedMutationToolNames != null) {
            return cachedMutationToolNames;
        }

        if (applicationContext == null) {
            log.debug("No ApplicationContext available for tool auto-detection");
            cachedMutationToolNames = Collections.emptySet();
            return cachedMutationToolNames;
        }

        cachedMutationToolNames = applicationContext.getBeansWithAnnotation(AgentTool.class)
                .entrySet()
                .stream()
                .filter(entry -> {
                    AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(entry.getValue().getClass(), AgentTool.class);
                    return annotation != null
                            && (annotation.category() == ToolCategory.MUTATION || annotation.requiresSession());
                })
                // Try to get the actual tool name from the @Tool annotation method
                // Fall back to bean name if not found
                .map(entry -> resolveToolName(entry.getKey(), entry.getValue()))
                .collect(Collectors.toSet());

        log.info("Auto-detected {} mutation/session-required tools: {}",
                cachedMutationToolNames.size(), cachedMutationToolNames);
        return cachedMutationToolNames;
    }

    /**
     * Resolves the tool name for a bean. Tries to find @Tool annotated method name,
     * falls back to bean name.
     */
    private String resolveToolName(final String beanName, final Object bean) {
        // Check for @Tool annotated methods
        for (var method : bean.getClass().getMethods()) {
            var toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null) {
                String name = toolAnnotation.name();
                if (!name.isBlank()) {
                    return name;
                }
                // If no name specified, Spring AI uses the method name
                return method.getName();
            }
        }
        // Fallback to bean name
        return beanName;
    }

    /**
     * Returns {@code true} if the named tool requires an active session context.
     *
     * @param toolName the tool name to check
     * @return {@code true} if the tool is in the protected set
     */
    protected boolean isProtectedTool(final String toolName) {
        return getProtectedToolNames().contains(toolName);
    }

    /**
     * Wraps each protected callback in the supplied {@link ToolCallbackProvider} with a
     * session-context guard, and returns a new provider containing the guarded callbacks.
     *
     * <p>Callbacks for unprotected tools are returned unchanged. If tool metadata cannot be
     * read for a particular callback, that callback is returned without a guard and a warning
     * is logged.
     *
     * @param provider the original provider whose protected callbacks should be guarded
     * @return a new {@link ToolCallbackProvider} whose protected tools enforce session context;
     *         unprotected tools are unaffected
     */
    public ToolCallbackProvider wrap(final ToolCallbackProvider provider) {
        ToolCallback[] original = provider.getToolCallbacks();
        ToolCallback[] guarded = Arrays.stream(original)
                .map(cb -> {
                    try {
                        String name = cb.getToolDefinition().name();
                        if (isProtectedTool(name)) {
                            log.debug("Wrapping protected tool with session guard: {}", name);
                            return new GuardedToolCallback(cb, this);
                        }
                    } catch (Exception ex) {
                        log.warn("Could not inspect tool for protection, skipping guard: {}", ex.getMessage());
                    }
                    return cb;
                })
                .toArray(ToolCallback[]::new);

        long guardedCount = Arrays.stream(guarded)
                .filter(GuardedToolCallback.class::isInstance)
                .count();
        log.info("MCP interceptor: wrapped {}/{} tools with session guard", guardedCount, guarded.length);

        return () -> guarded;
    }

    /**
     * Decorator around a {@link ToolCallback} that rejects invocations when no session
     * ID is present on the current thread.
     *
     * <p>When {@link SessionContextHolder#get()} returns a non-blank value the call is
     * forwarded to the delegate unchanged. When it is absent, the interceptor's
     * {@link BaseMcpCallInterceptor#createBlockedException(String)} is called and the
     * resulting exception is thrown without entering the delegate.
     */
    private static class GuardedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final BaseMcpCallInterceptor interceptor;

        GuardedToolCallback(final ToolCallback delegate, final BaseMcpCallInterceptor interceptor) {
            this.delegate = delegate;
            this.interceptor = interceptor;
        }

        @Override
        public @NonNull ToolDefinition getToolDefinition() {
            return Objects.requireNonNull(delegate.getToolDefinition(), "Tool definition must not be null");
        }

        @Override
        public @NonNull String call(final @NonNull String toolInput) {
            String sessionId = SessionContextHolder.get();
            if (sessionId == null || sessionId.isBlank()) {
                String toolName = delegate.getToolDefinition().name();
                log.warn("Blocked MCP call to protected tool={} — no session context", toolName);
                throw interceptor.createBlockedException(toolName);
            }
            return Objects.requireNonNull(delegate.call(toolInput), "Tool callback result must not be null");
        }
    }
}
