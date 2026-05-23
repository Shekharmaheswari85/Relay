/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.advisor;

import java.time.Duration;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;

/**
 * Advisor that wraps the LLM call chain with a Resilience4j circuit breaker.
 * <p>
 * Provides automatic failure handling to prevent cascading failures when
 * the LLM provider is experiencing issues.
 * <p>
 * Default configuration:
 * <ul>
 *   <li>Opens after 3 consecutive failures</li>
 *   <li>Failure rate threshold: 60%</li>
 *   <li>Half-open after 60 seconds</li>
 *   <li>Sliding window size: 5 calls</li>
 * </ul>
 */
@Slf4j
public class CircuitBreakerAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 3;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final int DEFAULT_HALF_OPEN_CALLS = 1;
    private static final long DEFAULT_WAIT_DURATION_SECONDS = 60;
    private static final int DEFAULT_SLIDING_WINDOW_SIZE = 5;
    private static final int DEFAULT_FAILURE_RATE_THRESHOLD = 60;

    /**
     * -- GETTER --
     *  Returns the underlying circuit breaker for advanced operations.
     */
    @Getter
    private final CircuitBreaker circuitBreaker;

    private final int order;

    /**
     * Creates a circuit breaker advisor with default configuration.
     *
     * @param name the circuit breaker name (used for metrics)
     */
    public CircuitBreakerAdvisor(final String name) {
        this(name, buildDefaultConfig(), DEFAULT_ORDER);
    }

    /**
     * Creates a circuit breaker advisor with custom configuration.
     *
     * @param name   the circuit breaker name
     * @param config the circuit breaker configuration
     * @param order  the advisor order (lower = earlier)
     */
    public CircuitBreakerAdvisor(final String name, final CircuitBreakerConfig config, final int order) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.circuitBreaker = registry.circuitBreaker(name);
        this.order = order;

        this.circuitBreaker
                .getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "Circuit breaker '{}' state transition: {} -> {}",
                        name,
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * Creates a circuit breaker advisor with an existing circuit breaker instance.
     * Useful for testing.
     */
    public CircuitBreakerAdvisor(final CircuitBreaker circuitBreaker, final int order) {
        this.circuitBreaker = circuitBreaker;
        this.order = order;
    }

    /**
     * Builds the default circuit breaker configuration.
     */
    public static CircuitBreakerConfig buildDefaultConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(DEFAULT_FAILURE_RATE_THRESHOLD)
                .minimumNumberOfCalls(DEFAULT_FAILURE_THRESHOLD)
                .slidingWindowSize(DEFAULT_SLIDING_WINDOW_SIZE)
                .permittedNumberOfCallsInHalfOpenState(DEFAULT_HALF_OPEN_CALLS)
                .waitDurationInOpenState(Duration.ofSeconds(DEFAULT_WAIT_DURATION_SECONDS))
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    @Override
    public @NonNull String getName() {
        return "CircuitBreakerAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest chatClientRequest, final @NonNull CallAdvisorChain callAdvisorChain) {

        return Objects.requireNonNull(circuitBreaker.executeSupplier(() -> {
            log.debug("Circuit breaker state: {}", circuitBreaker.getState());
            return callAdvisorChain.nextCall(chatClientRequest);
        }), "Advisor response must not be null");
    }

    /**
     * Returns the current circuit breaker state.
     */
    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }
}
