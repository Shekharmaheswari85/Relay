/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.llm;

import java.util.Locale;
import java.util.Objects;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import lombok.Getter;

/**
 * Enumerates the LLM providers supported by the agent framework.
 *
 * <p>Each constant encapsulates two provider-specific concerns:
 * <ol>
 *   <li><strong>Path construction</strong> — the {@link #buildCompletionsPath} method
 *       produces the HTTP path that is appended to the gateway base URL when making
 *       chat-completion requests.</li>
 *   <li><strong>Header construction</strong> — the {@link #buildHeaders} methods produce
 *       the HTTP headers that carry the API key (and, for Anthropic, the protocol-version
 *       header) required by the provider.</li>
 * </ol>
 *
 * <h3>Gateway URL contracts</h3>
 * <table>
 *   <tr><th>Provider</th><th>Path pattern</th><th>Auth header</th></tr>
 *   <tr>
 *     <td>{@link #OPENAI}, {@link #LLAMA}, {@link #GEMMA}</td>
 *     <td>{@code /openai/deployments/{model}@{version}/chat/completions?api-version={apiVersion}}</td>
 *     <td>{@code api-key}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #GOOGLE}</td>
 *     <td>{@code /v1beta/models/{model}@{version}:generateContent}</td>
 *     <td>{@code x-goog-api-key}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #ANTHROPIC}</td>
 *     <td>{@code /v1/messages}</td>
 *     <td>{@code x-api-key} + {@code anthropic-version}</td>
 *   </tr>
 * </table>
 *
 * <h3>Usage</h3>
 * <p>Resolve a provider from a configuration string (case-insensitive, defaults to
 * {@link #OPENAI} for null or unrecognised values):
 * <pre>{@code
 * LlmProvider provider = LlmProvider.fromString(properties.getDefaultProvider());
 * String path    = provider.buildCompletionsPath(model, version, apiVersion);
 * MultiValueMap<String, String> headers = provider.buildHeaders(apiKey);
 * }</pre>
 *
 * <p>Providers that need version-specific headers (currently only {@link #ANTHROPIC})
 * accept an extra {@code apiVersion} argument through the overloaded
 * {@link #buildHeaders(String, String)} method.
 *
 * @see LlmModelConfig
 * @see CompletionsPathStrategy
 */
@Getter
public enum LlmProvider {
    /**
     * OpenAI GPT models accessed through an OpenAI-compatible gateway.
     * Produces OpenAI-style paths: {@code /openai/deployments/{model}@{version}/chat/completions}.
     * Auth header: {@code api-key}.
     */
    OPENAI(Headers.API_KEY) {
        @Override
        public String buildCompletionsPath(final String model, final String version, final String apiVersion) {
            return buildOpenAiStylePath(model, version, apiVersion);
        }
    },

    /**
     * Meta Llama open-source models accessed through an OpenAI-compatible gateway.
     * Uses the same OpenAI-compatible path structure as {@link #OPENAI}.
     * Auth header: {@code api-key}.
     */
    LLAMA(Headers.API_KEY) {
        @Override
        public String buildCompletionsPath(final String model, final String version, final String apiVersion) {
            return buildOpenAiStylePath(model, version, apiVersion);
        }
    },

    /**
     * Google Gemma open-source models accessed through an OpenAI-compatible gateway.
     * Uses the same OpenAI-compatible path structure as {@link #OPENAI}.
     * Auth header: {@code api-key}.
     */
    GEMMA(Headers.API_KEY) {
        @Override
        public String buildCompletionsPath(final String model, final String version, final String apiVersion) {
            return buildOpenAiStylePath(model, version, apiVersion);
        }
    },

    /**
     * Google Gemini models via the Vertex AI / Google AI Studio gateway path.
     * Produces: {@code /v1beta/models/{model}@{version}:generateContent}.
     * Auth header: {@code x-goog-api-key}.
     */
    GOOGLE("x-goog-api-key") {
        @Override
        public String buildCompletionsPath(final String model, final String version, final String apiVersion) {
            return "/v1beta/models/" + withOptionalVersion(model, version) + ":generateContent";
        }
    },

    /**
     * Anthropic Claude models via the Anthropic Messages API.
     * Produces a fixed path: {@code /v1/messages}.
     * Auth headers: {@code x-api-key} plus {@code anthropic-version} (defaults to
     * {@code vertex-2023-10-16} when no {@code apiVersion} is supplied).
     */
    ANTHROPIC("x-api-key") {
        private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
        private static final String DEFAULT_ANTHROPIC_VERSION = "vertex-2023-10-16";

        @Override
        public String buildCompletionsPath(final String model, final String version, final String apiVersion) {
            return "/v1/messages";
        }

        @Override
        public MultiValueMap<String, String> buildHeaders(final String apiKey) {
            return buildHeaders(apiKey, null);
        }

        @Override
        public MultiValueMap<String, String> buildHeaders(final String apiKey, final String apiVersion) {
            MultiValueMap<String, String> headers = super.buildHeaders(apiKey);
            String version = (apiVersion != null && !apiVersion.isBlank()) ? apiVersion : DEFAULT_ANTHROPIC_VERSION;
            headers.add(ANTHROPIC_VERSION_HEADER, version);
            return headers;
        }
    };

    private final String apiKeyHeaderName;

    /** Holds header-name constants needed during enum-constant initialization. */
    private static final class Headers {
        private static final String API_KEY = "api-key";

        private Headers() {}
    }

    LlmProvider(final String apiKeyHeaderName) {
        this.apiKeyHeaderName = apiKeyHeaderName;
    }

    /**
     * Builds the completions endpoint path to append to the LLM gateway base URL.
     *
     * <p>The exact format differs per provider — see each constant's Javadoc for the
     * specific pattern produced.
     *
     * @param model      the model name or deployment ID (e.g., {@code gpt-4o}); may be null
     *                   but callers should supply a non-null value
     * @param version    optional model snapshot version (e.g., {@code 2025-04-14}); may be blank
     * @param apiVersion optional API version query param for OpenAI-style endpoints
     *                   (e.g., {@code 2024-02-01}); may be blank
     * @return the fully formed path segment; never null
     */
    public abstract String buildCompletionsPath(String model, String version, String apiVersion);

    /**
     * Builds the HTTP headers required for authenticating with this provider.
     *
     * <p>Returns a mutable {@link MultiValueMap} containing the provider-specific API-key
     * header. If {@code apiKey} is null or blank, the map is returned empty (no auth header).
     * Callers are free to add further headers to the returned map.
     *
     * @param apiKey the API key credential; null or blank values are silently ignored
     * @return a mutable multi-value map pre-populated with the auth header
     */
    public MultiValueMap<String, String> buildHeaders(final String apiKey) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.add(Objects.requireNonNull(apiKeyHeaderName, "API key header name must not be null"), apiKey);
        }
        return headers;
    }

    /**
     * Builds the HTTP headers required for authenticating with this provider, optionally
     * including a protocol-version header.
     *
     * <p>For most providers this delegates directly to {@link #buildHeaders(String)}.
     * {@link #ANTHROPIC} overrides this method to also emit the {@code anthropic-version}
     * header using the supplied {@code apiVersion} (or a built-in default when blank).
     *
     * @param apiKey     the API key credential; null or blank values are silently ignored
     * @param apiVersion optional API/protocol version (used only by {@link #ANTHROPIC});
     *                   may be null
     * @return a mutable multi-value map pre-populated with the appropriate auth headers
     */
    @SuppressWarnings("java:S1172")
    public MultiValueMap<String, String> buildHeaders(final String apiKey, final String apiVersion) {
        return buildHeaders(apiKey);
    }

    /**
     * Resolves a provider by name, case-insensitively.
     *
     * <p>Returns {@link #OPENAI} when {@code value} is null, blank, or does not match any
     * declared constant name — making OPENAI the safe default for misconfigured environments.
     *
     * @param value the provider name string (e.g., {@code "anthropic"}, {@code "GOOGLE"})
     * @return the matching {@code LlmProvider}, or {@link #OPENAI} if unresolvable
     */
    public static LlmProvider fromString(final String value) {
        if (value == null || value.isBlank()) {
            return OPENAI;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OPENAI;
        }
    }

    private static String buildOpenAiStylePath(final String model, final String version, final String apiVersion) {
        StringBuilder path = new StringBuilder("/openai/deployments/");
        path.append(withOptionalVersion(model, version));
        path.append("/chat/completions");
        if (apiVersion != null && !apiVersion.isBlank()) {
            path.append("?api-version=").append(apiVersion);
        }
        return path.toString();
    }

    private static String withOptionalVersion(final String model, final String version) {
        if (model == null) {
            return "";
        }
        String trimmedModel = model.trim();
        if (trimmedModel.contains("@")) {
            return trimmedModel;
        }
        if (version == null || version.isBlank()) {
            return trimmedModel;
        }
        return trimmedModel + "@" + version.trim();
    }
}
