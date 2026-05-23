/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.router;

import java.util.List;
import java.util.Map;

/**
 * Classifies a user message into one of a set of named intents, enabling dynamic routing
 * across {@link io.relay.agent.BaseSubAgent} implementations.
 *
 * <p>Intent routing is typically invoked at the beginning of a session's first turn (or
 * whenever the orchestrator needs to re-classify the user's goal) and the result is stored as
 * the session's current workflow step. The {@link io.relay.orchestrator.BaseAgentOrchestrator}
 * then uses that step to
 * select the correct sub-agent for all subsequent turns.
 *
 * <p>The interface deliberately separates the routing contract from any particular
 * classification strategy. {@link LlmIntentRouter} provides the standard LLM-powered
 * implementation; teams may supply rule-based or embedding-similarity alternatives by
 * implementing this interface directly.
 *
 * <p>Implementations must be safe to call from a virtual-thread context. They must never
 * throw — classification failures must be absorbed and returned as {@link #DEFAULT_INTENT}.
 *
 * <h3>Typical orchestrator usage</h3>
 * <pre>{@code
 * @Component
 * public class OrderAgentOrchestrator
 *         extends BaseAgentOrchestrator<OrderSessionDO, OrderStep> {
 *
 *     private final BaseIntentRouter intentRouter;
 *
 *     private static final List<String> ORDER_INTENTS = List.of(
 *             "order_status", "return_request", "product_inquiry", "smalltalk");
 *
 *     @Override
 *     protected OrderStep parseCurrentStep(OrderSessionDO session) {
 *         if (session.getCurrentStep() == null) {
 *             String intent = intentRouter.route(session.getLastMessage(), ORDER_INTENTS);
 *             return OrderStep.fromIntent(intent);
 *         }
 *         return OrderStep.valueOf(session.getCurrentStep());
 *     }
 * }
 * }</pre>
 *
 * @see LlmIntentRouter
 * @see io.relay.orchestrator.BaseAgentOrchestrator
 */
public interface BaseIntentRouter {

    /**
     * The intent name returned when classification fails, the message is blank, or the
     * classifier cannot match any candidate. Consumers should map this value to a sensible
     * fallback workflow step (e.g., a general-purpose or smalltalk sub-agent).
     */
    String DEFAULT_INTENT = "default";

    /**
     * Classifies the user message into one of the candidate intents, using the session context
     * for disambiguation when needed.
     *
     * <p>Implementations must never throw. On any failure — including empty inputs, network
     * errors, or unrecognised model output — return {@link #DEFAULT_INTENT}.
     *
     * @param message  the raw user message for this turn; may be blank (implementations should
     *                 return {@link #DEFAULT_INTENT} for blank input)
     * @param intents  the ordered list of candidate intent names the caller recognises; an empty
     *                 list causes an immediate {@link #DEFAULT_INTENT} return; a single-element
     *                 list returns that element without consulting the classifier
     * @param context  the current session context map, supplied for disambiguation — for example,
     *                 to distinguish "cancel" (cancel an order) from "cancel" (cancel a return);
     *                 never {@code null}, but may be empty
     * @return the matched intent name from {@code intents} (preserving original casing), or
     *         {@link #DEFAULT_INTENT} if no match could be determined; never {@code null}
     */
    String route(String message, List<String> intents, Map<String, Object> context);

    /**
     * Classifies the user message into one of the candidate intents without session context.
     *
     * <p>Delegates to {@link #route(String, List, Map)} with an empty context map. Use this
     * convenience overload when no disambiguation context is available or relevant.
     *
     * @param message the raw user message for this turn
     * @param intents the ordered list of candidate intent names the caller recognises
     * @return the matched intent name from {@code intents}, or {@link #DEFAULT_INTENT};
     *         never {@code null}
     */
    default String route(final String message, final List<String> intents) {
        return route(message, intents, Map.of());
    }
}
