/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.advisor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;

import io.relay.session.SessionContextHolder;

import lombok.extern.slf4j.Slf4j;

/**
 * Advisor that enforces a per-key rate limit on LLM calls using a token-bucket algorithm.
 *
 * <p>Protects the LLM gateway from being flooded by a single session, user, or tenant.
 * When the rate limit is exceeded, the advisor returns a graceful error message instead
 * of forwarding the request — no exception is thrown to the caller.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Bean
 * public RateLimitAdvisor rateLimitAdvisor() {
 *     return RateLimitAdvisor.builder()
 *             .maxRequests(10)                          // 10 requests per window
 *             .windowDuration(Duration.ofMinutes(1))    // per 1-minute window
 *             .keyExtractor(RateLimitAdvisor.PER_SESSION)  // keyed by session ID
 *             .rateLimitMessage("Rate limit reached. Please wait before sending another message.")
 *             .build();
 * }
 * }</pre>
 *
 * <h3>Key strategies</h3>
 * <ul>
 *   <li>{@link #PER_SESSION} — one bucket per session (default)</li>
 *   <li>{@link #GLOBAL} — single shared bucket for all requests</li>
 *   <li>Custom {@code Function<ChatClientRequest, String>} — e.g., per user or tenant</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <p>Uses a sliding window token-bucket: each key gets {@code maxRequests} tokens per
 * {@code windowDuration}. Tokens refill at a constant rate. Thread-safe via
 * {@link ConcurrentHashMap} and {@link AtomicLong}.
 *
 * <h3>Order</h3>
 * Placed at the front of the chain ({@code HIGHEST_PRECEDENCE + 5}) — before the LLM call
 * and audit advisor so that rate-limited requests generate no audit events or LLM costs.
 */
@Slf4j
public class RateLimitAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;
    private static final int DEFAULT_MAX_REQUESTS = 20;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    private static final String DEFAULT_RATE_LIMIT_MESSAGE =
            "Rate limit exceeded. Please wait a moment before sending another message.";

    /** Built-in key extractor: one bucket per active session ID. */
    public static final Function<ChatClientRequest, String> PER_SESSION =
            req -> {
                String sessionId = SessionContextHolder.get();
                return sessionId != null && !sessionId.isBlank() ? "session:" + sessionId : "global";
            };

    /** Built-in key extractor: single global bucket for all requests. */
    public static final Function<ChatClientRequest, String> GLOBAL = req -> "global";

    private final int maxRequests;
    private final long windowMs;
    private final Function<ChatClientRequest, String> keyExtractor;
    private final String rateLimitMessage;
    private final int order;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates a rate limiter with default settings (20 req/min, per-session).
     */
    public RateLimitAdvisor() {
        this(DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW, PER_SESSION, DEFAULT_RATE_LIMIT_MESSAGE, DEFAULT_ORDER);
    }

    /**
     * Full constructor — prefer {@link Builder} for readability.
     */
    public RateLimitAdvisor(
            final int maxRequests,
            final Duration windowDuration,
            final Function<ChatClientRequest, String> keyExtractor,
            final String rateLimitMessage,
            final int order) {
        this.maxRequests = Math.max(1, maxRequests);
        this.windowMs = Objects.requireNonNull(windowDuration, "Window duration must not be null").toMillis();
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "Key extractor must not be null");
        this.rateLimitMessage = rateLimitMessage != null ? rateLimitMessage : DEFAULT_RATE_LIMIT_MESSAGE;
        this.order = order;
    }

    /** Returns a builder for fluent configuration. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public @NonNull String getName() {
        return "RateLimitAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest request,
            final @NonNull CallAdvisorChain chain) {

        String key = keyExtractor.apply(request);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(maxRequests, windowMs));

        if (!bucket.tryConsume()) {
            log.warn("Rate limit exceeded for key={} (max={}/{}ms)", key, maxRequests, windowMs);
            return buildRateLimitResponse(request);
        }

        return chain.nextCall(request);
    }

    /**
     * Removes the token bucket for a specific key (e.g., when a session ends).
     *
     * @param key the rate-limit key to evict
     */
    public void evict(final String key) {
        buckets.remove(key);
    }

    /**
     * Returns the current token count for a key (for monitoring / testing).
     *
     * @param key the rate-limit key
     * @return remaining tokens in the current window, or {@code maxRequests} if no bucket exists
     */
    public long availableTokens(final String key) {
        TokenBucket bucket = buckets.get(key);
        return bucket == null ? maxRequests : bucket.availableTokens();
    }

    // ─── Rate limit response ──────────────────────────────────────────────────

    private @NonNull ChatClientResponse buildRateLimitResponse(final ChatClientRequest request) {
        String nonNullRateLimitMessage = Objects.requireNonNull(rateLimitMessage, "Rate limit message must not be null");
        AssistantMessage message = new AssistantMessage(nonNullRateLimitMessage);
        Generation generation = new Generation(message);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    // ─── Token bucket ─────────────────────────────────────────────────────────

    /**
     * Thread-safe token bucket using a sliding window refill strategy.
     */
    private static final class TokenBucket {

        private final int maxTokens;
        private final long windowMs;
        private final AtomicLong tokens;
        private final AtomicLong windowStart;

        TokenBucket(final int maxTokens, final long windowMs) {
            this.maxTokens = maxTokens;
            this.windowMs = windowMs;
            this.tokens = new AtomicLong(maxTokens);
            this.windowStart = new AtomicLong(System.currentTimeMillis());
        }

        boolean tryConsume() {
            refillIfNeeded();
            long current;
            do {
                current = tokens.get();
                if (current <= 0) {
                    return false;
                }
            } while (!tokens.compareAndSet(current, current - 1));
            return true;
        }

        long availableTokens() {
            refillIfNeeded();
            return Math.max(0, tokens.get());
        }

        private void refillIfNeeded() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start >= windowMs && windowStart.compareAndSet(start, now)) {
                tokens.set(maxTokens);
            }    
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link RateLimitAdvisor}.
     */
    public static final class Builder {

        private int maxRequests = DEFAULT_MAX_REQUESTS;
        private Duration windowDuration = DEFAULT_WINDOW;
        private Function<ChatClientRequest, String> keyExtractor = PER_SESSION;
        private String rateLimitMessage = DEFAULT_RATE_LIMIT_MESSAGE;
        private int order = DEFAULT_ORDER;

        private Builder() {}

        /**
         * Maximum number of LLM requests allowed per window per key (default: 20).
         */
        public Builder maxRequests(final int maxRequests) {
            this.maxRequests = maxRequests;
            return this;
        }

        /**
         * Duration of each rate-limit window (default: 1 minute).
         */
        public Builder windowDuration(final Duration windowDuration) {
            this.windowDuration = windowDuration;
            return this;
        }

        /**
         * Function that derives the bucket key from a request.
         * Use {@link #PER_SESSION} or {@link #GLOBAL} for common strategies.
         */
        public Builder keyExtractor(final Function<ChatClientRequest, String> keyExtractor) {
            this.keyExtractor = keyExtractor;
            return this;
        }

        /**
         * Message returned to the caller when the rate limit is exceeded.
         */
        public Builder rateLimitMessage(final String message) {
            this.rateLimitMessage = message;
            return this;
        }

        /**
         * Advisor chain order (default: {@code HIGHEST_PRECEDENCE + 5}).
         */
        public Builder order(final int order) {
            this.order = order;
            return this;
        }

        public RateLimitAdvisor build() {
            return new RateLimitAdvisor(maxRequests, windowDuration, keyExtractor, rateLimitMessage, order);
        }
    }
}
