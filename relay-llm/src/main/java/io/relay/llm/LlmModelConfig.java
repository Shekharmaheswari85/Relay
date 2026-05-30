/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.llm;

/**
 * Immutable value object that captures everything the framework needs to build an HTTP
 * request to a specific LLM deployment: the provider identity, the model name, optional
 * version tokens, and the strategy used to construct the completions URL path.
 *
 * <p>{@code LlmModelConfig} is the resolved form of the YAML stanzas
 * {@code relay.llm.reasoning-model} and {@code relay.llm.utility-model}. The auto-configuration
 * in {@code ChatClientAutoConfiguration} reads those stanzas, builds one
 * {@code LlmModelConfig} per configured tier, and stores them in a
 * {@link ChatClientRegistry}.
 *
 * <h3>Constructor parameters</h3>
 * <ul>
 *   <li>{@code provider} — the LLM provider ({@link LlmProvider#OPENAI},
 *       {@link LlmProvider#GOOGLE}, {@link LlmProvider#ANTHROPIC}, etc.)</li>
 *   <li>{@code model} — the model name or deployment ID (e.g., {@code gpt-4o},
 *       {@code gemini-2.5-flash}, {@code claude-sonnet-4})</li>
 *   <li>{@code version} — optional model snapshot version (e.g., {@code 2025-04-14},
 *       {@code 001}); required for {@link LlmProvider#OPENAI} and
 *       {@link LlmProvider#GOOGLE} unless a custom {@code pathStrategy} is supplied</li>
 *   <li>{@code apiVersion} — optional API version for OpenAI-style deployments
 *       (e.g., {@code 2024-02-01}); required for {@link LlmProvider#OPENAI},
 *       {@link LlmProvider#LLAMA}, and {@link LlmProvider#GEMMA} unless a custom
 *       {@code pathStrategy} is supplied</li>
 *   <li>{@code pathStrategy} — optional {@link CompletionsPathStrategy} that overrides
 *       the provider's built-in path builder; pass {@code null} to use the default</li>
 * </ul>
 *
 * <h3>Preferred construction — Builder</h3>
 * <pre>{@code
 * LlmModelConfig config = LlmModelConfig.builder()
 *         .provider(LlmProvider.OPENAI)
 *         .model("gpt-4o")
 *         .version("2025-04-14")
 *         .apiVersion("2024-02-01")
 *         .build();
 * }</pre>
 *
 * <h3>Custom path strategy</h3>
 * <p>Bypass the built-in path format when targeting a non-standard gateway:
 * <pre>{@code
 * LlmModelConfig config = LlmModelConfig.builder()
 *         .provider(LlmProvider.OPENAI)
 *         .model("gpt-4o")
 *         .pathStrategy(CompletionsPathStrategy.azureOpenAi())
 *         .build();
 * }</pre>
 *
 * @param provider         the LLM provider; must not be {@code null}
 * @param model            the model name or deployment ID; must not be blank
 * @param version          the model snapshot version; may be blank
 * @param apiVersion       the API version query parameter; may be blank
 * @param pathStrategy     a custom path builder; {@code null} delegates to the provider default
 *
 * @see LlmProvider
 * @see CompletionsPathStrategy
 * @see ModelTier
 */
public record LlmModelConfig(
        LlmProvider provider,
        String model,
        String version,
        String apiVersion,
        CompletionsPathStrategy pathStrategy) {

    private static final String DEFAULT_MODEL = "gpt-4o";

    /**
     * Convenience constructor that omits the path strategy, causing
     * {@link #resolveCompletionsPath()} to delegate to the provider's built-in path builder.
     *
     * @param provider   the LLM provider; must not be {@code null}
     * @param model      the model name or deployment ID; must not be blank
     * @param version    the model snapshot version; may be blank
     * @param apiVersion the API version query parameter; may be blank
     */
    public LlmModelConfig(final LlmProvider provider, final String model,
                          final String version, final String apiVersion) {
        this(provider, model, version, apiVersion, null);
    }

    /**
     * Resolves the completions URL path for this model configuration.
     *
     * <p>Delegates to the {@link CompletionsPathStrategy} when one is set; otherwise
     * falls back to {@link LlmProvider#buildCompletionsPath(String, String, String)}.
     * The returned value is appended to {@code relay.llm.gateway-base-url} when
     * constructing the full request URL.
     *
     * @return the completions path segment to append to the gateway base URL; never null
     */
    public String resolveCompletionsPath() {
        if (pathStrategy != null) {
            return pathStrategy.buildPath(model, version, apiVersion);
        }
        return provider.buildCompletionsPath(model, version, apiVersion);
    }

    /**
     * Creates a minimal, zero-configuration default targeting OpenAI GPT-4o with no
     * version or API-version constraint.
     *
     * <p>Intended as a safe fallback when no model is explicitly configured. Production
     * deployments should always supply explicit version values through the builder or YAML.
     *
     * @return a default {@code LlmModelConfig} for OpenAI GPT-4o
     */
    public static LlmModelConfig defaultConfig() {
        return new LlmModelConfig(LlmProvider.OPENAI, DEFAULT_MODEL, "", "");
    }

    /**
     * Returns a new {@link Builder} with sensible defaults ({@link LlmProvider#OPENAI},
     * model {@code gpt-4o}).
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link LlmModelConfig} with built-in validation.
     *
     * <p>Defaults: provider = {@link LlmProvider#OPENAI}, model = {@code gpt-4o},
     * version = {@code ""}, apiVersion = {@code ""}, pathStrategy = {@code null}.
     *
     * <p>Validation rules (applied on {@link #build()}):
     * <ul>
     *   <li>{@code model} must be non-blank.</li>
     *   <li>When no custom {@code pathStrategy} is set, {@code version} is required for
     *       {@link LlmProvider#OPENAI} and {@link LlmProvider#GOOGLE}.</li>
     *   <li>When no custom {@code pathStrategy} is set, {@code apiVersion} is required for
     *       {@link LlmProvider#OPENAI}, {@link LlmProvider#LLAMA}, and
     *       {@link LlmProvider#GEMMA}.</li>
     * </ul>
     */
    public static class Builder {
        private LlmProvider provider = LlmProvider.OPENAI;
        private String model = DEFAULT_MODEL;
        private String version = "";
        private String apiVersion = "";
        private CompletionsPathStrategy pathStrategy;

        /**
         * Sets the provider; defaults to {@link LlmProvider#OPENAI} when null.
         *
         * @param provider the provider enum constant
         * @return this builder
         */
        public Builder provider(final LlmProvider provider) {
            this.provider = provider != null ? provider : LlmProvider.OPENAI;
            return this;
        }

        /**
         * Sets the provider by name string; delegates to {@link LlmProvider#fromString(String)},
         * which defaults to {@link LlmProvider#OPENAI} for unrecognized values.
         *
         * @param providerName case-insensitive provider name (e.g., {@code "anthropic"})
         * @return this builder
         */
        public Builder provider(final String providerName) {
            this.provider = LlmProvider.fromString(providerName);
            return this;
        }

        /**
         * Sets the model name or deployment ID; defaults to {@code gpt-4o} when null or blank.
         *
         * @param model the model identifier
         * @return this builder
         */
        public Builder model(final String model) {
            this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
            return this;
        }

        /**
         * Sets the model snapshot version (e.g., {@code 2025-04-14}). Treats null as empty.
         *
         * @param version the model version string; may be blank
         * @return this builder
         */
        public Builder version(final String version) {
            this.version = version != null ? version : "";
            return this;
        }

        /**
         * Sets the API version query parameter for OpenAI-style endpoints
         * (e.g., {@code 2024-02-01}). Treats null as empty.
         *
         * @param apiVersion the API version string; may be blank
         * @return this builder
         */
        public Builder apiVersion(final String apiVersion) {
            this.apiVersion = apiVersion != null ? apiVersion : "";
            return this;
        }

        /**
         * Overrides the provider's built-in path builder with a custom strategy.
         *
         * <p>When set, version-validation rules for the provider are skipped because the
         * custom strategy is fully responsible for constructing the path. Use the factory
         * methods on {@link CompletionsPathStrategy} for common gateway conventions.
         *
         * @param pathStrategy the custom path strategy; pass {@code null} to use the provider default
         * @return this builder
         */
        public Builder pathStrategy(final CompletionsPathStrategy pathStrategy) {
            this.pathStrategy = pathStrategy;
            return this;
        }

        /**
         * Validates configuration and returns an immutable {@link LlmModelConfig}.
         *
         * @return the constructed configuration
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public LlmModelConfig build() {
            validate();
            return new LlmModelConfig(provider, model, version, apiVersion, pathStrategy);
        }

        private void validate() {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("LLM model is required");
            }

            if (pathStrategy != null) {
                return;
            }

            boolean versionRequired = provider == LlmProvider.OPENAI || provider == LlmProvider.GOOGLE;
            if (versionRequired && (version == null || version.isBlank())) {
                throw new IllegalArgumentException(
                        "LLM version is required for provider " + provider + " (model=" + model + ")");
            }

            boolean apiVersionRequired =
                    provider == LlmProvider.OPENAI || provider == LlmProvider.LLAMA || provider == LlmProvider.GEMMA;
            if (apiVersionRequired && (apiVersion == null || apiVersion.isBlank())) {
                throw new IllegalArgumentException(
                        "LLM api_version is required for provider " + provider + " (model=" + model + ")");
            }
        }
    }
}
