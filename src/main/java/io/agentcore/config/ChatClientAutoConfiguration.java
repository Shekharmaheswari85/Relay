/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.config;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import io.agentcore.llm.ChatClientRegistry;
import io.agentcore.llm.LlmModelConfig;
import io.agentcore.llm.LlmProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for ChatClient and ChatClientRegistry.
 * <p>
 * Automatically configures LLM clients based on {@link AgentLlmProperties}.
 * Teams just need to add configuration to application.yml.
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Auto-discovers all {@link Advisor} beans and applies them to ChatClient</li>
 *   <li>Auto-discovers {@link ToolCallbackProvider} and registers tools</li>
 *   <li>Auto-discovers {@link AgentSystemPromptProvider} for system prompts</li>
 *   <li>Supports multiple providers with runtime switching</li>
 *   <li>Handles custom LLM gateway SSL and audit headers</li>
 * </ul>
 *
 * <h3>Customization</h3>
 * Define your own {@code ChatClientRegistry} bean to override auto-configuration.
 */
@Configuration
@EnableConfigurationProperties(AgentLlmProperties.class)
@ConditionalOnProperty(prefix = "agent.llm", name = "gateway-base-url")
@RequiredArgsConstructor
@Slf4j
public class ChatClientAutoConfiguration {

    private final AgentLlmProperties properties;

    /**
     * Optional gateway headers contributor — pluggable SPI for injecting provider-specific
     * audit/routing headers. When no bean is defined, no extra headers are added.
     */
    private LlmGatewayHeadersContributor gatewayHeadersContributor;

    @Autowired(required = false)
    public void setGatewayHeadersContributor(final LlmGatewayHeadersContributor gatewayHeadersContributor) {
        this.gatewayHeadersContributor = gatewayHeadersContributor;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatClientRegistry chatClientRegistry(
            final ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            final ObjectProvider<List<Advisor>> advisorsProvider,
            final ObjectProvider<AgentSystemPromptProvider> systemPromptProvider) {

        String baseUrl = normalizeBaseUrl(properties.getGatewayBaseUrl());
        String apiKey = properties.getApiKey() != null ? properties.getApiKey() : "";
        LlmProvider defaultProvider = LlmProvider.fromString(properties.getDefaultProvider());

        ToolCallbackProvider tools = toolCallbackProvider.getIfAvailable();
        List<Advisor> advisors = advisorsProvider.getIfAvailable();
        String systemPrompt = resolveSystemPrompt(systemPromptProvider);

        // Build reasoning client (required)
        if (properties.getReasoningModel() == null) {
            throw new IllegalStateException("agent.llm.reasoning-model must be configured");
        }
        LlmModelConfig reasoningConfig = toLlmModelConfig(properties.getReasoningModel());
        logModelConfig("reasoning-model", reasoningConfig);
        ChatClient reasoningClient = buildChatClient(baseUrl, apiKey, reasoningConfig, tools, advisors, systemPrompt);

        // Build utility client (optional)
        ChatClient utilityClient = null;
        if (properties.getUtilityModel() != null) {
            LlmModelConfig utilityConfig = toLlmModelConfig(properties.getUtilityModel());
            logModelConfig("utility-model", utilityConfig);
            utilityClient = buildChatClient(baseUrl, apiKey, utilityConfig, tools, advisors, systemPrompt);
        }

        // Build provider-specific clients for runtime switching
        Map<LlmProvider, ChatClient> providerClients = new EnumMap<>(LlmProvider.class);
        providerClients.put(reasoningConfig.provider(), reasoningClient);

        if (properties.getProviders() != null) {
            for (AgentLlmProperties.ModelConfig providerConfig : properties.getProviders()) {
                LlmProvider provider = LlmProvider.fromString(providerConfig.getProvider());
                if (!providerClients.containsKey(provider)) {
                    LlmModelConfig config = toLlmModelConfig(providerConfig);
                    logModelConfig("provider[" + provider + "]", config);
                    providerClients.put(provider, buildChatClient(baseUrl, apiKey, config, tools, advisors, systemPrompt));
                }
            }
        }

        log.info("ChatClientRegistry auto-configured: providers={}, default={}, tools={}, advisors={}",
                providerClients.keySet(),
                defaultProvider,
                tools != null ? tools.getToolCallbacks().length : 0,
                advisors != null ? advisors.size() : 0);

        return new ChatClientRegistry(providerClients, reasoningClient, utilityClient, defaultProvider);
    }

    private ChatClient buildChatClient(
            final String baseUrl,
            final String apiKey,
            final LlmModelConfig modelConfig,
            final ToolCallbackProvider tools,
            final List<Advisor> advisors,
            final String systemPrompt) {

        OpenAiChatModel chatModel = buildChatModel(baseUrl, apiKey, modelConfig);

        ChatClient.Builder builder = ChatClient.builder(Objects.requireNonNull(chatModel, "Chat model must not be null"))
                .defaultOptions(Objects.requireNonNull(buildModelOptions(modelConfig), "Chat options must not be null"));

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.defaultSystem(systemPrompt);
        }

        if (tools != null) {
            builder.defaultToolCallbacks(tools);
        }

        if (advisors != null && !advisors.isEmpty()) {
            builder.defaultAdvisors(Objects.requireNonNull(advisors.toArray(new Advisor[0]), "Advisors must not be null"));
        }

        return builder.build();
    }

    private OpenAiChatModel buildChatModel(
            final String baseUrl,
            final String apiKey,
            final LlmModelConfig modelConfig) {

        LlmProvider provider = modelConfig.provider();
        MultiValueMap<String, String> headers = provider.buildHeaders(apiKey, modelConfig.apiVersion());

        // Add gateway-specific audit/routing headers via pluggable SPI.
        // Provide a LlmGatewayHeadersContributor bean to inject gateway-specific headers.
        if (gatewayHeadersContributor != null) {
            AgentLlmProperties.AuditConfig audit = properties.getAudit();
            gatewayHeadersContributor.contribute(headers, audit);
        }

        String completionsPath = modelConfig.resolveCompletionsPath();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .headers(headers)
                .completionsPath(completionsPath)
                .restClientBuilder(buildRestClientBuilder())
                .webClientBuilder(buildWebClientBuilder(modelConfig.apiVersion()))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(buildModelOptions(modelConfig))
                .build();
    }

    private OpenAiChatOptions buildModelOptions(final LlmModelConfig modelConfig) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(modelConfig.model())
                .temperature(properties.getTemperature());

        // GPT-5 family uses maxCompletionTokens instead of maxTokens
        if (modelConfig.provider() == LlmProvider.OPENAI && isGpt5Family(modelConfig.model())) {
            builder.maxCompletionTokens(properties.getMaxTokens());
        } else {
            builder.maxTokens(properties.getMaxTokens());
        }

        return builder.build();
    }

    private boolean isGpt5Family(final String model) {
        return model != null && model.toLowerCase(Locale.ROOT).startsWith("gpt-5");
    }

    @SuppressWarnings("PMD.CloseResource")
    private WebClient.Builder buildWebClientBuilder(final String apiVersion) {
        try {
            AgentLlmProperties.SslConfig ssl = properties.getSsl();
            validateSslConfig(ssl);

            SSLContext sslContext;
            if (ssl.isTrustAll()) {
                log.warn("LLM gateway SSL trust-all mode enabled. Use only for local development.");
                sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(null, (chain, authType) -> true)
                        .build();
            } else if (ssl.getCaPath() != null && !ssl.getCaPath().isBlank()) {
                log.info("LLM gateway SSL using custom CA bundle: {}", ssl.getCaPath());
                sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(new File(ssl.getCaPath()), null)
                        .build();
            } else {
                sslContext = SSLContext.getDefault();
            }

            var tlsStrategyBuilder = ClientTlsStrategyBuilder.create().setSslContext(sslContext);
            if (ssl.isTrustAll()) {
                tlsStrategyBuilder.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            }

            var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                    .setTlsStrategy(tlsStrategyBuilder.buildAsync())
                    .build();
            CloseableHttpAsyncClient asyncClient = HttpAsyncClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
            asyncClient.start();

            WebClient.Builder builder = WebClient.builder()
                    .clientConnector(new HttpComponentsClientHttpConnector(
                            Objects.requireNonNull(asyncClient, "Async HTTP client must not be null")));

            if (apiVersion != null && !apiVersion.isBlank()) {
                builder.filter(Objects.requireNonNull(apiVersionFilter(apiVersion), "API version filter must not be null"));
            }

            return builder;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to configure LLM gateway SSL", ex);
        }
    }

    private RestClient.Builder buildRestClientBuilder() {
        try {
            AgentLlmProperties.SslConfig ssl = properties.getSsl();
            validateSslConfig(ssl);

            SSLContext sslContext = ssl.isTrustAll()
                    ? SSLContextBuilder.create()
                            .loadTrustMaterial(null, (chain, authType) -> true)
                            .build()
                    : SSLContext.getDefault();

            var tlsStrategy = ClientTlsStrategyBuilder.create().setSslContext(sslContext);
            if (ssl.isTrustAll()) {
                tlsStrategy.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            }

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setTlsSocketStrategy(tlsStrategy.buildClassic())
                            .build())
                    .build();

            return RestClient.builder()
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(Objects.requireNonNull(httpClient, "HTTP client must not be null")));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to configure LLM gateway RestClient SSL", ex);
        }
    }

    private void validateSslConfig(final AgentLlmProperties.SslConfig ssl) {
        if (ssl.isTrustAll() && !ssl.isAllowInsecureTrustAll()) {
            throw new IllegalStateException(
                    "agent.llm.ssl.trust-all=true disables TLS certificate and hostname verification. "
                            + "For local development only, also set "
                            + "agent.llm.ssl.allow-insecure-trust-all=true to acknowledge this risk.");
        }
    }

    private ExchangeFilterFunction apiVersionFilter(final String apiVersion) {
        return (request, next) -> {
            String path = request.url().getPath();
            boolean isAzureDeployment = path != null && path.contains("/openai/deployments/");
            boolean hasApiVersion = request.url().getQuery() != null
                    && request.url().getQuery().contains("api-version=");

            if (!isAzureDeployment || hasApiVersion) {
                return next.exchange(request);
            }

            var updatedUri = UriComponentsBuilder.fromUri(request.url())
                    .queryParam("api-version", apiVersion)
                    .build(true)
                    .toUri();
            var mutatedRequest = ClientRequest.from(request)
                    .url(updatedUri)
                    .build();
            return next.exchange(mutatedRequest);
        };
    }

    private String resolveSystemPrompt(final ObjectProvider<AgentSystemPromptProvider> provider) {
        AgentSystemPromptProvider promptProvider = provider.getIfAvailable();
        if (promptProvider != null) {
            return promptProvider.getSystemPrompt();
        }
        return null;
    }

    private String normalizeBaseUrl(final String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com";
        }
        String normalized = url.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void logModelConfig(final String source, final LlmModelConfig config) {
        log.info("LLM model configured: source={}, provider={}, model={}, version={}",
                source, config.provider(), config.model(), config.version());
    }

    private LlmModelConfig toLlmModelConfig(final AgentLlmProperties.ModelConfig config) {
        return LlmModelConfig.builder()
                .provider(config.getProvider())
                .model(config.getModel())
                .version(config.getVersion())
                .apiVersion(config.getApiVersion())
                .build();
    }
}
