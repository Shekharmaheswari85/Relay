/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.llm;

import java.util.Collections;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;

import lombok.Getter;

/**
 * Central registry of pre-built {@link ChatClient} instances, keyed by both
 * {@link ModelTier} (reasoning / utility) and {@link LlmProvider}.
 *
 * <p>The registry is populated by {@code ChatClientAutoConfiguration}
 * at startup, using the model coordinates from {@code AgentLlmProperties}.
 * Agent code should never construct {@code ChatClient} instances directly — instead, inject
 * this registry and select the appropriate client for each call.
 *
 * <h3>Tier-based routing (recommended)</h3>
 * <p>Use the tier API to keep agent code decoupled from specific model names. The framework
 * can then swap models without changing agent logic:
 * <pre>{@code
 * @Autowired
 * private ChatClientRegistry registry;
 *
 * public String summarise(String text) {
 *     return registry.getClientForTier(ModelTier.UTILITY)
 *             .prompt().user("Summarise: " + text).call().content();
 * }
 *
 * public AnalysisResult analyse(String data) {
 *     return registry.getClientForTier(ModelTier.REASONING)
 *             .prompt().user("Analyse: " + data).call().entity(AnalysisResult.class);
 * }
 * }</pre>
 *
 * <h3>Provider-specific routing</h3>
 * <p>When a specific provider must be targeted (e.g., for a feature that only works on
 * Anthropic), use {@link #getClient(LlmProvider)}:
 * <pre>{@code
 * ChatClient anthropic = registry.getClient(LlmProvider.ANTHROPIC);
 * }</pre>
 *
 * <h3>Fallback behaviour</h3>
 * <p>If no {@code utility-model} is configured in YAML, {@link #getUtilityClient()} returns
 * the reasoning client instead of throwing — allowing single-model deployments to work
 * without change.
 *
 * @see ModelTier
 * @see LlmProvider
 * @see ModelTier
 * @see LlmProvider
 */
public class ChatClientRegistry {

    private final Map<LlmProvider, ChatClient> providerClients;

    @Getter
    private final ChatClient reasoningClient;

    private final ChatClient utilityClient;

    @Getter
    private final LlmProvider defaultProvider;

    public ChatClientRegistry(
            final Map<LlmProvider, ChatClient> providerClients,
            final ChatClient reasoningClient,
            final ChatClient utilityClient,
            final LlmProvider defaultProvider) {
        this.providerClients = Collections.unmodifiableMap(providerClients);
        this.reasoningClient = reasoningClient;
        this.utilityClient = utilityClient;
        this.defaultProvider = defaultProvider;
    }

    /**
     * Returns the {@link ModelTier#UTILITY} client.
     *
     * <p>Falls back to the {@link ModelTier#REASONING} client when no utility model is
     * configured, ensuring that agents work correctly in single-model deployments.
     *
     * @return the utility-tier {@code ChatClient}; never null
     */
    public ChatClient getUtilityClient() {
        return utilityClient != null ? utilityClient : reasoningClient;
    }

    /**
     * Returns the {@link ChatClient} mapped to the given {@link ModelTier}.
     *
     * <p>Delegates to {@link #getUtilityClient()} for {@link ModelTier#UTILITY} and to
     * {@link #reasoningClient} for {@link ModelTier#REASONING}. The utility client
     * falls back to the reasoning client when none is configured.
     *
     * @param tier the desired model tier; must not be null
     * @return the corresponding {@code ChatClient}; never null
     */
    public ChatClient getClientForTier(final ModelTier tier) {
        return tier == ModelTier.UTILITY ? getUtilityClient() : getReasoningClient();
    }

    /**
     * Returns the {@link ChatClient} for a specific provider.
     *
     * @param provider the desired provider; must not be null
     * @return the {@code ChatClient} registered for that provider
     * @throws IllegalArgumentException if no client is registered for the given provider;
     *         the message lists all configured providers for diagnostics
     */
    public ChatClient getClient(final LlmProvider provider) {
        ChatClient client = providerClients.get(provider);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No ChatClient configured for provider: " + provider + ". Available: " + providerClients.keySet());
        }
        return client;
    }

    /**
     * Returns the {@link ChatClient} for the default provider as declared in
     * {@code agent.llm.default-provider}.
     *
     * @return the default provider's {@code ChatClient}; never null
     * @throws IllegalArgumentException if the default provider has no registered client
     */
    public ChatClient getDefaultClient() {
        return getClient(defaultProvider);
    }

    /**
     * Returns an unmodifiable view of all provider-to-client mappings held in this registry.
     *
     * @return an immutable map from {@link LlmProvider} to its {@code ChatClient}
     */
    public Map<LlmProvider, ChatClient> getAllClients() {
        return providerClients;
    }
}
