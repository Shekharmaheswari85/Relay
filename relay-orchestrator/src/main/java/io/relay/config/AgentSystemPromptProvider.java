/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

/**
 * SPI for resolving the system prompt text for a named main agent.
 *
 * <p>Every main agent in the framework has a named system prompt that guides the LLM's
 * behaviour for that agent's domain.  This interface decouples prompt resolution from
 * the agent runtime so that teams can load prompts from YAML configuration, a database,
 * a remote service, or any other source without modifying framework code.
 *
 * <h3>Default implementation</h3>
 * <p>{@link DefaultAgentSystemPromptProvider} is registered automatically by
 * {@code ChatClientAutoConfiguration} when no custom bean of this type exists in the
 * application context.  It reads prompts from classpath files declared under
 * {@code agent.llm.system-prompts}:
 *
 * <pre>{@code
 * agent:
 *   llm:
 *     system-prompts:
 *       default: prompts/default-system-prompt.txt       # used by ChatClientAutoConfiguration
 *       onboarding-market: prompts/onboarding-system-prompt.txt
 *       eda-analysis: prompts/eda-analysis-system-prompt.txt
 * }</pre>
 *
 * <h3>Custom implementation</h3>
 * <p>Register a Spring {@code @Component} (or {@code @Bean}) that implements this interface
 * to override prompt resolution entirely.  The default implementation is suppressed via
 * {@code @ConditionalOnMissingBean}.
 *
 * <pre>{@code
 * @Component
 * public class DatabasePromptProvider implements AgentSystemPromptProvider {
 *
 *     private final PromptRepository repo;
 *
 *     @Override
 *     public String getSystemPrompt(String agentName) {
 *         return repo.findByAgentName(agentName)
 *                    .map(PromptEntity::getContent)
 *                    .orElse(null);
 *     }
 * }
 * }</pre>
 *
 * <h3>Implementor contract</h3>
 * <ul>
 *   <li>{@link #getSystemPrompt(String)} must return {@code null} (not throw) when no
 *       prompt is configured for the given agent name.</li>
 *   <li>Implementations may cache loaded prompts but must be thread-safe.</li>
 *   <li>Sub-agents manage their own system prompts internally and do not appear in this SPI.</li>
 * </ul>
 *
 * @see DefaultAgentSystemPromptProvider
 * @see AgentLlmProperties#systemPrompts
 */
public interface AgentSystemPromptProvider {

    /**
     * The conventional agent name key used for the shared default system prompt.
     * This key is looked up by {@code ChatClientAutoConfiguration} when configuring
     * the {@link org.springframework.ai.chat.client.ChatClient}'s default system message.
     */
    String DEFAULT_AGENT = "default";

    /**
     * Returns the system prompt text for the specified main agent.
     *
     * @param agentName the main agent identifier (e.g., {@code "onboarding-market"},
     *                  {@code "eda-analysis"}); must not be null
     * @return the system prompt string, or {@code null} if no prompt is configured for
     *         this agent name
     */
    String getSystemPrompt(String agentName);

    /**
     * Returns the system prompt for the {@link #DEFAULT_AGENT} key ({@code "default"}).
     *
     * <p>Used by {@code ChatClientAutoConfiguration} when setting the shared
     * {@code ChatClient}'s default system message.  Delegates to
     * {@link #getSystemPrompt(String)} with {@link #DEFAULT_AGENT}.
     *
     * @return the default system prompt, or {@code null} if not configured
     */
    default String getSystemPrompt() {
        return getSystemPrompt(DEFAULT_AGENT);
    }

    /**
     * Returns {@code true} when a non-blank system prompt is configured for the given agent.
     *
     * @param agentName the agent identifier to check; must not be null
     * @return {@code true} if a non-blank prompt is available; {@code false} otherwise
     */
    default boolean hasPrompt(String agentName) {
        String prompt = getSystemPrompt(agentName);
        return prompt != null && !prompt.isBlank();
    }
}
