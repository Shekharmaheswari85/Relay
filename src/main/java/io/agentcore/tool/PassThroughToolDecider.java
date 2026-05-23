/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.tool;

import java.util.List;
import java.util.Map;

/**
 * {@link BaseToolDecider} implementation that returns every available tool unchanged,
 * performing no filtering.
 *
 * <p>This is the library's default decider: the LLM always receives the full set of
 * registered tools for every turn. It is appropriate when the tool set is small (fewer than
 * roughly a dozen tools) or during initial development before usage patterns are known.
 *
 * <p>As the tool set grows, a context-aware implementation of {@link BaseToolDecider}
 * should replace this class to reduce token cost and improve routing accuracy. To replace
 * it, define a Spring bean that implements {@link BaseToolDecider} — the framework's
 * default registration of {@code PassThroughToolDecider} is conditional on no other bean
 * being present.
 *
 * <pre>{@code
 * @Bean
 * @ConditionalOnMissingBean(BaseToolDecider.class)
 * public BaseToolDecider toolDecider() {
 *     return new PassThroughToolDecider();
 * }
 * }</pre>
 *
 * @see BaseToolDecider
 */
public class PassThroughToolDecider implements BaseToolDecider {

    /**
     * Returns {@code availableTools} without modification.
     *
     * @param message        the user's message for the current turn (ignored)
     * @param availableTools the full set of registered tool names
     * @param context        the current session context map (ignored)
     * @return the same {@code availableTools} list, unfiltered
     */
    @Override
    public List<String> selectTools(
            final String message,
            final List<String> availableTools,
            final Map<String, Object> context) {
        return availableTools;
    }
}
