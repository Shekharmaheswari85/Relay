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
package io.agentcore.llm;

/**
 * SPI for constructing the completions endpoint path that is appended to the LLM gateway
 * base URL when making chat-completion HTTP requests.
 *
 * <p>Different LLM gateways and providers use incompatible URL structures.  This functional
 * interface decouples path construction from the rest of the framework so that any gateway
 * convention can be plugged in without modifying core classes.
 *
 * <h3>Default behaviour</h3>
 * <p>When no custom strategy is set on a {@link LlmModelConfig}, the framework delegates
 * to {@link LlmProvider#buildCompletionsPath(String, String, String)} — the per-provider
 * built-in logic that handles the Walmart LLM Gateway conventions for each supported
 * provider.
 *
 * <h3>Implementor contract</h3>
 * <ul>
 *   <li>Implementations must be pure functions (no side effects, no mutable state).</li>
 *   <li>{@link #buildPath} must never return {@code null}.</li>
 *   <li>Implementations should tolerate null or blank {@code version} and
 *       {@code apiVersion} arguments gracefully.</li>
 * </ul>
 *
 * <h3>Built-in strategies</h3>
 * <p>Static factory methods on this interface cover the most common gateway conventions:
 * <ul>
 *   <li>{@link #walmarGatewayOpenAi()} — Walmart LLM Gateway OpenAI path with
 *       {@code @version} suffix</li>
 *   <li>{@link #azureOpenAi()} — Azure OpenAI deployment path without the {@code @version}
 *       suffix</li>
 *   <li>{@link #openAiDirect()} — direct OpenAI API path {@code /v1/chat/completions}</li>
 *   <li>{@link #anthropic()} — Anthropic Messages API path {@code /v1/messages}</li>
 *   <li>{@link #googleAi()} — Google AI (Gemini) generate-content path</li>
 * </ul>
 *
 * <h3>Inline custom strategy</h3>
 * <pre>{@code
 * CompletionsPathStrategy myGateway =
 *         (model, version, apiVersion) -> "/llm/v2/" + model + "/completions";
 *
 * LlmModelConfig config = LlmModelConfig.builder()
 *         .provider(LlmProvider.OPENAI)
 *         .model("gpt-4o")
 *         .pathStrategy(myGateway)
 *         .build();
 * }</pre>
 *
 * <h3>Selecting a built-in strategy</h3>
 * <pre>{@code
 * LlmModelConfig config = LlmModelConfig.builder()
 *         .provider(LlmProvider.OPENAI)
 *         .model("gpt-4o")
 *         .apiVersion("2024-02-01")
 *         .pathStrategy(CompletionsPathStrategy.azureOpenAi())
 *         .build();
 * }</pre>
 *
 * @see LlmProvider
 * @see LlmModelConfig
 */
@FunctionalInterface
public interface CompletionsPathStrategy {

    /**
     * Builds the completions endpoint path to append to the LLM gateway base URL.
     *
     * @param model      the model name or deployment ID (e.g., {@code gpt-4o},
     *                   {@code gemini-2.5-flash}); callers should supply a non-null value,
     *                   but implementations must handle null gracefully
     * @param version    optional model snapshot version (e.g., {@code 2025-04-14},
     *                   {@code 001}); may be null or blank
     * @param apiVersion optional API version query parameter for OpenAI-style endpoints
     *                   (e.g., {@code 2024-02-01}); may be null or blank
     * @return the fully formed path segment; never null
     */
    String buildPath(String model, String version, String apiVersion);

    /**
     * Returns the Walmart LLM Gateway path strategy for OpenAI-compatible models.
     *
     * <p>Produces: {@code /openai/deployments/{model}@{version}/chat/completions}
     * (with {@code ?api-version={apiVersion}} appended when {@code apiVersion} is non-blank).
     * The {@code @version} suffix is omitted when {@code version} is blank or already present
     * in the model name.
     *
     * <p>Use this strategy for any model routed through the Walmart LLM Gateway with the
     * OpenAI-compatible backend (GPT, Llama, Gemma deployments).
     *
     * @return a stateless, reusable {@code CompletionsPathStrategy} instance
     */
    static CompletionsPathStrategy walmarGatewayOpenAi() {
        return (model, version, apiVersion) -> {
            String modelWithVersion = buildModelWithVersion(model, version);
            StringBuilder path = new StringBuilder("/openai/deployments/")
                    .append(modelWithVersion)
                    .append("/chat/completions");
            if (apiVersion != null && !apiVersion.isBlank()) {
                path.append("?api-version=").append(apiVersion);
            }
            return path.toString();
        };
    }

    /**
     * Returns a strategy that produces the standard direct OpenAI API path.
     *
     * <p>Produces: {@code /v1/chat/completions}.
     * All three parameters ({@code model}, {@code version}, {@code apiVersion}) are
     * intentionally ignored — the model is conveyed in the request body, not in the URL.
     *
     * <p>Use this strategy when connecting directly to {@code api.openai.com} without a
     * Walmart gateway layer.
     *
     * @return a stateless, reusable {@code CompletionsPathStrategy} instance
     */
    static CompletionsPathStrategy openAiDirect() {
        return (model, version, apiVersion) -> "/v1/chat/completions";
    }

    /**
     * Returns the Azure OpenAI Service path strategy.
     *
     * <p>Produces: {@code /openai/deployments/{model}/chat/completions}
     * (with {@code ?api-version={apiVersion}} appended when {@code apiVersion} is non-blank).
     * Unlike {@link #walmarGatewayOpenAi()}, this strategy does <em>not</em> append the
     * {@code @version} suffix to the deployment name — Azure uses the {@code api-version}
     * query parameter instead.
     *
     * <p>Use this strategy when routing directly through Azure OpenAI Service endpoints
     * rather than the Walmart LLM Gateway.
     *
     * @return a stateless, reusable {@code CompletionsPathStrategy} instance
     */
    static CompletionsPathStrategy azureOpenAi() {
        return (model, version, apiVersion) -> {
            String path = "/openai/deployments/" + (model != null ? model.trim() : "") + "/chat/completions";
            if (apiVersion != null && !apiVersion.isBlank()) {
                path = path + "?api-version=" + apiVersion;
            }
            return path;
        };
    }

    /**
     * Returns the Anthropic Messages API path strategy.
     *
     * <p>Produces: {@code /v1/messages}.
     * All parameters are ignored — Anthropic's API uses request-body fields, not URL
     * segments, to identify the model. The {@code anthropic-version} protocol header is
     * added separately by {@link LlmProvider#buildHeaders(String, String)}.
     *
     * @return a stateless, reusable {@code CompletionsPathStrategy} instance
     */
    static CompletionsPathStrategy anthropic() {
        return (model, version, apiVersion) -> "/v1/messages";
    }

    /**
     * Returns the Google AI (Gemini) generate-content path strategy.
     *
     * <p>Produces: {@code /v1beta/models/{model}@{version}:generateContent}.
     * The {@code @version} suffix is omitted when {@code version} is blank or already
     * embedded in the model name.
     *
     * <p>Use this strategy when targeting the Google AI Studio or Vertex AI endpoint that
     * the Walmart LLM Gateway proxies for Gemini models.
     *
     * @return a stateless, reusable {@code CompletionsPathStrategy} instance
     */
    static CompletionsPathStrategy googleAi() {
        return (model, version, apiVersion) -> "/v1beta/models/" + buildModelWithVersion(model, version) + ":generateContent";
    }

    private static String buildModelWithVersion(final String model, final String version) {
        if (model == null) {
            return "";
        }
        String trimmed = model.trim();
        if (trimmed.contains("@") || version == null || version.isBlank()) {
            return trimmed;
        }
        return trimmed + "@" + version.trim();
    }
}
