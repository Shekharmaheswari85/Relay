/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import io.agentcore.llm.ModelTier;

import lombok.extern.slf4j.Slf4j;

/**
 * Convention-based {@link ToolTierRegistry} that derives each tool's {@link ModelTier}
 * automatically from the {@link AgentTool#category()} annotation at startup.
 *
 * <p>The mapping rules are:
 * <ul>
 *   <li>{@link ToolCategory#MUTATION} → {@link ModelTier#REASONING} — state-changing
 *       operations are routed to the higher-capability model to reduce accidental errors.</li>
 *   <li>{@link ToolCategory#DISCOVERY} → {@link ModelTier#UTILITY} — read-only exploration
 *       does not require expensive reasoning.</li>
 *   <li>{@link ToolCategory#MONITORING} → {@link ModelTier#UTILITY} — observability queries
 *       are similarly lightweight.</li>
 * </ul>
 *
 * <p>Tier resolution is lazy: the first call to {@link #getTier(String)} triggers a
 * one-time scan of all {@link AgentTool}-annotated beans in the Spring
 * {@link org.springframework.context.ApplicationContext}. Results are cached in a
 * {@link java.util.concurrent.ConcurrentHashMap} for subsequent calls.
 *
 * <p>This bean is conditional — it is only registered when no other
 * {@link ToolTierRegistry} bean is present in the context. To apply custom tier rules,
 * define your own {@code @Component} that implements {@link ToolTierRegistry} and this
 * implementation will back off automatically.
 *
 * @see ToolTierRegistry
 * @see AgentTool#category()
 */
@Component
@ConditionalOnMissingBean(ToolTierRegistry.class)
@Slf4j
public class DefaultToolTierRegistry implements ToolTierRegistry {

    private final Map<String, ModelTier> tierCache = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;
    private volatile boolean initialized = false;

    /**
     * Creates a registry that will scan the given application context for
     * {@link AgentTool}-annotated beans on the first {@link #getTier(String)} call.
     *
     * @param applicationContext the Spring context used to discover {@link AgentTool} beans
     */
    public DefaultToolTierRegistry(final ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Returns the {@link ModelTier} for the given tool name.
     *
     * <p>On the first invocation, scans all {@link AgentTool}-annotated beans and builds
     * the tier cache. Subsequent calls are served from the cache without further scanning.
     * Tools that were not found during scanning fall back to {@link #getDefaultTier()}.
     *
     * @param toolName the canonical tool name (from {@code @Tool(name = "...")} or method name)
     * @return the assigned model tier; never {@code null}
     */
    @Override
    public ModelTier getTier(final String toolName) {
        ensureInitialized();
        return tierCache.getOrDefault(toolName, getDefaultTier());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            scanAgentTools();
            initialized = true;
        }
    }
    private void scanAgentTools() {
        applicationContext.getBeansWithAnnotation(AgentTool.class).forEach((beanName, bean) -> {
            AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(bean.getClass(), AgentTool.class);
            if (annotation == null) {
                return;
            }

            String toolName = resolveToolName(beanName, bean);
            ModelTier tier = mapCategoryToTier(annotation.category());
            tierCache.put(toolName, tier);
            log.debug("Auto-mapped tool tier: {}={} (category={})", toolName, tier, annotation.category());
        });

        log.info("DefaultToolTierRegistry initialized: {} tools mapped (REASONING={}, UTILITY={})",
                tierCache.size(),
                tierCache.values().stream().filter(t -> t == ModelTier.REASONING).count(),
                tierCache.values().stream().filter(t -> t == ModelTier.UTILITY).count());
    }

    private String resolveToolName(final String beanName, final Object bean) {
        for (var method : bean.getClass().getMethods()) {
            var toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null) {
                String name = toolAnnotation.name();
                if (!name.isBlank()) {
                    return name;
                }
                return method.getName();
            }
        }
        return beanName;
    }

    private ModelTier mapCategoryToTier(final ToolCategory category) {
        return switch (category) {
            case MUTATION -> ModelTier.REASONING;
            case DISCOVERY, MONITORING -> ModelTier.UTILITY;
        };
    }
}
