/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.summary;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.relay.llm.ChatClientRegistry;
import io.relay.model.BaseAgentSession;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base implementation of {@link SessionSummarizer} that drives summarization
 * by invoking a cost-effective LLM via {@link io.relay.llm.ChatClientRegistry}.
 *
 * <p>The class handles all framework-level concerns — threshold comparison, LLM invocation,
 * payload truncation, output truncation, and context-key updates — so that subclasses only
 * need to supply three domain-specific pieces:
 * <ol>
 *   <li>Which context fields to include in the summarization payload
 *       ({@link #buildSourcePayload}).</li>
 *   <li>The agent identifier used to look up the summary system prompt
 *       ({@link #getAgentId}).</li>
 *   <li>How to persist the generated summary back to the session
 *       ({@link #persistSummary}).</li>
 * </ol>
 *
 * <h3>Prompt resolution order</h3>
 * <ol>
 *   <li>{@link SummaryPromptProvider#getSummaryPrompt(String)} keyed by {@link #getAgentId()}.</li>
 *   <li>{@link SummaryPromptProvider#getDefaultSummaryPrompt()} when the agent-specific
 *       lookup returns blank.</li>
 *   <li>{@link #getFallbackSystemPrompt()} — a hardcoded prompt provided by the subclass.</li>
 * </ol>
 * If all three sources return blank or null, an {@link IllegalStateException} is thrown at
 * summarization time. At least one source must be configured.
 *
 * <h3>Prompt configuration via YAML</h3>
 * <pre>{@code
 * relay:
 *   llm:
 *     summary-prompts:
 *       onboarding-market: prompts/onboarding-summary.txt
 *       eda-analysis: prompts/eda-summary.txt
 * }</pre>
 *
 * <h3>Extending this class</h3>
 * <pre>{@code
 * @Component
 * public class MySessionSummarizer extends BaseLlmSessionSummarizer<MySessionDO> {
 *
 *     private final MySessionRepository sessionRepository;
 *
 *     public MySessionSummarizer(ChatClientRegistry registry, ObjectMapper mapper,
 *                                SummaryPromptProvider promptProvider,
 *                                MySessionRepository sessionRepository) {
 *         super(registry, mapper, promptProvider);
 *         this.sessionRepository = sessionRepository;
 *     }
 *
 *     @Override
 *     protected String getAgentId() {
 *         return "my-agent";
 *     }
 *
 *     @Override
 *     protected String buildSourcePayload(MySessionDO session,
 *             Map<String, Object> context, String userMessage) {
 *         return toJson(Map.of(
 *             "step", session.getCurrentStep(),
 *             "summary", context.get(CONTEXT_KEY_LLM_SUMMARY),
 *             "userMessage", userMessage
 *         ));
 *     }
 *
 *     @Override
 *     protected void persistSummary(MySessionDO session, Map<String, Object> context,
 *             String generatedSummary, int sourcePromptChars) {
 *         updateSummaryContext(context, generatedSummary, sourcePromptChars);
 *         session.setContextJson(toJson(context));
 *         sessionRepository.save(session);
 *     }
 * }
 * }</pre>
 *
 * @param <S> the session entity type, must extend {@link BaseAgentSession}
 * @see SessionSummarizer
 * @see SummaryPromptProvider
 */
@Slf4j
public abstract class BaseLlmSessionSummarizer<S extends BaseAgentSession> implements SessionSummarizer<S> {

    /** Default threshold before triggering summarization. */
    protected static final int DEFAULT_PROMPT_SUMMARY_THRESHOLD_CHARS = 18_000;

    /** Maximum characters sent to the LLM for summarization. */
    protected static final int DEFAULT_MAX_INPUT_CHARS = 30_000;

    /** Maximum characters in the generated summary. */
    protected static final int DEFAULT_MAX_SUMMARY_OUTPUT_CHARS = 1_600;

    /** Context key for storing the generated summary. */
    public static final String CONTEXT_KEY_LLM_SUMMARY = "llmSessionSummary";

    /** Context key for summary update timestamp. */
    public static final String CONTEXT_KEY_LLM_SUMMARY_UPDATED_AT = "llmSessionSummaryUpdatedAt";

    /** Context key for source prompt size when summary was generated. */
    public static final String CONTEXT_KEY_LLM_SUMMARY_SOURCE_CHARS = "llmSessionSummarySourcePromptChars";

    protected final ChatClientRegistry chatClientRegistry;
    protected final ObjectMapper objectMapper;
    @Nullable
    protected final SummaryPromptProvider summaryPromptProvider;

    /**
     * Constructs a summarizer with an optional {@link SummaryPromptProvider} for
     * YAML-configured prompts.
     *
     * @param chatClientRegistry   the registry used to obtain the summarization LLM client;
     *                             never null
     * @param objectMapper         the Jackson mapper used by {@link #toJson}; never null
     * @param summaryPromptProvider an optional provider for agent-specific summary prompts;
     *                              may be null, in which case only the fallback prompt is used
     */
    protected BaseLlmSessionSummarizer(
            final ChatClientRegistry chatClientRegistry,
            final ObjectMapper objectMapper,
            @Nullable final SummaryPromptProvider summaryPromptProvider) {
        this.chatClientRegistry = chatClientRegistry;
        this.objectMapper = objectMapper;
        this.summaryPromptProvider = summaryPromptProvider;
    }

    /**
     * Constructs a summarizer without a {@link SummaryPromptProvider}.
     *
     * <p>The summarizer will rely exclusively on {@link #getFallbackSystemPrompt()}, which
     * the subclass must override to return a non-blank value.
     *
     * @param chatClientRegistry the registry used to obtain the summarization LLM client;
     *                           never null
     * @param objectMapper       the Jackson mapper used by {@link #toJson}; never null
     */
    protected BaseLlmSessionSummarizer(
            final ChatClientRegistry chatClientRegistry, final ObjectMapper objectMapper) {
        this(chatClientRegistry, objectMapper, null);
    }

    @Override
    public boolean summarizeIfNeeded(
            final S session, final Map<String, Object> context, final String userMessage, final int promptChars) {
        if (promptChars < getPromptSummaryThreshold()) {
            return false;
        }
        return doSummarize(session, context, userMessage, promptChars);
    }

    @Override
    public boolean summarizeNow(final S session, final Map<String, Object> context, final String userMessage) {
        String sourcePayload = buildSourcePayload(session, context, userMessage);
        return doSummarize(session, context, userMessage, sourcePayload.length());
    }

    @Override
    public int getPromptSummaryThreshold() {
        return DEFAULT_PROMPT_SUMMARY_THRESHOLD_CHARS;
    }

    /**
     * Returns the maximum number of characters of the source payload sent to the LLM
     * for summarization.
     *
     * <p>Payloads exceeding this limit are truncated with an ellipsis before the LLM call.
     * Override to raise or lower the limit for specific deployment configurations.
     * The default is {@value #DEFAULT_MAX_INPUT_CHARS}.
     *
     * @return the character limit; always positive
     */
    protected int getMaxInputChars() {
        return DEFAULT_MAX_INPUT_CHARS;
    }

    /**
     * Returns the maximum number of characters allowed in the generated summary.
     *
     * <p>Summaries exceeding this limit are truncated with an ellipsis before being
     * persisted. Override to increase the limit when richer summaries are needed.
     * The default is {@value #DEFAULT_MAX_SUMMARY_OUTPUT_CHARS}.
     *
     * @return the character limit; always positive
     */
    protected int getMaxSummaryOutputChars() {
        return DEFAULT_MAX_SUMMARY_OUTPUT_CHARS;
    }

    /**
     * Extracts and serializes the domain-specific context that the LLM should summarize.
     *
     * <p>Implementations typically assemble a JSON object containing relevant session
     * fields (current step, key decisions, accumulated data) and the latest user message.
     * The returned string is truncated to {@link #getMaxInputChars()} characters before
     * being sent to the LLM, so very large payloads are safe to return here.
     *
     * @param session     the session entity whose context is being summarized; never null
     * @param context     the parsed context map from {@code session.getContextJson()};
     *                    never null
     * @param userMessage the latest user message to include for recency context; never null
     * @return the serialized payload to send to the LLM; never null
     */
    protected abstract String buildSourcePayload(S session, Map<String, Object> context, String userMessage);

    /**
     * Returns the stable identifier of this agent, used to look up the summary system
     * prompt from {@link SummaryPromptProvider}.
     *
     * <p>The value must match the key used in the {@code relay.llm.summary-prompts} YAML
     * map (case-insensitive lookup). For example, returning {@code "onboarding-market"}
     * resolves the prompt at {@code relay.llm.summary-prompts.onboarding-market}.
     *
     * @return the agent identifier; never null or blank
     */
    protected abstract String getAgentId();

    /**
     * Resolves the system prompt for the summarization LLM call by trying the three
     * sources in order: agent-specific prompt from {@link SummaryPromptProvider},
     * default prompt from the same provider, and finally {@link #getFallbackSystemPrompt()}.
     *
     * @return the resolved system prompt; never null or blank
     * @throws IllegalStateException if all three sources return blank or null
     */
    protected @NonNull String getSystemPrompt() {
        // Try provider first
        if (summaryPromptProvider != null) {
            String prompt = summaryPromptProvider.getSummaryPrompt(getAgentId());
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        }
        // Fallback to hardcoded prompt
        String fallback = getFallbackSystemPrompt();
        if (fallback == null || fallback.isBlank()) {
            throw new IllegalStateException(
                    "No summary prompt configured for agent '" + getAgentId() + "' and no fallback provided");
        }
        return fallback;
    }

    /**
     * Returns a hardcoded fallback system prompt used when neither the
     * {@link SummaryPromptProvider} nor its default yield a configured prompt.
     *
     * <p>The default implementation returns {@code null}. Subclasses that do not register
     * a prompt via YAML must override this method and return a non-blank string, otherwise
     * summarization will throw {@link IllegalStateException}.
     *
     * @return the fallback system prompt text, or {@code null} if not provided
     */
    @Nullable
    protected String getFallbackSystemPrompt() {
        return null;
    }

    /**
     * Persists the LLM-generated summary back to the session store.
     *
     * <p>Implementations should:
     * <ol>
     *   <li>Call {@link #updateSummaryContext(Map, String, int)} to write the standard
     *       context keys ({@value #CONTEXT_KEY_LLM_SUMMARY},
     *       {@value #CONTEXT_KEY_LLM_SUMMARY_UPDATED_AT},
     *       {@value #CONTEXT_KEY_LLM_SUMMARY_SOURCE_CHARS}).</li>
     *   <li>Serialize the updated context map back to JSON and assign it to
     *       {@code session.setContextJson(...)}.</li>
     *   <li>Save the session entity via the domain repository.</li>
     * </ol>
     *
     * @param session           the session entity to update; never null
     * @param context           the parsed context map already updated in memory by
     *                          {@link #updateSummaryContext}; never null
     * @param generatedSummary  the non-blank summary text produced by the LLM; never null
     * @param sourcePromptChars the character count of the prompt that triggered this
     *                          summarization, stored for observability
     */
    protected abstract void persistSummary(
            S session, Map<String, Object> context, String generatedSummary, int sourcePromptChars);

    /**
     * Returns the {@link ChatClient} used for LLM summarization calls.
     *
     * <p>The default implementation returns the utility client from
     * {@link io.relay.llm.ChatClientRegistry#getUtilityClient()}, which is
     * typically a cost-optimised model. Override to direct summarization traffic to
     * a different model (e.g. a faster or cheaper tier).
     *
     * @return the chat client to use; never null
     */
    protected ChatClient getSummarizationClient() {
        return chatClientRegistry.getUtilityClient();
    }

    private boolean doSummarize(
            final S session, final Map<String, Object> context, final String userMessage, final int promptChars) {
        String sourcePayload = buildSourcePayload(session, context, userMessage);
        String generatedSummary = generateSummary(sourcePayload);

        if (generatedSummary == null || generatedSummary.isBlank()) {
            return false;
        }

        persistSummary(session, context, generatedSummary, promptChars);
        log.info(
                "LLM session summary refreshed for session={} sourcePromptChars={}",
                session.getSessionId(),
                promptChars);
        return true;
    }

    private String generateSummary(final String sourcePayload) {
        String truncatedPayload = truncateIfNeeded(sourcePayload, getMaxInputChars());

        try {
            String summary = getSummarizationClient()
                    .prompt()
                    .system(Objects.requireNonNull(getSystemPrompt(), "System prompt must not be null"))
                    .user("Summarize this session payload:\n" + truncatedPayload)
                    .call()
                    .content();

            if (summary == null) {
                return null;
            }

            return truncateIfNeeded(summary.trim(), getMaxSummaryOutputChars());
        } catch (Exception ex) {
            log.warn("LLM session summary generation failed: {}", ex.getMessage());
            return null;
        }
    }

    private String truncateIfNeeded(final String text, final int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    /**
     * Writes the three standard summary context keys into {@code context}.
     *
     * <p>Call this from {@link #persistSummary} before serializing the context map
     * back to JSON. The keys written are:
     * <ul>
     *   <li>{@value #CONTEXT_KEY_LLM_SUMMARY} — the generated summary text</li>
     *   <li>{@value #CONTEXT_KEY_LLM_SUMMARY_UPDATED_AT} — ISO-8601 timestamp of this update</li>
     *   <li>{@value #CONTEXT_KEY_LLM_SUMMARY_SOURCE_CHARS} — prompt character count that
     *       triggered the summarization</li>
     * </ul>
     *
     * @param context           the context map to update in place; never null
     * @param summary           the generated summary text; never null
     * @param sourcePromptChars the character count of the prompt that triggered summarization
     */
    protected void updateSummaryContext(
            final Map<String, Object> context, final String summary, final int sourcePromptChars) {
        context.put(CONTEXT_KEY_LLM_SUMMARY, summary);
        context.put(CONTEXT_KEY_LLM_SUMMARY_UPDATED_AT, LocalDateTime.now().toString());
        context.put(CONTEXT_KEY_LLM_SUMMARY_SOURCE_CHARS, sourcePromptChars);
    }

    /**
     * Serializes an arbitrary object to a JSON string using the injected
     * {@link com.fasterxml.jackson.databind.ObjectMapper}.
     *
     * <p>On serialization failure, logs a warning and returns {@code "{}"} so that
     * callers can proceed without throwing.
     *
     * @param payload the object to serialize; may be null
     * @return the JSON string representation, or {@code "{}"} on error; never null
     */
    protected String toJson(final Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize payload: {}", ex.getMessage());
            return "{}";
        }
    }
}
