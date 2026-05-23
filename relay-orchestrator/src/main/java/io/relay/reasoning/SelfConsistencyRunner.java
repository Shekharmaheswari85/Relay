/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Implements the <em>Self-Consistency</em> reasoning strategy by sampling {@code k}
 * independent responses to the same prompt and returning the majority-vote answer.
 *
 * <h3>Why self-consistency helps</h3>
 * <p>LLMs are stochastic — the same prompt with non-zero temperature can produce different
 * answers across calls. On high-stakes questions (math, logic, classification) the correct
 * answer tends to appear more often than incorrect alternatives. Majority voting over
 * {@code k} independent samples exploits this statistical property to improve reliability
 * without any fine-tuning.
 *
 * <h3>Execution model</h3>
 * <p>All {@code k} LLM calls run concurrently on Java 21 virtual threads, so wall-clock
 * latency is bounded by the slowest individual call rather than their sum. A configurable
 * {@code timeoutSeconds} (default 60 s) prevents indefinite blocking.
 *
 * <h3>Majority vote</h3>
 * <p>The default {@link #selectBestResponse} implementation normalises each response
 * (trim, collapse whitespace, lower-case) and returns the most frequent value. When
 * multiple responses share the highest frequency, the first one in collection order wins.
 * Override this method for domain-specific selection — for example choosing the response
 * with the highest log-probability or the longest chain of reasoning.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SelfConsistencyRunner runner = SelfConsistencyRunner.builder(chatClient)
 *         .sampleCount(5)
 *         .systemPrompt("You are a precise mathematical assistant.")
 *         .scoreThreshold(3)   // require at least 3 of 5 to agree
 *         .build();
 *
 * String answer = runner.run("What is 17 × 18 − 42?");
 * }</pre>
 *
 * @see ReasoningStrategy#SELF_CONSISTENCY
 */
@Slf4j
public class SelfConsistencyRunner {

    private static final int DEFAULT_SAMPLE_COUNT = 5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final ChatClient chatClient;
    private final int sampleCount;
    private final int timeoutSeconds;
    @Nullable
    private final String systemPrompt;

    /**
     * Creates a runner from the given builder state. Use {@link #builder(ChatClient)} to
     * obtain a builder.
     *
     * @param builder a fully configured builder; never {@code null}
     */
    protected SelfConsistencyRunner(final Builder builder) {
        this.chatClient = Objects.requireNonNull(builder.chatClient, "ChatClient must not be null");
        this.sampleCount = Math.max(1, builder.sampleCount);
        this.timeoutSeconds = Math.max(1, builder.timeoutSeconds);
        this.systemPrompt = builder.systemPrompt;
    }

    /**
     * Returns a fluent builder for configuring a {@link SelfConsistencyRunner}.
     *
     * @param chatClient the {@link ChatClient} used for all sample calls; never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Runs {@code k} independent LLM calls for the given {@code prompt} in parallel on
     * virtual threads and returns the majority-vote answer.
     *
     * <p>Calls that time out or throw exceptions are silently excluded from the vote.
     * If fewer than one successful response is collected, {@code null} is returned.
     *
     * @param prompt the user prompt to sample; never {@code null}
     * @return the majority-vote answer, or {@code null} if no successful samples were
     *         collected
     */
    @Nullable
    public String run(@NonNull final String prompt) {
        Objects.requireNonNull(prompt, "Prompt must not be null");

        List<String> responses = collectSamples(prompt);
        if (responses.isEmpty()) {
            log.warn("SelfConsistencyRunner: all {} samples failed or timed out for prompt (len={})",
                    sampleCount, prompt.length());
            return null;
        }

        String winner = selectBestResponse(responses);
        log.debug("SelfConsistencyRunner: {} of {} samples collected, selected winner (len={})",
                responses.size(), sampleCount, winner.length());
        return winner;
    }

    // ─── Protected extension point ────────────────────────────────────────────

    /**
     * Selects the best response from the collected samples.
     *
     * <p>The default implementation normalises each response (trim, collapse runs of
     * whitespace, lower-case) and returns the raw response whose normalised form appears
     * most frequently. When there is a tie the first response in collection order wins.
     *
     * <p>Override to implement custom selection logic, such as:
     * <ul>
     *   <li>Choosing the longest response with a certain minimum vote count.</li>
     *   <li>Extracting a structured answer field before comparing.</li>
     *   <li>Applying a secondary scorer (embedding similarity, rule check) to break ties.</li>
     * </ul>
     *
     * @param responses the non-empty list of raw response strings collected from the LLM;
     *                  never {@code null} or empty
     * @return the selected best response; never {@code null}
     */
    @NonNull
    protected String selectBestResponse(@NonNull final List<String> responses) {
        String fallback = responses.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");

        Map<String, Long> frequencyMap = responses.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(SelfConsistencyRunner::normalise, Collectors.counting()));

        String winningNormalised = frequencyMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(normalise(fallback));

        String selected = responses.stream()
                .filter(Objects::nonNull)
                .filter(r -> winningNormalised.equals(normalise(r)))
                .findFirst()
                .orElse(fallback);
        if (selected == null) {
            return "";
        }
        return selected;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private List<String> collectSamples(final String prompt) {
        CopyOnWriteArrayList<String> collected = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < sampleCount; i++) {
                final int sampleIndex = i;
                futures.add(executor.submit(() -> {
                    try {
                        String response = callLlm(prompt);
                        if (response != null && !response.isBlank()) {
                            collected.add(response);
                        }
                    } catch (Exception ex) {
                        log.debug("SelfConsistencyRunner: sample {} failed: {}", sampleIndex, ex.getMessage());
                    }
                }));
            }

            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            for (var future : futures) {
                long remaining = endTime - System.currentTimeMillis();
                try {
                    if (remaining > 0) {
                        future.get(remaining, TimeUnit.MILLISECONDS);
                    } else {
                        future.cancel(true);
                    }
                } catch (TimeoutException ex) {
                    log.warn("SelfConsistencyRunner: sample collection timed out");
                    future.cancel(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("SelfConsistencyRunner: interrupted while waiting for samples");
                    futures.forEach(f -> f.cancel(true));
                    break;
                } catch (ExecutionException ex) {
                    log.debug("SelfConsistencyRunner: sample collection failed: {}", ex.getMessage());
                }
            }
        }

        return new ArrayList<>(collected);
    }

    @Nullable
    private String callLlm(final String prompt) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null"));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            spec = chatClient.prompt().system(Objects.requireNonNull(systemPrompt, "System prompt must not be null")).user(prompt);
        }
        return spec.call().content();
    }

    private static String normalise(final String text) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(text.trim().split("\\s+"))
                .collect(Collectors.joining(" "))
                .toLowerCase();
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link SelfConsistencyRunner} with customised
     * settings. Obtain an instance via {@link SelfConsistencyRunner#builder(ChatClient)}.
     */
    public static final class Builder {

        private final ChatClient chatClient;
        private int sampleCount = DEFAULT_SAMPLE_COUNT;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        @Nullable
        private String systemPrompt;

        private Builder(final ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "ChatClient must not be null");
        }

        /**
         * Sets the number of independent LLM samples to collect.
         *
         * <p>Higher values improve reliability but increase latency and cost. Values less
         * than 1 are clamped to 1.
         *
         * @param sampleCount the number of independent calls (default: 5)
         * @return this builder
         */
        public Builder sampleCount(final int sampleCount) {
            this.sampleCount = sampleCount;
            return this;
        }

        /**
         * Sets the maximum wall-clock time to wait for all samples to complete.
         *
         * <p>Samples that exceed this budget are excluded from the vote. Values less than 1
         * are clamped to 1 second.
         *
         * @param timeoutSeconds the timeout in seconds (default: 60)
         * @return this builder
         */
        public Builder timeoutSeconds(final int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * Sets an optional system prompt injected into every sample call.
         *
         * <p>Use this to prime the model with a persona or domain context without repeating
         * it in the user prompt. {@code null} or blank values are ignored.
         *
         * @param systemPrompt the system prompt text; may be {@code null}
         * @return this builder
         */
        public Builder systemPrompt(@Nullable final String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * Constructs the {@link SelfConsistencyRunner} from the current builder state.
         *
         * @return a fully configured runner; never {@code null}
         */
        public SelfConsistencyRunner build() {
            return new SelfConsistencyRunner(this);
        }
    }
}
