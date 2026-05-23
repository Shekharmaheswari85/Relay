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
package io.agentcore.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import io.agentcore.prompt.PromptLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link AgentSystemPromptProvider} that resolves system prompts from
 * classpath files declared in {@code agent.llm.system-prompts}.
 *
 * <p>This bean is registered automatically by {@code ChatClientAutoConfiguration}
 * and is suppressed via {@code @ConditionalOnMissingBean(AgentSystemPromptProvider.class)},
 * so it is always replaced when a custom implementation is provided.
 *
 * <h3>Configuration</h3>
 * <p>Map agent names to classpath-relative prompt file paths:
 * <pre>{@code
 * agent:
 *   llm:
 *     system-prompts:
 *       default: prompts/default-system-prompt.txt
 *       onboarding-market: prompts/onboarding-system-prompt.txt
 *       eda-analysis: prompts/eda-analysis-system-prompt.txt
 * }</pre>
 *
 * <h3>Loading behaviour</h3>
 * <ul>
 *   <li>Prompt files are loaded lazily on first access and then cached in memory
 *       for the lifetime of the application using a {@link java.util.concurrent.ConcurrentHashMap}.</li>
 *   <li>If a file cannot be found or read, the error is logged and {@code null} is
 *       returned — the agent continues without a system prompt rather than failing at startup.</li>
 *   <li>Agent names not present in the configured map return {@code null} immediately
 *       without attempting any file I/O.</li>
 * </ul>
 *
 * @see AgentSystemPromptProvider
 * @see AgentLlmProperties#systemPrompts
 * @see io.agentcore.prompt.PromptLoader
 */
@Component
@ConditionalOnMissingBean(AgentSystemPromptProvider.class)
@EnableConfigurationProperties(AgentLlmProperties.class)
@Slf4j
public class DefaultAgentSystemPromptProvider implements AgentSystemPromptProvider {

    private final Map<String, String> promptPaths;
    private final Map<String, String> loadedPrompts = new ConcurrentHashMap<>();

    /**
     * Constructs the provider from the LLM properties.
     *
     * @param properties the {@code agent.llm.*} configuration; the
     *                   {@code system-prompts} map is read to discover which agent names
     *                   have prompt files configured
     */
    public DefaultAgentSystemPromptProvider(final AgentLlmProperties properties) {
        this.promptPaths = properties.getSystemPrompts();
        log.info("System prompts configured for main agents: {}", promptPaths.keySet());
    }

    /**
     * Returns the system prompt for the named agent, loading it from the classpath on
     * first access and caching it for subsequent calls.
     *
     * @param agentName the agent identifier; returns {@code null} when null or blank
     * @return the prompt content, or {@code null} if no path is configured for this agent
     *         or the file could not be loaded
     */
    @Override
    public String getSystemPrompt(final String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }

        String cached = loadedPrompts.get(agentName);
        if (cached != null) {
            return cached;
        }

        String path = promptPaths.get(agentName);
        if (path == null || path.isBlank()) {
            log.debug("No system prompt configured for main agent '{}'", agentName);
            return null;
        }

        String prompt = loadPrompt(agentName, path);
        if (prompt != null) {
            loadedPrompts.put(agentName, prompt);
        }
        return prompt;
    }

    private String loadPrompt(final String agentName, final String path) {
        try {
            String prompt = PromptLoader.load(path);
            log.info("Loaded system prompt for main agent '{}': {} chars from {}", agentName, prompt.length(), path);
            return prompt;
        } catch (Exception e) {
            log.error("Failed to load system prompt for main agent '{}' from '{}': {}", agentName, path, e.getMessage());
            return null;
        }
    }
}
