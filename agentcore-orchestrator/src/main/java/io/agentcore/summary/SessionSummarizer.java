/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.summary;

import java.util.Map;

import io.agentcore.model.BaseAgentSession;

/**
 * Service-provider interface for compacting session conversation history into a rolling
 * LLM-generated summary.
 *
 * <p>As a session progresses, the accumulated context JSON and chat history can exceed
 * the model's effective context window. Implementations of this interface are called
 * by the agent orchestration layer at the beginning of each turn to detect this condition
 * and, when necessary, invoke an LLM to produce a shorter canonical summary that replaces
 * the verbose history. The new summary is persisted back to the session so that future
 * turns start with a compact but faithful representation of past state.
 *
 * <h3>Framework contract</h3>
 * <ul>
 *   <li>{@link #summarizeIfNeeded} is the hot path — it must be cheap when
 *       {@code promptChars} is below the threshold. Implementations must not perform
 *       LLM calls when the check fails.</li>
 *   <li>Both methods return {@code true} only when a new summary was actually generated
 *       and persisted; {@code false} means the session state was not changed.</li>
 *   <li>Implementations must be thread-safe; multiple sessions may be summarized
 *       concurrently.</li>
 * </ul>
 *
 * <h3>Built-in implementation</h3>
 * <p>{@link BaseLlmSessionSummarizer} provides the LLM invocation, threshold logic, and
 * context-key management. Teams extend it and supply domain-specific payload extraction.
 *
 * @param <S> the session entity type, must extend {@link BaseAgentSession}
 * @see BaseLlmSessionSummarizer
 */
public interface SessionSummarizer<S extends BaseAgentSession> {

    /**
     * Generates and persists a summary of the session when {@code promptChars} meets or
     * exceeds the threshold returned by {@link #getPromptSummaryThreshold()}.
     *
     * <p>When the threshold is not reached, the method returns {@code false} immediately
     * without making any LLM calls or modifying session state.
     *
     * @param session     the session entity whose context will be summarized; never null
     * @param context     the parsed context map from {@code session.getContextJson()};
     *                    the implementation may mutate this map to store the new summary
     * @param userMessage the latest user message, included in the summarization payload
     *                    to give the LLM recency context; never null
     * @param promptChars the estimated character count of the current assembled prompt,
     *                    used to evaluate whether the threshold is exceeded
     * @return {@code true} if a summary was generated and persisted; {@code false} if the
     *         threshold was not reached or if the LLM call produced no content
     */
    boolean summarizeIfNeeded(S session, Map<String, Object> context, String userMessage, int promptChars);

    /**
     * Generates and persists a summary of the session unconditionally, regardless of the
     * current prompt size.
     *
     * <p>Use this method to force a summary refresh at domain-defined milestones (e.g.
     * after a major workflow step completes) rather than waiting for the size threshold.
     *
     * @param session     the session entity whose context will be summarized; never null
     * @param context     the parsed context map; the implementation may mutate this map
     * @param userMessage the latest user message, included in the summarization payload;
     *                    never null
     * @return {@code true} if a summary was generated and persisted; {@code false} if the
     *         LLM call produced no usable content
     */
    boolean summarizeNow(S session, Map<String, Object> context, String userMessage);

    /**
     * Returns the prompt character count at or above which {@link #summarizeIfNeeded}
     * triggers an LLM summarization call.
     *
     * @return the threshold in characters; always positive
     */
    int getPromptSummaryThreshold();
}
