/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.advisor;

import java.util.Objects;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;

import io.agentcore.session.SessionContextHolder;
import io.agentcore.stream.ToolProgressPublisher;

import lombok.extern.slf4j.Slf4j;

/**
 * Advisor that surfaces the agent's internal reasoning as real-time {@code thinking} SSE events.
 *
 * <h3>How Claude Code / IDE "thinking" panels work</h3>
 * <p>Claude's API returns {@code thinking} blocks — raw chain-of-thought tokens — before the
 * final response. IDEs like Cursor intercept those blocks and display them in a collapsible
 * "Thinking…" panel that updates in real time. This gives developers visibility into
 * <em>why</em> the model made a decision, not just what it said.
 *
 * <h3>How {@code ThinkingAdvisor} achieves the same effect</h3>
 * <p>This advisor hooks into the Spring AI {@link CallAdvisor} chain and emits {@code thinking}
 * SSE events via {@link ToolProgressPublisher} at two moments per LLM call:
 * <ol>
 *   <li><b>Pre-call</b> — emits a summary of the prompt being sent: message length, number of
 *       messages in context, selected model tier, and the first 120 characters of the user
 *       query. The UI can show this as "Analysing your request…"</li>
 *   <li><b>Post-call</b> — emits token usage ({@code promptTokens}, {@code completionTokens},
 *       {@code totalTokens}) and latency. The UI can show this as "Responded in 1.4 s
 *       (847 tokens)". When the underlying model does not report usage the event is skipped.</li>
 * </ol>
 *
 * <h3>Frontend integration</h3>
 * <p>Listen for {@code event: thinking} on the SSE stream. The {@code data} field is a JSON
 * object:
 * <pre>{@code
 * {
 *   "phase":    "pre_call",                         // or "post_call"
 *   "message":  "Calling reasoning model — 312-char query, 4 context turns",
 *   "detail":   "query preview: \"How do I place a bulk order...\"",
 *   "latencyMs": 1423,                              // post_call only
 *   "usage": {                                      // post_call only, when available
 *     "promptTokens":     512,
 *     "completionTokens": 218,
 *     "totalTokens":      730
 *   }
 * }
 * }</pre>
 *
 * <h3>Registration</h3>
 * <pre>{@code
 * @Bean
 * public ThinkingAdvisor thinkingAdvisor(ToolProgressPublisher publisher) {
 *     return ThinkingAdvisor.builder(publisher)
 *             .order(Ordered.HIGHEST_PRECEDENCE + 20)
 *             .build();
 * }
 * }</pre>
 *
 * <h3>Order</h3>
 * <p>Runs at {@code HIGHEST_PRECEDENCE + 20} — after the rate limiter and RAG advisor but
 * before the audit advisor — so it sees the fully augmented prompt.
 *
 * @see ToolProgressPublisher#emitThinking(String, String)
 */
@Slf4j
public class ThinkingAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    private static final int QUERY_PREVIEW_LENGTH = 120;

    private final ToolProgressPublisher progressPublisher;
    private final int order;

    /**
     * Creates a thinking advisor with default order.
     *
     * @param progressPublisher the publisher used to emit {@code thinking} SSE events; never null
     */
    public ThinkingAdvisor(final ToolProgressPublisher progressPublisher) {
        this(progressPublisher, DEFAULT_ORDER);
    }

    /**
     * Creates a thinking advisor with explicit chain order.
     *
     * @param progressPublisher the publisher used to emit {@code thinking} SSE events; never null
     * @param order             advisor chain order; lower values run first
     */
    public ThinkingAdvisor(final ToolProgressPublisher progressPublisher, final int order) {
        this.progressPublisher = Objects.requireNonNull(progressPublisher, "ToolProgressPublisher must not be null");
        this.order = order;
    }

    /** Returns a builder for fluent configuration. */
    public static Builder builder(final ToolProgressPublisher progressPublisher) {
        return new Builder(progressPublisher);
    }

    @Override
    public @NonNull String getName() {
        return "ThinkingAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest request,
            final @NonNull CallAdvisorChain chain) {

        String sessionId = SessionContextHolder.get();

        emitPreCall(sessionId, request);

        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        emitPostCall(sessionId, response, latencyMs);

        return response;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void emitPreCall(final String sessionId, final ChatClientRequest request) {
        if (sessionId == null || request.prompt() == null) {
            return;
        }
        try {
            var instructions = request.prompt().getInstructions();
            int turnCount = (int) instructions.stream()
                    .filter(UserMessage.class::isInstance)
                    .count();

            String userText = instructions.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(m -> ((UserMessage) m).getText())
                    .findFirst()
                    .orElse("");

            String preview = userText.length() > QUERY_PREVIEW_LENGTH
                    ? userText.substring(0, QUERY_PREVIEW_LENGTH) + "…"
                    : userText;

            String message = String.format(
                    "Calling LLM — %d-char query, %d message turn%s",
                    userText.length(), turnCount, turnCount == 1 ? "" : "s");

            String json = buildJson("pre_call", message, "query preview: \"" + escape(preview) + "\"",
                    null, null);
            progressPublisher.emitThinking(sessionId, json);
        } catch (Exception ex) {
            log.debug("ThinkingAdvisor pre-call emit failed: {}", ex.getMessage());
        }
    }

    private void emitPostCall(final String sessionId, final ChatClientResponse response, final long latencyMs) {
        if (sessionId == null || response == null) {
            return;
        }
        try {
            ChatResponse chatResponse = response.chatResponse();
            if (chatResponse == null) {
                return;
            }
            Usage usage = chatResponse.getMetadata() != null
                    ? chatResponse.getMetadata().getUsage()
                    : null;

            String usageJson = usage != null
                    ? String.format(
                    "{\"promptTokens\":%d,\"completionTokens\":%d,\"totalTokens\":%d}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens())
                    : null;

            String message = usage != null
                    ? String.format("Responded in %d ms (%d tokens)", latencyMs, usage.getTotalTokens())
                    : String.format("Responded in %d ms", latencyMs);

            String json = buildJson("post_call", message, null, latencyMs, usageJson);
            progressPublisher.emitThinking(sessionId, json);
        } catch (Exception ex) {
            log.debug("ThinkingAdvisor post-call emit failed: {}", ex.getMessage());
        }
    }

    private String buildJson(final String phase, final String message, final String detail,
                             final Long latencyMs, final String usageJson) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"phase\":\"").append(phase).append("\"");
        sb.append(",\"message\":\"").append(escape(message)).append("\"");
        if (detail != null) {
            sb.append(",\"detail\":\"").append(escape(detail)).append("\"");
        }
        if (latencyMs != null) {
            sb.append(",\"latencyMs\":").append(latencyMs);
        }
        if (usageJson != null) {
            sb.append(",\"usage\":").append(usageJson);
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link ThinkingAdvisor}.
     */
    public static final class Builder {

        private final ToolProgressPublisher progressPublisher;
        private int order = DEFAULT_ORDER;

        private Builder(final ToolProgressPublisher progressPublisher) {
            this.progressPublisher = Objects.requireNonNull(progressPublisher);
        }

        /**
         * Advisor chain order (default: {@code HIGHEST_PRECEDENCE + 20}).
         */
        public Builder order(final int order) {
            this.order = order;
            return this;
        }

        /** Builds the advisor. */
        public ThinkingAdvisor build() {
            return new ThinkingAdvisor(progressPublisher, order);
        }
    }
}
