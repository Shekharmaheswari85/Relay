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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import io.agentcore.config.AgentLlmProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link SummaryPromptProvider} that resolves system prompts for session
 * summarization from classpath resources declared in the application's YAML configuration.
 *
 * <p>Prompt file paths are read from {@link AgentLlmProperties#summaryPrompts}, which
 * maps agent identifiers to classpath-relative paths. File content is loaded lazily on the
 * first request for each agent key and then cached in memory for the lifetime of the
 * application context.
 *
 * <h3>YAML configuration</h3>
 * <pre>{@code
 * agent:
 *   llm:
 *     summary-prompts:
 *       default:            prompts/default-summary.txt
 *       onboarding-market:  prompts/onboarding-summary.txt
 *       eda-analysis:       prompts/eda-summary.txt
 * }</pre>
 *
 * <p>The special key {@code default} maps to {@link #getDefaultSummaryPrompt()}, which is
 * used as the fallback when no agent-specific entry exists for the requested agent ID.
 *
 * <p>This bean is auto-registered only when no other {@link SummaryPromptProvider} bean is
 * present in the application context ({@code @ConditionalOnMissingBean}). Provide a custom
 * implementation to load prompts from a database, remote configuration service, or any
 * other backing store.
 *
 * <p>Agent ID lookup is case-insensitive (normalised to lower-case) with a case-sensitive
 * fallback, so {@code "Onboarding-Market"} resolves to the same entry as
 * {@code "onboarding-market"}.
 *
 * @see SummaryPromptProvider
 * @see AgentLlmProperties
 */
@Component
@Slf4j
@ConditionalOnMissingBean(SummaryPromptProvider.class)
public class DefaultSummaryPromptProvider implements SummaryPromptProvider {

    private static final String DEFAULT_AGENT_KEY = "default";

    private final Map<String, String> summaryPromptPaths;
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    public DefaultSummaryPromptProvider(final AgentLlmProperties properties) {
        this.summaryPromptPaths = properties.getSummaryPrompts();
    }

    @Override
    public String getSummaryPrompt(final String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return getDefaultSummaryPrompt();
        }

        String normalizedKey = agentId.trim().toLowerCase();

        // Check cache first
        if (promptCache.containsKey(normalizedKey)) {
            return promptCache.get(normalizedKey);
        }

        // Try exact match
        String promptPath = summaryPromptPaths.get(normalizedKey);
        if (promptPath == null) {
            // Try with agentId as-is (case-sensitive)
            promptPath = summaryPromptPaths.get(agentId);
        }

        if (promptPath == null) {
            log.debug("No summary prompt configured for agent '{}', using default", agentId);
            return getDefaultSummaryPrompt();
        }

        String prompt = loadPromptFromClasspath(promptPath);
        if (prompt != null) {
            promptCache.put(normalizedKey, prompt);
        }
        return prompt;
    }

    @Override
    public String getDefaultSummaryPrompt() {
        if (promptCache.containsKey(DEFAULT_AGENT_KEY)) {
            return promptCache.get(DEFAULT_AGENT_KEY);
        }

        String defaultPath = summaryPromptPaths.get(DEFAULT_AGENT_KEY);
        if (defaultPath == null) {
            return null;
        }

        String prompt = loadPromptFromClasspath(defaultPath);
        if (prompt != null) {
            promptCache.put(DEFAULT_AGENT_KEY, prompt);
        }
        return prompt;
    }

    private String loadPromptFromClasspath(final String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String resourcePath = path;
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("Summary prompt resource not found: {}", resourcePath);
                return null;
            }
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            log.warn("Failed to load summary prompt from {}: {}", resourcePath, ex.getMessage());
            return null;
        }
    }
}
