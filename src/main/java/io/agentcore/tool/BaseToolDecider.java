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
 * Strategy that narrows the set of tools presented to the LLM for a single conversation turn.
 *
 * <p>When an agent has many tools registered, sending all of them in every request wastes
 * tokens and can confuse the model into choosing an irrelevant tool. A {@code BaseToolDecider}
 * inspects the user's message and the current session context, then returns the subset of
 * tools that are actually useful for that turn. This reduces both token cost and the
 * probability of tool-misuse errors.
 *
 * <p>Implement this interface and register the implementation as a Spring bean to replace the
 * default {@link PassThroughToolDecider}. The framework calls
 * {@link #selectTools(String, List, Map)} before each LLM invocation.
 *
 * <h3>Custom implementation example</h3>
 * <pre>{@code
 * @Component
 * public class KeywordToolDecider implements BaseToolDecider {
 *
 *     @Override
 *     public List<String> selectTools(String message, List<String> availableTools,
 *                                     Map<String, Object> context) {
 *         String lower = message.toLowerCase();
 *         List<String> selected = new ArrayList<>();
 *
 *         if (lower.contains("report") || lower.contains("export")) {
 *             selected.add("generateReport");
 *             selected.add("exportToCsv");
 *         }
 *         if (lower.contains("order") || lower.contains("cancel")) {
 *             selected.add("lookupOrder");
 *             selected.add("cancelOrder");
 *         }
 *
 *         return selected.isEmpty() ? availableTools : selected;
 *     }
 * }
 * }</pre>
 *
 * <p>When no custom {@code BaseToolDecider} bean is defined, the framework falls back to
 * {@link PassThroughToolDecider}, which returns all tools unchanged.
 *
 * @see PassThroughToolDecider
 */
public interface BaseToolDecider {

    /**
     * Returns the tools that should be presented to the LLM for the current turn.
     *
     * <p>Implementations must return a non-null list. Returning {@code availableTools}
     * unchanged is equivalent to using {@link PassThroughToolDecider}. Returning an empty
     * list means the LLM will have no tools for this turn, which may cause it to respond
     * without invoking any tool — only do this intentionally.
     *
     * @param message        the user's message for the current turn; never {@code null}
     * @param availableTools the full set of tool names registered with the agent;
     *                       never {@code null}
     * @param context        the current session context map, providing any in-flight state
     *                       (e.g. workflow step, user role); never {@code null}, may be empty
     * @return the subset of tool names to expose to the LLM for this turn; never {@code null}
     */
    List<String> selectTools(String message, List<String> availableTools, Map<String, Object> context);

    /**
     * Returns the tools that should be presented to the LLM when no session context is
     * available. Delegates to {@link #selectTools(String, List, Map)} with an empty context.
     *
     * @param message        the user's message for the current turn; never {@code null}
     * @param availableTools the full set of tool names registered with the agent;
     *                       never {@code null}
     * @return the subset of tool names to expose to the LLM for this turn; never {@code null}
     */
    default List<String> selectTools(final String message, final List<String> availableTools) {
        return selectTools(message, availableTools, Map.of());
    }
}
