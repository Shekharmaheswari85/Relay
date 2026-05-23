/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.advisor;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.client.HttpStatusCodeException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Advisor that activates a fallback LLM model when the primary model fails.
 * <p>
 * Inspired by ODIN's PTU → PayGo fallback pattern, this advisor catches
 * rate-limit and availability failures (HTTP 429, 502, 503, 504) and
 * transparently retries the same prompt on a secondary model — with no
 * changes to the caller.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Bean
 * public FallbackModelAdvisor fallbackModelAdvisor(
 *         MeterRegistry meterRegistry,
 *         AgentLlmProperties llmProperties) {
 *
 *     OpenAiChatModel payGoModel = OpenAiChatModel.builder()
 *             .openAiApi(buildPayGoApi(llmProperties))
 *             .build();
 *     return FallbackModelAdvisor.builder(payGoModel, meterRegistry)
 *             .maxRetries(2)
 *             .retryBackoff(Duration.ofSeconds(1))
 *             .build();
 * }
 * }</pre>
 *
 * <h3>Triggering conditions</h3>
 * <ul>
 *   <li>HTTP 429 (Too Many Requests / Rate Limit)</li>
 *   <li>HTTP 502, 503, 504 (Gateway / Service Unavailable)</li>
 *   <li>Exception messages containing "rate limit", "too many requests",
 *       "service unavailable", "overloaded", or "capacity"</li>
 * </ul>
 *
 * <h3>Retry behaviour</h3>
 * <p>When {@code maxRetries > 1}, each fallback attempt is separated by {@code retryBackoff}.
 * If all attempts fail, the original exception from the primary call is re-thrown.
 *
 * <h3>Order</h3>
 * Placed near the end of the chain ({@code LOWEST_PRECEDENCE - 10}) so it wraps
 * the actual LLM call but does not interfere with guard or audit advisors.
 *
 * @see CircuitBreakerAdvisor
 */
@Slf4j
public class FallbackModelAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.LOWEST_PRECEDENCE - 10;
    private static final Set<Integer> DEFAULT_FALLBACK_STATUS_CODES = Set.of(429, 502, 503, 504);
    private static final int DEFAULT_MAX_RETRIES = 1;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ZERO;

    private final ChatModel fallbackModel;
    private final Set<Integer> fallbackStatusCodes;
    private final int order;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final Counter fallbackCounter;

    /**
     * Creates an advisor with default HTTP status triggers and a single fallback attempt.
     *
     * @param fallbackModel  the secondary model to invoke on failure
     * @param meterRegistry  for recording fallback activation metrics
     */
    public FallbackModelAdvisor(final ChatModel fallbackModel, final MeterRegistry meterRegistry) {
        this(fallbackModel, DEFAULT_FALLBACK_STATUS_CODES, DEFAULT_ORDER,
                DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF, meterRegistry);
    }

    /**
     * Full constructor — prefer the {@link Builder} for readability.
     */
    public FallbackModelAdvisor(
            final ChatModel fallbackModel,
            final Set<Integer> fallbackStatusCodes,
            final int order,
            final int maxRetries,
            final Duration retryBackoff,
            final MeterRegistry meterRegistry) {
        this.fallbackModel = Objects.requireNonNull(fallbackModel, "Fallback model must not be null");
        this.fallbackStatusCodes = Set.copyOf(fallbackStatusCodes);
        this.order = order;
        this.maxRetries = Math.max(1, maxRetries);
        this.retryBackoff = retryBackoff != null ? retryBackoff : DEFAULT_RETRY_BACKOFF;
        this.fallbackCounter = Counter.builder("relay.llm.fallback.activations")
                .description("Number of times the LLM fallback model was activated")
                .register(meterRegistry);
    }

    /**
     * Returns a builder for fluent configuration.
     *
     * @param fallbackModel the secondary model to invoke on failure
     * @param meterRegistry for recording fallback activation metrics
     */
    public static Builder builder(final ChatModel fallbackModel, final MeterRegistry meterRegistry) {
        return new Builder(fallbackModel, meterRegistry);
    }

    @Override
    public @NonNull String getName() {
        return "FallbackModelAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest chatClientRequest,
            final @NonNull CallAdvisorChain callAdvisorChain) {

        Exception primaryException;
        try {
            return callAdvisorChain.nextCall(chatClientRequest);
        } catch (Exception ex) {
            if (!shouldFallback(ex)) {
                throw ex;
            }
            primaryException = ex;
        }

        log.warn("Primary LLM call failed [{}], activating fallback model (maxRetries={})",
                summarizeError(primaryException), maxRetries);
        fallbackCounter.increment();
        return invokeFallbackWithRetry(chatClientRequest, primaryException);
    }

    // ─── Fallback execution ───────────────────────────────────────────────────

    private @NonNull ChatClientResponse invokeFallbackWithRetry(
            final ChatClientRequest request, final Exception primaryEx) {

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (attempt > 1 && !retryBackoff.isZero()) {
                sleepUninterrupted(retryBackoff);
            }
            try {
                ChatResponse fallbackResponse = Objects.requireNonNull(
                        fallbackModel.call(request.prompt()),
                        "Fallback chat response must not be null");
                log.info("Fallback LLM call succeeded (attempt={}/{})", attempt, maxRetries);
                return ChatClientResponse.builder()
                        .chatResponse(fallbackResponse)
                        .context(request.context())
                        .build();
            } catch (Exception fallbackEx) {
                log.warn("Fallback LLM attempt {}/{} failed [{}]", attempt, maxRetries, fallbackEx.getMessage());
            }
        }

        log.error("All {} fallback attempt(s) failed. Re-throwing original exception.", maxRetries);
        throw primaryEx instanceof RuntimeException re ? re : new RuntimeException(primaryEx.getMessage(), primaryEx);
    }

    private void sleepUninterrupted(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("Fallback retry sleep interrupted");
        }
    }

    // ─── Trigger detection ────────────────────────────────────────────────────

    private boolean shouldFallback(final Exception ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof HttpStatusCodeException httpEx) {
                return fallbackStatusCodes.contains(httpEx.getStatusCode().value());
            }
            cause = cause.getCause();
        }
        String msg = ex.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase(Locale.ROOT);
            return lower.contains("rate limit")
                    || lower.contains("too many requests")
                    || lower.contains("service unavailable")
                    || lower.contains("overloaded")
                    || lower.contains("capacity");
        }
        return false;
    }

    private String summarizeError(final Exception ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link FallbackModelAdvisor}.
     */
    public static final class Builder {

        private final ChatModel fallbackModel;
        private final MeterRegistry meterRegistry;
        private Set<Integer> fallbackStatusCodes = DEFAULT_FALLBACK_STATUS_CODES;
        private int order = DEFAULT_ORDER;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration retryBackoff = DEFAULT_RETRY_BACKOFF;

        private Builder(final ChatModel fallbackModel, final MeterRegistry meterRegistry) {
            this.fallbackModel = Objects.requireNonNull(fallbackModel, "Fallback model must not be null");
            this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry must not be null");
        }

        /**
         * HTTP status codes that trigger fallback (default: 429, 502, 503, 504).
         */
        public Builder fallbackStatusCodes(final Set<Integer> codes) {
            this.fallbackStatusCodes = Set.copyOf(codes);
            return this;
        }

        /**
         * Advisor chain order (default: {@code LOWEST_PRECEDENCE - 10}).
         */
        public Builder order(final int order) {
            this.order = order;
            return this;
        }

        /**
         * Number of fallback attempts before giving up (default: 1).
         * Values less than 1 are treated as 1.
         */
        public Builder maxRetries(final int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Delay between fallback retry attempts (default: no delay).
         */
        public Builder retryBackoff(final Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
            return this;
        }

        public FallbackModelAdvisor build() {
            return new FallbackModelAdvisor(
                    fallbackModel, fallbackStatusCodes, order, maxRetries, retryBackoff, meterRegistry);
        }
    }
}
