/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * {@link org.springframework.boot.context.properties.ConfigurationProperties} binding for
 * the {@code agent.llm.*} namespace, which configures the LLM gateway connection, model
 * tiers, system prompts, SSL/TLS options, and audit headers.
 *
 * <p>These properties drive {@link io.agentcore.config.ChatClientAutoConfiguration}, which
 * uses them to construct the {@link io.agentcore.llm.ChatClientRegistry} containing the
 * reasoning and utility {@link org.springframework.ai.chat.client.ChatClient} instances.
 *
 * <h3>Full configuration example</h3>
 * <pre>{@code
 * agent:
 *   llm:
 *     gateway-base-url: https://api.openai.com
 *     api-key: ${LLM_API_KEY}
 *     default-provider: openai
 *     temperature: 0.1
 *     max-tokens: 4096
 *     reasoning-model:
 *       provider: openai
 *       model: gpt-4o
 *       version: "2025-04-14"
 *       api-version: "2024-02-01"
 *     utility-model:
 *       provider: openai
 *       model: gpt-4o-mini
 *       version: "2024-07-18"
 *       api-version: "2024-02-01"
 *     system-prompts:
 *       default: prompts/default-system-prompt.txt
 *       onboarding-market: prompts/onboarding-system-prompt.txt
 *       eda-analysis: prompts/eda-analysis-system-prompt.txt
 *     summary-prompts:
 *       onboarding-market: prompts/onboarding-summary.txt
 *     ssl:
 *       trust-all: false
 *       allow-insecure-trust-all: false
 *       ca-path: ${CA_BUNDLE_PATH:}
 *     audit:
 *       user-type: NO_END_USER
 *       user-name: agent_system
 *       user-agent: my-agent/1.0
 *       user-ip: ${POD_IP:}
 * }</pre>
 *
 * <h3>Minimal configuration</h3>
 * <p>Only {@code gateway-base-url} and {@code reasoning-model} (with its required fields)
 * are strictly needed to get a working agent. All other properties have safe defaults.
 *
 * @see io.agentcore.config.ChatClientAutoConfiguration
 * @see io.agentcore.llm.ChatClientRegistry
 * @see io.agentcore.llm.ModelTier
 */
@Data
@ConfigurationProperties(prefix = "agent.llm")
public class AgentLlmProperties {

    /**
     * Base URL of the LLM gateway (or direct provider endpoint).
     * The completions path produced by the active {@link io.agentcore.llm.CompletionsPathStrategy}
     * is appended to this URL for every request.
     * Default: {@code "https://api.openai.com"} (direct OpenAI).
     */
    private String gatewayBaseUrl = "https://api.openai.com";

    /**
     * API key credential sent in the provider-specific auth header.
     * Should be externalised via an environment variable, e.g., {@code ${LLM_API_KEY}}.
     */
    private String apiKey;

    /**
     * Name of the default {@link io.agentcore.llm.LlmProvider} used when routing by
     * provider is requested but no explicit provider is specified.
     * Accepted values (case-insensitive): {@code openai}, {@code anthropic},
     * {@code google}, {@code llama}, {@code gemma}. Default: {@code "openai"}.
     */
    private String defaultProvider = "openai";

    /**
     * Sampling temperature applied to all model calls (0.0 – 2.0).
     * Lower values produce more deterministic outputs; higher values increase creativity.
     * Default: {@code 0.1}.
     */
    private double temperature = 0.1;

    /**
     * Maximum number of tokens in each model response.
     * Default: {@code 4096}.
     */
    private int maxTokens = 4096;

    /**
     * Model coordinates for the {@link io.agentcore.llm.ModelTier#REASONING} tier.
     * Used by {@code ChatClientAutoConfiguration} to build the high-capability client.
     * At minimum, set {@code provider}, {@code model}, and (for OpenAI) {@code version}
     * and {@code api-version}.
     */
    private ModelConfig reasoningModel;

    /**
     * Model coordinates for the {@link io.agentcore.llm.ModelTier#UTILITY} tier.
     * Used by {@code ChatClientAutoConfiguration} to build the cost-effective client.
     * When absent, the utility client falls back to the reasoning client.
     */
    private ModelConfig utilityModel;

    /**
     * Additional model configurations available for explicit provider lookup via
     * {@link io.agentcore.llm.ChatClientRegistry#getClient(io.agentcore.llm.LlmProvider)}.
     * Each entry must specify a unique {@code provider} value.
     */
    private List<ModelConfig> providers = new ArrayList<>();

    /**
     * TLS/SSL configuration for the HTTP client that calls the LLM gateway.
     */
    private SslConfig ssl = new SslConfig();

    /**
     * Audit header values forwarded to the LLM gateway on every request.
     * Used by implementations of {@link LlmGatewayHeadersContributor}.
     */
    private AuditConfig audit = new AuditConfig();

    /**
     * Maps agent names to classpath-relative paths for their system prompt files.
     *
     * <p>The special key {@code "default"} is read by
     * {@code ChatClientAutoConfiguration} to set the system message on the shared
     * {@code ChatClient}.  All other keys are consumed by
     * {@link DefaultAgentSystemPromptProvider} and resolved on demand.
     *
     * <p>Sub-agents declare their own prompts inside their class implementations and do
     * not appear in this map.
     *
     * <pre>{@code
     * agent:
     *   llm:
     *     system-prompts:
     *       default: prompts/default-system-prompt.txt
     *       onboarding-market: prompts/onboarding-system-prompt.txt
     * }</pre>
     */
    private Map<String, String> systemPrompts = new HashMap<>();

    /**
     * Maps agent names to classpath-relative paths for session-summarisation prompt files.
     *
     * <p>Consumed by {@link io.agentcore.summary.BaseLlmSessionSummarizer} to compress
     * long conversation histories before they exceed the model's context window.
     *
     * <pre>{@code
     * agent:
     *   llm:
     *     summary-prompts:
     *       onboarding-market: prompts/onboarding-summary.txt
     * }</pre>
     */
    private Map<String, String> summaryPrompts = new HashMap<>();

    /**
     * Coordinates for a single LLM model deployment.
     *
     * <p>Instances of this class are converted to {@link io.agentcore.llm.LlmModelConfig}
     * objects by {@code ChatClientAutoConfiguration} when building {@code ChatClient} beans.
     */
    @Data
    public static class ModelConfig {

        /**
         * Provider name (case-insensitive). Accepted values: {@code openai}, {@code anthropic},
         * {@code google}, {@code llama}, {@code gemma}. Defaults to {@code openai} when null.
         */
        private String provider;

        /**
         * Model name or deployment ID (e.g., {@code gpt-4o}, {@code gemini-2.5-flash},
         * {@code claude-sonnet-4}).
         */
        private String model;

        /**
         * Model snapshot version appended as {@code @version} in the URL
         * (e.g., {@code 2025-04-14}). Required for {@code OPENAI} and {@code GOOGLE}
         * unless a custom {@link io.agentcore.llm.CompletionsPathStrategy} is used.
         */
        private String version;

        /**
         * OpenAI API version query parameter (e.g., {@code 2024-02-01}).
         * Required for {@code OPENAI}, {@code LLAMA}, and {@code GEMMA} providers
         * unless a custom path strategy is used.
         */
        private String apiVersion;
    }

    /**
     * TLS/SSL options for the LLM gateway HTTP client ({@code agent.llm.ssl.*}).
     */
    @Data
    public static class SslConfig {

        /**
         * When {@code true}, disables all certificate validation.
         * Requires {@link #allowInsecureTrustAll} to also be {@code true}.
         * Use only in local development environments.
         * Default: {@code false}.
         */
        private boolean trustAll = false;

        /**
         * Explicit acknowledgement that insecure trust-all TLS mode is intentional.
         * This second switch prevents accidental enablement from a copied config file.
         * Default: {@code false}.
         */
        private boolean allowInsecureTrustAll = false;

        /**
         * Classpath or filesystem path to a custom CA certificate bundle ({@code .pem} or
         * {@code .crt}). Required when the LLM gateway uses a private CA certificate that
         * is not in the JVM's default trust store.
         */
        private String caPath;
    }

    /**
     * Audit identity headers forwarded to the LLM gateway on every request
     * ({@code agent.llm.audit.*}).
     *
     * <p>These values are passed to registered {@link LlmGatewayHeadersContributor}
     * implementations, which map them to the gateway's specific header names.
     */
    @Data
    public static class AuditConfig {

        /**
         * Caller category forwarded in the gateway audit header.
         * Default: {@code "NO_END_USER"} (machine-to-machine call with no human context).
         */
        private String userType = "NO_END_USER";

        /**
         * Service account or system name forwarded in the gateway audit header.
         * Default: {@code "agent_system"}.
         */
        private String userName = "agent_system";

        /**
         * User-agent string forwarded in the gateway audit header.
         * Leave blank to omit the header. Default: {@code ""}.
         */
        private String userAgent = "";

        /**
         * IP address of the originating caller forwarded in the gateway audit header.
         * Typically set to the pod IP in Kubernetes deployments (e.g., {@code ${POD_IP:}}).
         * Leave blank to omit the header.
         */
        private String userIp;
    }

}
