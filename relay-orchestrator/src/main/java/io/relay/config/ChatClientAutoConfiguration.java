/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import io.relay.llm.ChatClientRegistry;
import io.relay.llm.LlmModelConfig;
import io.relay.llm.LlmProvider;

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
 * <li>Auto-discovers all {@link Advisor} beans and applies them to
 * ChatClient</li>
 * <li>Auto-discovers {@link ToolCallbackProvider} and registers tools</li>
 * <li>Auto-discovers {@link AgentSystemPromptProvider} for system prompts</li>
 * <li>Supports multiple providers with runtime switching</li>
 * <li>Handles custom LLM gateway SSL and audit headers</li>
 * </ul>
 *
 * <h3>Customization</h3>
 * Define your own {@code ChatClientRegistry} bean to override
 * auto-configuration.
 */
@Configuration
@EnableConfigurationProperties(AgentLlmProperties.class)
@RequiredArgsConstructor
@Slf4j
public class ChatClientAutoConfiguration {

    private final AgentLlmProperties properties;
    private final ApplicationContext applicationContext;

    /**
     * Optional gateway headers contributor — pluggable SPI for injecting
     * provider-specific
     * audit/routing headers. When no bean is defined, no extra headers are added.
     */
    private List<LlmGatewayHeadersContributor> gatewayHeadersContributors = List.of();

    @Autowired(required = false)
    public void setGatewayHeadersContributors(final List<LlmGatewayHeadersContributor> gatewayHeadersContributors) {
        this.gatewayHeadersContributors = gatewayHeadersContributors != null ? gatewayHeadersContributors : List.of();
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
        ChatClient reasoningClient;
        LlmProvider reasoningProvider;
        if (properties.getReasoningModels() != null && !properties.getReasoningModels().isEmpty()) {
            log.info("Configuring resilient Reasoning client with {} failover delegates.",
                    properties.getReasoningModels().size());
            reasoningClient = buildFailoverChatClient(baseUrl, apiKey, properties.getReasoningModels(), tools, advisors,
                    systemPrompt);
            reasoningProvider = LlmProvider.fromString(properties.getReasoningModels().get(0).getProvider());
        } else {
            if (properties.getReasoningModel() == null) {
                throw new IllegalStateException(
                        "relay.llm.reasoning-model or relay.llm.reasoning-models must be configured");
            }
            LlmModelConfig reasoningConfig = toLlmModelConfig(properties.getReasoningModel());
            logModelConfig("reasoning-model", reasoningConfig);

            String reasoningBaseUrl = properties.getReasoningModel().getGatewayBaseUrl() != null
                    ? normalizeBaseUrl(properties.getReasoningModel().getGatewayBaseUrl())
                    : baseUrl;
            String reasoningApiKey = properties.getReasoningModel().getApiKey() != null
                    ? properties.getReasoningModel().getApiKey()
                    : apiKey;

            reasoningClient = buildChatClient(
                    reasoningBaseUrl,
                    reasoningApiKey,
                    reasoningConfig,
                    properties.getReasoningModel().getHeaders(),
                    tools,
                    advisors,
                    systemPrompt);
            reasoningProvider = reasoningConfig.provider();
        }

        // Build utility client (optional)
        ChatClient utilityClient = null;
        if (properties.getUtilityModels() != null && !properties.getUtilityModels().isEmpty()) {
            log.info("Configuring resilient Utility client with {} failover delegates.",
                    properties.getUtilityModels().size());
            utilityClient = buildFailoverChatClient(baseUrl, apiKey, properties.getUtilityModels(), tools, advisors,
                    systemPrompt);
        } else if (properties.getUtilityModel() != null) {
            LlmModelConfig utilityConfig = toLlmModelConfig(properties.getUtilityModel());
            logModelConfig("utility-model", utilityConfig);

            String utilityBaseUrl = properties.getUtilityModel().getGatewayBaseUrl() != null
                    ? normalizeBaseUrl(properties.getUtilityModel().getGatewayBaseUrl())
                    : baseUrl;
            String utilityApiKey = properties.getUtilityModel().getApiKey() != null
                    ? properties.getUtilityModel().getApiKey()
                    : apiKey;

            utilityClient = buildChatClient(
                    utilityBaseUrl,
                    utilityApiKey,
                    utilityConfig,
                    properties.getUtilityModel().getHeaders(),
                    tools,
                    advisors,
                    systemPrompt);
        }

        // Build provider-specific clients for runtime switching
        Map<LlmProvider, ChatClient> providerClients = new EnumMap<>(LlmProvider.class);
        providerClients.put(reasoningProvider, reasoningClient);

        if (properties.getProviders() != null) {
            for (AgentLlmProperties.ModelConfig providerConfig : properties.getProviders()) {
                LlmProvider provider = LlmProvider.fromString(providerConfig.getProvider());
                if (!providerClients.containsKey(provider)) {
                    LlmModelConfig config = toLlmModelConfig(providerConfig);
                    logModelConfig("provider[" + provider + "]", config);

                    String pBaseUrl = providerConfig.getGatewayBaseUrl() != null
                            ? normalizeBaseUrl(providerConfig.getGatewayBaseUrl())
                            : baseUrl;
                    String pApiKey = providerConfig.getApiKey() != null
                            ? providerConfig.getApiKey()
                            : apiKey;

                    providerClients.put(
                            provider,
                            buildChatClient(
                                    pBaseUrl,
                                    pApiKey,
                                    config,
                                    providerConfig.getHeaders(),
                                    tools,
                                    advisors,
                                    systemPrompt));
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

    @SuppressWarnings("removal")
    private ChatClient buildFailoverChatClient(
            final String baseUrl,
            final String apiKey,
            final List<AgentLlmProperties.ModelConfig> modelConfigs,
            final ToolCallbackProvider tools,
            final List<Advisor> advisors,
            final String systemPrompt) {

        List<ChatModel> delegates = modelConfigs.stream()
                .map(config -> {
                    LlmModelConfig resolvedConfig = toLlmModelConfig(config);
                    String mBaseUrl = config.getGatewayBaseUrl() != null
                            ? normalizeBaseUrl(config.getGatewayBaseUrl())
                            : baseUrl;
                    String mApiKey = config.getApiKey() != null
                            ? config.getApiKey()
                            : apiKey;
                    return resolveChatModel(mBaseUrl, mApiKey, resolvedConfig, config.getHeaders());
                })
                .filter(Objects::nonNull)
                .toList();

        if (delegates.isEmpty()) {
            throw new IllegalStateException("Failed to build any delegate ChatModels for failover list");
        }

        ChatModel chatModel = new io.relay.llm.FailoverChatModel(delegates);

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.defaultSystem(systemPrompt);
        }

        if (tools != null) {
            builder.defaultToolCallbacks(tools.getToolCallbacks());
        }

        if (advisors != null && !advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(new Advisor[0]));
        }

        return builder.build();
    }

    @SuppressWarnings("removal")
    private ChatClient buildChatClient(
            final String baseUrl,
            final String apiKey,
            final LlmModelConfig modelConfig,
            final Map<String, String> modelHeaders,
            final ToolCallbackProvider tools,
            final List<Advisor> advisors,
            final String systemPrompt) {

        ChatModel chatModel = resolveChatModel(baseUrl, apiKey, modelConfig, modelHeaders);

        ChatClient.Builder builder = ChatClient
                .builder(Objects.requireNonNull(chatModel, "Chat model must not be null"));

        if (chatModel instanceof OpenAiChatModel) {
            builder.defaultOptions(
                    Objects.requireNonNull(buildModelOptions(modelConfig), "Chat options must not be null").mutate());
        }

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.defaultSystem(systemPrompt);
        }

        if (tools != null) {
            builder.defaultToolCallbacks(tools.getToolCallbacks());
        }

        if (advisors != null && !advisors.isEmpty()) {
            builder.defaultAdvisors(
                    Objects.requireNonNull(advisors.toArray(new Advisor[0]), "Advisors must not be null"));
        }

        return builder.build();
    }

    private ChatModel resolveChatModel(
            final String baseUrl,
            final String apiKey,
            final LlmModelConfig modelConfig,
            final Map<String, String> modelHeaders) {

        LlmProvider provider = modelConfig.provider();
        String providerName = provider.name().toLowerCase(Locale.ROOT);

        // Check if there are explicit overrides on credentials, endpoints, or headers
        String globalBaseUrl = normalizeBaseUrl(properties.getGatewayBaseUrl());
        String globalApiKey = properties.getApiKey() != null ? properties.getApiKey() : "";
        boolean hasOverrides = !Objects.equals(baseUrl, globalBaseUrl)
                || !Objects.equals(apiKey, globalApiKey)
                || (modelHeaders != null && !modelHeaders.isEmpty());

        // Try to find a matching ChatModel bean in the application context
        Map<String, ChatModel> chatModelBeans = applicationContext.getBeansOfType(ChatModel.class);
        if (!chatModelBeans.isEmpty() && !hasOverrides) {
            // 1. If there's only one ChatModel bean in the context and it matches the
            // provider, use it directly
            if (chatModelBeans.size() == 1) {
                ChatModel singleModel = chatModelBeans.values().iterator().next();
                String className = singleModel.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                if (className.contains(providerName)) {
                    log.info("Using the single registered ChatModel bean of type: {}",
                            singleModel.getClass().getName());
                    return singleModel;
                }
            }

            // 2. Try to match by bean name or class name containing the provider name
            for (Map.Entry<String, ChatModel> entry : chatModelBeans.entrySet()) {
                String beanName = entry.getKey().toLowerCase(Locale.ROOT);
                ChatModel modelBean = entry.getValue();
                String className = modelBean.getClass().getSimpleName().toLowerCase(Locale.ROOT);

                if (beanName.contains(providerName) || className.contains(providerName)) {
                    log.info("Using matched ChatModel bean '{}' [{}] for provider {}",
                            entry.getKey(), modelBean.getClass().getName(), provider);
                    return modelBean;
                }
            }

            // 3. Fallback: if there's a bean named "chatModel"
            if (chatModelBeans.containsKey("chatModel")) {
                log.info("Using default 'chatModel' bean");
                return chatModelBeans.get("chatModel");
            }
        }

        // Fallback to OpenAI compatible gateway client
        log.info(
                "No native ChatModel bean found for provider {} (or overrides present). Falling back to gateway adapter.",
                provider);
        return buildChatModel(baseUrl, apiKey, modelConfig, modelHeaders);
    }

    private OpenAiChatModel buildChatModel(
            final String baseUrl,
            final String apiKey,
            final LlmModelConfig modelConfig,
            final Map<String, String> modelHeaders) {

        LlmProvider provider = modelConfig.provider();
        MultiValueMap<String, String> headers = provider.buildHeaders(apiKey, modelConfig.apiVersion());
        addCustomHeaders(headers, properties.getCustomHeaders());
        addCustomHeaders(headers, modelHeaders);

        // Add gateway-specific audit/routing headers via pluggable SPI.
        // Provide a LlmGatewayHeadersContributor bean to inject gateway-specific
        // headers.
        if (!gatewayHeadersContributors.isEmpty()) {
            AgentLlmProperties.AuditConfig audit = properties.getAudit();
            for (LlmGatewayHeadersContributor contributor : gatewayHeadersContributors) {
                contributor.contribute(headers, audit);
            }
        }

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl);

        Map<String, Iterable<String>> clientHeaders = new HashMap<>();
        headers.forEach((key, list) -> clientHeaders.put(key, new ArrayList<>(list)));
        clientBuilder.headers(clientHeaders);

        OpenAIClient openAiClient = clientBuilder.build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(buildModelOptions(modelConfig))
                .build();
    }

    private void addCustomHeaders(final MultiValueMap<String, String> target, final Map<String, String> customHeaders) {
        if (customHeaders == null || customHeaders.isEmpty()) {
            return;
        }
        customHeaders.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                target.set(key, value);
            }
        });
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
