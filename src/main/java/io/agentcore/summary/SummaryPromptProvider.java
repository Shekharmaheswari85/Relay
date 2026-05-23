/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.summary;

/**
 * Service-provider interface that supplies system prompts for LLM-driven session
 * summarization.
 *
 * <p>During summarization, {@link BaseLlmSessionSummarizer} calls
 * {@link #getSummaryPrompt(String)} with the agent identifier to retrieve the system
 * prompt that instructs the LLM how to compress session history. Separating prompt
 * retrieval from the summarization logic lets teams configure prompts via YAML, a
 * database, or any external system without touching summarizer code.
 *
 * <h3>Framework contract</h3>
 * <ul>
 *   <li>Implementations may return {@code null} to signal that no agent-specific prompt
 *       is configured; {@link BaseLlmSessionSummarizer#getFallbackSystemPrompt()} is
 *       tried next before an exception is raised.</li>
 *   <li>Implementations should cache loaded prompts; the method may be called on every
 *       agent turn that triggers summarization.</li>
 *   <li>Implementations are expected to be Spring beans so that auto-configuration can
 *       inject them into {@link BaseLlmSessionSummarizer}.</li>
 * </ul>
 *
 * <h3>Built-in implementation</h3>
 * <p>{@link DefaultSummaryPromptProvider} reads prompt paths from
 * {@code agent.llm.summary-prompts} YAML configuration and is auto-registered when no
 * other {@code SummaryPromptProvider} bean is present.
 *
 * @see DefaultSummaryPromptProvider
 * @see BaseLlmSessionSummarizer
 */
public interface SummaryPromptProvider {

    /**
     * Returns the system prompt used to summarize sessions for the given agent, or
     * {@code null} if no agent-specific prompt is configured.
     *
     * <p>When {@code null} is returned, {@link BaseLlmSessionSummarizer} falls back to
     * {@link #getDefaultSummaryPrompt()} and then to
     * {@link BaseLlmSessionSummarizer#getFallbackSystemPrompt()}.
     *
     * @param agentId the agent identifier used to look up the prompt,
     *                e.g. {@code "onboarding-market"}; never null
     * @return the system prompt text, or {@code null} if not configured for this agent
     */
    String getSummaryPrompt(String agentId);

    /**
     * Returns the default system prompt used when no agent-specific prompt is configured,
     * or {@code null} if no default is available.
     *
     * <p>The default implementation returns {@code null}. Override when a shared fallback
     * prompt should apply to all agents not explicitly configured via
     * {@link #getSummaryPrompt(String)}.
     *
     * @return the default system prompt text, or {@code null} if not configured
     */
    default String getDefaultSummaryPrompt() {
        return null;
    }
}
