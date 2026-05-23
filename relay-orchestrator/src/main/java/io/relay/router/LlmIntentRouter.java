/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.router;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link BaseIntentRouter} implementation that classifies user messages by making a single
 * synchronous {@link io.relay.llm.ModelTier#UTILITY} LLM call.
 *
 * <p>{@code LlmIntentRouter} sends the candidate intent names and the user message to the LLM
 * as a zero-shot classification prompt. The model is instructed to respond with exactly one
 * intent name and nothing else. The raw response is then matched back to the candidate list
 * using a two-pass strategy:
 * <ol>
 *   <li>Exact case-insensitive match — preferred, preserves the original casing of the matched
 *       intent.</li>
 *   <li>Substring match — handles models that append punctuation or a brief explanation
 *       alongside the intent name.</li>
 * </ol>
 * If neither pass produces a match, the router logs a {@code DEBUG} message and returns
 * {@link BaseIntentRouter#DEFAULT_INTENT}.
 *
 * <h3>Fast-path optimisations</h3>
 * <ul>
 *   <li>A blank message returns {@link BaseIntentRouter#DEFAULT_INTENT} without an LLM call.</li>
 *   <li>An empty candidate list returns {@link BaseIntentRouter#DEFAULT_INTENT} without an LLM call.</li>
 *   <li>A single-element candidate list returns that element directly without an LLM call.</li>
 * </ul>
 *
 * <h3>Failure behaviour</h3>
 * <p>Any exception during the LLM call is caught, logged at {@code WARN} level, and silently
 * converted to {@link BaseIntentRouter#DEFAULT_INTENT}. The router never throws.
 *
 * <h3>Wiring with the default prompt</h3>
 * <pre>{@code
 * @Bean
 * public BaseIntentRouter intentRouter(ChatClientRegistry registry) {
 *     return new LlmIntentRouter(registry.getClientForTier(ModelTier.UTILITY));
 * }
 * }</pre>
 *
 * <h3>Wiring with a custom classification prompt</h3>
 * <p>Supply a format string with exactly two {@code %s} placeholders: the first receives the
 * comma-separated intent list, the second receives the user message.
 * <pre>{@code
 * @Bean
 * public BaseIntentRouter intentRouter(ChatClientRegistry registry) {
 *     String domainPrompt = """
 *             You are an order-management routing assistant.
 *             Available workflows: %s
 *             Customer message: %s
 *             Reply with the workflow name only — no explanation.
 *             """;
 *     return new LlmIntentRouter(
 *             registry.getClientForTier(ModelTier.UTILITY), domainPrompt);
 * }
 * }</pre>
 *
 * @see BaseIntentRouter
 * @see io.relay.llm.ChatClientRegistry
 */
@Slf4j
public class LlmIntentRouter implements BaseIntentRouter {

    /**
     * Default zero-shot classification prompt template.
     *
     * <p>The first {@code %s} placeholder is replaced with the comma-separated list of candidate
     * intent names. The second {@code %s} placeholder is replaced with the user message. The
     * prompt instructs the model to respond with exactly one intent name and no additional text.
     */
    public static final String DEFAULT_PROMPT_TEMPLATE = """
            Classify the following user message into exactly one of these intents: %s

            User message: %s

            Respond with only the intent name, nothing else. No explanation.
            """;

    private final ChatClient utilityClient;
    private final String promptTemplate;

    /**
     * Creates a router that uses {@link #DEFAULT_PROMPT_TEMPLATE} for classification.
     *
     * @param utilityClient the {@link ChatClient} backed by a lightweight utility-tier model;
     *                      must not be {@code null}
     */
    public LlmIntentRouter(final ChatClient utilityClient) {
        this(utilityClient, DEFAULT_PROMPT_TEMPLATE);
    }

    /**
     * Creates a router with a caller-supplied classification prompt template.
     *
     * <p>If {@code promptTemplate} is {@code null} or blank, {@link #DEFAULT_PROMPT_TEMPLATE}
     * is used instead.
     *
     * @param utilityClient  the {@link ChatClient} backed by a lightweight utility-tier model;
     *                       must not be {@code null}
     * @param promptTemplate a {@link String#format} template with exactly two {@code %s}
     *                       positional placeholders — the first receives the comma-separated
     *                       intent list, the second receives the user message
     */
    public LlmIntentRouter(final ChatClient utilityClient, final String promptTemplate) {
        this.utilityClient = utilityClient;
        this.promptTemplate = promptTemplate != null && !promptTemplate.isBlank()
                ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
    }

    /**
     * Classifies {@code message} into one of the {@code intents} using a single LLM call.
     *
     * <p>The {@code context} parameter is accepted for API compatibility but is not currently
     * forwarded to the LLM prompt. Sub-classes that need context-aware classification should
     * override this method and incorporate relevant context fields into the prompt before calling
     * {@code super.route(...)}.
     *
     * <p>Fast paths (no LLM call):
     * <ul>
     *   <li>Null or empty {@code intents} → {@link #DEFAULT_INTENT}</li>
     *   <li>Single-element {@code intents} → that element</li>
     *   <li>Blank {@code message} → {@link #DEFAULT_INTENT}</li>
     * </ul>
     *
     * @param message  the raw user message; blank input triggers the fast path
     * @param intents  the ordered candidate intent names; must not be {@code null}
     * @param context  the current session context map; accepted but not currently used in the
     *                 classification prompt
     * @return the matched intent name (original casing preserved) from {@code intents}, or
     *         {@link #DEFAULT_INTENT} if classification fails or no match is found; never
     *         {@code null}
     */
    @Override
    public String route(
            final String message,
            final List<String> intents,
            final Map<String, Object> context) {

        if (intents == null || intents.isEmpty()) {
            return DEFAULT_INTENT;
        }
        if (intents.size() == 1) {
            return intents.get(0);
        }
        if (message == null || message.isBlank()) {
            return DEFAULT_INTENT;
        }

        try {
            String intentList = intents.stream().collect(Collectors.joining(", "));
            String prompt = promptTemplate.formatted(intentList, message.trim());

            String response = utilityClient.prompt()
                    .user(Objects.requireNonNull(prompt, "Prompt must not be null"))
                    .call()
                    .content();

            return resolveIntent(response, intents);

        } catch (Exception ex) {
            log.warn("LLM intent routing failed for message '{}': {}. Defaulting to '{}'",
                    abbreviate(message, 60), ex.getMessage(), DEFAULT_INTENT);
            return DEFAULT_INTENT;
        }
    }

    private String resolveIntent(final String llmResponse, final List<String> intents) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return DEFAULT_INTENT;
        }
        String trimmed = llmResponse.trim();

        for (String intent : intents) {
            if (trimmed.equalsIgnoreCase(intent)) {
                return intent;
            }
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String intent : intents) {
            if (lower.contains(intent.toLowerCase(Locale.ROOT))) {
                return intent;
            }
        }

        log.debug("LLM returned unrecognised intent '{}', using default", trimmed);
        return DEFAULT_INTENT;
    }

    private static String abbreviate(final String text, final int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
