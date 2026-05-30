/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.stereotype.Component;

/**
 * Marks a Spring bean class as an agent tool that the framework discovers, registers,
 * and optionally exposes via the MCP SSE endpoint.
 *
 * <p>Annotating a class with {@code @AgentTool} has three effects:
 * <ol>
 *   <li>The class is registered as a Spring {@code @Component}, so no separate
 *       {@code @Bean} declaration is needed.</li>
 *   <li>{@link AutoDiscoveryToolConfig} picks it up at startup and registers every
 *       {@code @Tool}-annotated method with Spring AI's
 *       {@link org.springframework.ai.tool.method.MethodToolCallbackProvider}, making
 *       the methods callable by the LLM.</li>
 *   <li>If the Spring AI MCP server is on the classpath, the tool methods are
 *       automatically exposed over the SSE endpoint.</li>
 * </ol>
 *
 * <p>The {@link #category()} attribute drives two downstream behaviors:
 * <ul>
 *   <li>Model-tier routing — {@link ToolCategory#MUTATION} tools are routed to the
 *       {@link io.relay.llm.ModelTier#REASONING} model; all others default to
 *       {@link io.relay.llm.ModelTier#UTILITY}.</li>
 *   <li>MCP session guards — {@link ToolCategory#MUTATION} tools and tools with
 *       {@link #requiresSession()} {@code = true} are wrapped by
 *       {@link io.relay.aspect.BaseMcpCallInterceptor} so that direct MCP calls
 *       without an active session are rejected.</li>
 * </ul>
 *
 * <h3>Minimal example</h3>
 * <pre>{@code
 * @AgentTool
 * public class ProductLookupTool {
 *
 *     @Tool(description = "Looks up a product by SKU and returns its details")
 *     public ProductDetails lookupProduct(@ToolParam(description = "The product SKU") String sku) {
 *         return productService.findBySku(sku);
 *     }
 * }
 * }</pre>
 *
 * <h3>Mutation tool with session guard</h3>
 * <pre>{@code
 * @AgentTool(category = ToolCategory.MUTATION, requiresSession = true)
 * public class CancelOrderTool {
 *
 *     @Tool(description = "Cancels an order on behalf of the current session user")
 *     public CancelResult cancelOrder(@ToolParam(description = "Order ID to cancel") String orderId) {
 *         return orderService.cancel(orderId);
 *     }
 * }
 * }</pre>
 *
 * @see AutoDiscoveryToolConfig
 * @see ToolCategory
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AgentTool {

    /**
     * Classifies the tool by its behavioral impact on the system.
     *
     * <p>The category controls two things:
     * <ul>
     *   <li><b>Model-tier routing</b> — {@link ToolCategory#MUTATION} is routed to the
     *       higher-capability {@link io.relay.llm.ModelTier#REASONING} model;
     *       {@link ToolCategory#DISCOVERY} and {@link ToolCategory#MONITORING} are routed to
     *       the cost-effective {@link io.relay.llm.ModelTier#UTILITY} model.</li>
     *   <li><b>MCP session guard</b> — {@link ToolCategory#MUTATION} tools are automatically
     *       protected by {@link io.relay.aspect.BaseMcpCallInterceptor}; direct MCP calls
     *       without an active session context will be rejected.</li>
     * </ul>
     *
     * <p>Defaults to {@link ToolCategory#DISCOVERY} — the safest, lowest-cost tier for
     * read-only tools.
     *
     * @return the tool's behavioral category
     */
    ToolCategory category() default ToolCategory.DISCOVERY;

    /**
     * Declares that this tool requires an active session context to execute correctly.
     *
     * <p>When {@code true}, {@link io.relay.aspect.BaseMcpCallInterceptor} wraps the
     * tool callback and rejects any direct MCP call that arrives without a session ID bound
     * in {@link io.relay.session.SessionContextHolder}. This prevents the tool from
     * being invoked outside the agent's advisor pipeline where session state is not yet set.
     *
     * <p>Note: {@link ToolCategory#MUTATION} tools are already protected by the MCP
     * interceptor regardless of this flag. Use {@code requiresSession = true} on
     * {@link ToolCategory#DISCOVERY} or {@link ToolCategory#MONITORING} tools that
     * depend on session-scoped data.
     *
     * <p>Defaults to {@code false}.
     *
     * @return {@code true} if the tool must have an active session context
     */
    boolean requiresSession() default false;
}
