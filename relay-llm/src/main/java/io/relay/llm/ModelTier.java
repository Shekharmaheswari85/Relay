/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.llm;

/**
 * Classifies LLM models into two cost-performance tiers so that agents can route
 * requests to the most appropriate (and cost-effective) model automatically.
 *
 * <p>The tier is the bridge between agent logic and the LLM configuration:
 * each tier maps to a distinct {@link LlmModelConfig} entry in
 * {@code AgentLlmProperties} ({@code reasoning-model} and
 * {@code utility-model} respectively). At runtime, {@link ChatClientRegistry}
 * resolves the tier to a pre-built {@link org.springframework.ai.chat.client.ChatClient}.
 *
 * <h3>Routing example</h3>
 * <pre>{@code
 * @Autowired ChatClientRegistry registry;
 *
 * public String summarise(String text) {
 *     return registry.getClientForTier(ModelTier.UTILITY)
 *             .prompt()
 *             .user("Summarise: " + text)
 *             .call()
 *             .content();
 * }
 *
 * public AnalysisResult analyse(String data) {
 *     return registry.getClientForTier(ModelTier.REASONING)
 *             .prompt()
 *             .user("Analyse: " + data)
 *             .call()
 *             .entity(AnalysisResult.class);
 * }
 * }</pre>
 *
 * <h3>YAML configuration</h3>
 * <pre>{@code
 * relay:
 *   llm:
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
 * }</pre>
 *
 * <p>When no {@code utility-model} is configured, {@link ChatClientRegistry#getUtilityClient()}
 * falls back to the reasoning client so that agents function correctly with a single model.
 *
 * @see ChatClientRegistry
 * @see LlmModelConfig
 * @see LlmModelConfig
 */
public enum ModelTier {

    /**
     * High-capability tier intended for tasks that require multistep reasoning,
     * complex decision-making, code generation, or nuanced instruction-following.
     *
     * <p>Typical models: GPT-4o, Claude Sonnet, Gemini 2.5 Pro. These models are
     * more expensive per token and should be reserved for work where accuracy and
     * depth genuinely benefit from the extra capability.
     *
     * <p>Configured via {@code relay.llm.reasoning-model} in {@code application.yml}.
     */
    REASONING,

    /**
     * Cost-effective tier intended for tasks that are structurally simple: extraction,
     * reformatting, short summarization, classification, or filling structured templates.
     *
     * <p>Typical models: GPT-4o-mini, Claude Haiku, Gemini Flash. These models are
     * significantly cheaper per token and respond faster, making them suitable for
     * high-volume or latency-sensitive operations.
     *
     * <p>Configured via {@code relay.llm.utility-model} in {@code application.yml}.
     * Falls back to {@link #REASONING} if no utility model is configured.
     */
    UTILITY
}
