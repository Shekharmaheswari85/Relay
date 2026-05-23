/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;

import io.relay.aspect.BaseMcpCallInterceptor;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring {@link org.springframework.context.annotation.Configuration} class that scans
 * the application context for all {@link AgentTool}-annotated beans and wires them into the
 * Spring AI tool-calling infrastructure automatically.
 *
 * <p>Teams using this library do not need to write their own tool registration config.
 * Annotating a class with {@link AgentTool} is sufficient — this configuration picks it up
 * at startup and produces the following beans:
 *
 * <ul>
 *   <li>{@link org.springframework.ai.tool.ToolCallbackProvider agentToolCallbackProvider} —
 *       a {@link org.springframework.ai.tool.method.MethodToolCallbackProvider} backed by
 *       all discovered {@link AgentTool} beans. If an
 *       {@link io.relay.aspect.BaseMcpCallInterceptor} bean is present, the provider is
 *       wrapped to enforce session-context guards on protected tools before they are exposed
 *       over the MCP SSE endpoint. This bean is conditional — define your own
 *       {@code ToolCallbackProvider} bean to replace it.</li>
 *   <li>{@code mutationToolNames} — a {@link java.util.Set Set&lt;String&gt;} of bean names
 *       whose {@link AgentTool#category()} is {@link ToolCategory#MUTATION}. Other components
 *       (e.g., confirmation gates) use this set to identify state-changing tools.</li>
 *   <li>{@code sessionRequiredToolNames} — a {@link java.util.Set Set&lt;String&gt;} of bean
 *       names where {@link AgentTool#requiresSession()} is {@code true}. Used by MCP
 *       interceptors to block session-less direct invocations.</li>
 * </ul>
 *
 * <h3>Zero-configuration example</h3>
 * <pre>{@code
 * @AgentTool(category = ToolCategory.MUTATION, requiresSession = true)
 * public class CancelOrderTool {
 *
 *     @Tool(description = "Cancels the order for the current session user")
 *     public CancelResult cancelOrder(@ToolParam(description = "Order ID") String orderId) {
 *         return orderService.cancel(orderId);
 *     }
 * }
 * }</pre>
 *
 * <p>No additional {@code @Configuration} or {@code @Bean} methods are required.
 * The tool appears in the {@code ToolCallbackProvider} and in {@code mutationToolNames}
 * and {@code sessionRequiredToolNames} automatically.
 *
 * @see AgentTool
 * @see ToolCategory
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public final class AutoDiscoveryToolConfig {

    private final ApplicationContext applicationContext;
    private final BaseMcpCallInterceptor mcpCallInterceptor;

    public AutoDiscoveryToolConfig(
            final ApplicationContext applicationContext,
            final ObjectProvider<BaseMcpCallInterceptor> mcpCallInterceptorProvider) {
        this.applicationContext = applicationContext;
        this.mcpCallInterceptor = mcpCallInterceptorProvider.getIfAvailable();
    }

    /**
     * Builds and returns a {@link ToolCallbackProvider} backed by every
     * {@link AgentTool}-annotated bean in the application context.
     *
     * <p>At startup this method:
     * <ol>
     *   <li>Collects all beans annotated with {@link AgentTool}.</li>
     *   <li>Passes them to
     *       {@link org.springframework.ai.tool.method.MethodToolCallbackProvider}, which
     *       reflects over each bean's {@code @Tool}-annotated methods and registers them
     *       as callable tools.</li>
     *   <li>If a {@link io.relay.aspect.BaseMcpCallInterceptor} bean is present,
     *       wraps the raw provider so that tools categorised as {@link ToolCategory#MUTATION}
     *       or marked with {@link AgentTool#requiresSession()} {@code = true} reject
     *       session-less MCP calls.</li>
     * </ol>
     *
     * <p>If no {@link AgentTool} beans are found, logs a warning and returns an empty
     * provider. This bean is conditional — define your own {@code ToolCallbackProvider}
     * to override it.
     *
     * @return the fully configured provider; never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallbackProvider.class)
    public ToolCallbackProvider agentToolCallbackProvider() {
        Map<String, Object> toolBeans = applicationContext.getBeansWithAnnotation(AgentTool.class);

        if (toolBeans.isEmpty()) {
            log.warn("No @AgentTool beans found in application context. "
                    + "Annotate your tool classes with @AgentTool for auto-discovery.");
            return MethodToolCallbackProvider.builder().toolObjects().build();
        }

        List<Object> allTools = new ArrayList<>(toolBeans.values());
        logDiscoveredTools(toolBeans);

        ToolCallbackProvider raw = MethodToolCallbackProvider.builder()
                .toolObjects(Objects.requireNonNull(allTools.toArray(new Object[0]), "Tool objects must not be null"))
                .build();

        if (mcpCallInterceptor != null) {
            log.info("Wrapping tools with MCP interceptor: {}", mcpCallInterceptor.getClass().getSimpleName());
            return mcpCallInterceptor.wrap(raw);
        }

        return raw;
    }

    /**
     * Produces the set of bean names whose {@link AgentTool#category()} is
     * {@link ToolCategory#MUTATION}.
     *
     * <p>Downstream components — such as confirmation-gate advisors and MCP interceptors —
     * inject this set to determine which tools require additional safeguards before
     * execution. The set is computed once at startup from the live application context.
     *
     * @return an immutable-like view of mutation tool bean names; never {@code null},
     *         may be empty if no mutation tools are registered
     */
    @Bean
    public Set<String> mutationToolNames() {
        Map<String, Object> toolBeans = applicationContext.getBeansWithAnnotation(AgentTool.class);
        return toolBeans.entrySet().stream()
                .filter(e -> {
                    AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(e.getValue().getClass(), AgentTool.class);
                    return annotation != null && annotation.category() == ToolCategory.MUTATION;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Produces the set of bean names where {@link AgentTool#requiresSession()} is
     * {@code true}.
     *
     * <p>{@link io.relay.aspect.BaseMcpCallInterceptor} uses this set (alongside the
     * mutation tool names it auto-detects) to decide which tool callbacks to wrap with a
     * session-context guard. The set is computed once at startup.
     *
     * @return an immutable-like view of session-required tool bean names; never {@code null},
     *         may be empty if no tools declare {@code requiresSession = true}
     */
    @Bean
    public Set<String> sessionRequiredToolNames() {
        Map<String, Object> toolBeans = applicationContext.getBeansWithAnnotation(AgentTool.class);
        return toolBeans.entrySet().stream()
                .filter(e -> {
                    AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(e.getValue().getClass(), AgentTool.class);
                    return annotation != null && annotation.requiresSession();
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private void logDiscoveredTools(final Map<String, Object> toolBeans) {
        Map<ToolCategory, List<String>> byCategory = toolBeans.entrySet().stream()
                .collect(Collectors.groupingBy(
                        e -> {
                            AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(e.getValue().getClass(), AgentTool.class);
                            return annotation != null ? annotation.category() : ToolCategory.DISCOVERY;
                        },
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

        log.info("Auto-discovered {} @AgentTool beans:", toolBeans.size());
        byCategory.getOrDefault(ToolCategory.DISCOVERY, Collections.emptyList())
                .forEach(name -> log.info("  [DISCOVERY] {}", name));
        byCategory.getOrDefault(ToolCategory.MUTATION, Collections.emptyList())
                .forEach(name -> log.info("  [MUTATION]  {}", name));
        byCategory.getOrDefault(ToolCategory.MONITORING, Collections.emptyList())
                .forEach(name -> log.info("  [MONITORING] {}", name));
    }
}
