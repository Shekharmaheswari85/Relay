/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.a2a;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.agentcore.stream.PipelineEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Fans a single message out to multiple remote agents and forwards their SSE events
 * into a {@link PipelineEmitter} according to the configured {@link FanOutStrategy}.
 *
 * <p>Use {@code MultiAgentOrchestrator} when a task requires input from several specialized
 * agents simultaneously and the results should be combined into a single response stream.
 * A common pattern is a front-end orchestrator that asks an inventory agent, a pricing agent,
 * and a recommendation agent in parallel, then stitches their answers together.
 *
 * <h3>Fan-out strategies</h3>
 * <table>
 *   <tr><th>Strategy</th><th>Behaviour</th><th>Use when</th></tr>
 *   <tr>
 *     <td>{@link FanOutStrategy#PARALLEL}</td>
 *     <td>All agents called simultaneously on virtual threads; events interleaved as they arrive.</td>
 *     <td>Fastest; order of events does not matter.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link FanOutStrategy#SEQUENTIAL}</td>
 *     <td>Agents called one after the other on the calling thread; each agent's stream completes before the next starts.</td>
 *     <td>Order matters; later agents need the context produced by earlier ones.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link FanOutStrategy#FIRST_WINS}</td>
 *     <td>All agents called simultaneously on virtual threads; only the first agent to emit any event is proxied.</td>
 *     <td>Redundant agents providing the same capability; optimise for latency.</td>
 *   </tr>
 * </table>
 *
 * <h3>Setup</h3>
 * <p>Each agent is expected to already have an open session. Supply one session ID per
 * agent client in the same order as the clients list:
 *
 * <pre>{@code
 * @Bean
 * public MultiAgentOrchestrator productEnrichmentOrchestrator(AgentClientRegistry registry) {
 *     return MultiAgentOrchestrator.builder()
 *             .client(registry.get("inventory-agent"))
 *             .client(registry.get("pricing-agent"))
 *             .client(registry.get("recommendation-agent"))
 *             .strategy(FanOutStrategy.PARALLEL)
 *             .build();
 * }
 *
 * // In your orchestrator:
 * productEnrichmentOrchestrator.route(
 *         List.of(inventorySessionId, pricingSessionId, recoSessionId),
 *         "Enrich product SKU-123",
 *         emitter);
 * }</pre>
 *
 * <h3>Error handling</h3>
 * <p>In {@code PARALLEL} and {@code SEQUENTIAL} strategies, if one agent's stream errors the
 * error is logged and the stream for that agent terminates, but the other agents' streams
 * continue. In {@code FIRST_WINS}, the first agent to emit any event wins; other threads
 * stop proxying once the winner is determined.
 *
 * @see AgentClient
 * @see AgentClientRegistry
 */
@Slf4j
public final class MultiAgentOrchestrator {

    /**
     * Determines how the orchestrator fans out to and merges results from multiple agents.
     */
    public enum FanOutStrategy {
        /**
         * All agents are called concurrently on virtual threads; SSE events are interleaved
         * as they arrive. This is the default and fastest strategy.
         */
        PARALLEL,

        /**
         * Agents are called sequentially on the calling thread; each agent's stream must
         * complete before the next agent is called.
         */
        SEQUENTIAL,

        /**
         * All agents are called concurrently on virtual threads; only the first agent to
         * emit any event is proxied — all other threads stop forwarding events once a winner
         * is determined.
         */
        FIRST_WINS
    }

    private final List<AgentClient> clients;
    private final FanOutStrategy strategy;

    private MultiAgentOrchestrator(final List<AgentClient> clients, final FanOutStrategy strategy) {
        this.clients = List.copyOf(Objects.requireNonNull(clients, "clients must not be null"));
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("MultiAgentOrchestrator requires at least one AgentClient");
        }
    }

    /** Returns a builder for fluent configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fans the message out to all configured agents and forwards their SSE events to the
     * given {@link PipelineEmitter} according to the configured {@link FanOutStrategy}.
     *
     * <p>Each entry in {@code sessionIds} corresponds to the agent at the same index in the
     * client list provided at build time. If the lists have different sizes, excess entries
     * are ignored and missing session IDs fall back to {@code null} (the remote agent will
     * create a new session).
     *
     * <p>This method blocks the calling virtual thread until all agent interactions complete
     * (PARALLEL and FIRST_WINS wait on a {@link CountDownLatch}; SEQUENTIAL processes
     * agents one by one on the calling thread).
     *
     * @param sessionIds pre-existing session IDs on the remote agents, one per client;
     *                   pass {@code null} entries if a session should be created on the fly
     * @param message    the user message to send to all agents
     * @param emitter    the pipeline emitter to forward SSE events into; never null
     */
    public void route(final List<String> sessionIds, final String message, final PipelineEmitter emitter) {
        switch (strategy) {
            case PARALLEL -> {
                log.debug("MultiAgent PARALLEL fan-out to {} agents", clients.size());
                runParallel(sessionIds, message, emitter, null);
            }
            case SEQUENTIAL -> {
                log.debug("MultiAgent SEQUENTIAL fan-out to {} agents", clients.size());
                runSequential(sessionIds, message, emitter);
            }
            case FIRST_WINS -> {
                log.debug("MultiAgent FIRST_WINS fan-out to {} agents", clients.size());
                runParallel(sessionIds, message, emitter, new AtomicBoolean(false));
            }
        }
    }

    /**
     * Convenience overload that creates a fresh remote session per agent before routing.
     *
     * <p>Each agent receives a new session with the given {@code agentId} and
     * {@code correlationId} as the creator identifier. Session creation is performed
     * synchronously on the calling virtual thread before routing begins.
     *
     * @param agentId       the agent ID passed to remote session creation
     * @param correlationId a correlation identifier for tracing (e.g., the local session ID)
     * @param message       the user message to send
     * @param emitter       the pipeline emitter to forward SSE events into; never null
     */
    public void routeWithNewSessions(
            final String agentId, final String correlationId, final String message, final PipelineEmitter emitter) {

        final String[] sessionIdsArray = new String[clients.size()];
        CountDownLatch latch = new CountDownLatch(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            final int index = i;
            final AgentClient client = clients.get(i);
            Thread.ofVirtual().start(() -> {
                try {
                    String sessionId = client.createSession(agentId, "multi-agent:" + correlationId);
                    sessionIdsArray[index] = sessionId;
                } catch (Exception ex) {
                    log.warn("Session creation failed for '{}': {}", client.getName(), ex.getMessage());
                    sessionIdsArray[index] = "__failed__";
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                log.warn("MultiAgent session creation timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("MultiAgent session creation interrupted");
        }

        List<String> sessionIds = Arrays.asList(sessionIdsArray);
        route(sessionIds, message, emitter);
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    /**
     * Runs all agents in parallel on virtual threads, optionally with a FIRST_WINS gate.
     * When {@code won} is {@code null} the strategy is PARALLEL (all events proxied).
     * When {@code won} is non-null the strategy is FIRST_WINS (only the first emitter wins).
     */
    private void runParallel(
            final List<String> sessionIds,
            final String message,
            final PipelineEmitter emitter,
            final AtomicBoolean won) {

        CountDownLatch latch = new CountDownLatch(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            final AgentClient client = clients.get(i);
            final String sessionId = resolveSessionId(sessionIds, i);
            final boolean[] threadWon = {false};
            Thread.ofVirtual().start(() -> {
                try {
                    streamToEmitter(client, sessionId, message, event -> {
                        if (won == null) {
                            proxyEvent(event, emitter);
                        } else {
                            if (!threadWon[0]) {
                                threadWon[0] = won.compareAndSet(false, true);
                            }
                            if (threadWon[0]) {
                                proxyEvent(event, emitter);
                            }
                        }
                    });
                } catch (Exception ex) {
                    log.warn("Agent '{}' stream failed: {}", client.getName(), ex.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("MultiAgent parallel fan-out interrupted");
        }
    }

    /**
     * Runs all agents sequentially on the calling thread.
     */
    private void runSequential(
            final List<String> sessionIds,
            final String message,
            final PipelineEmitter emitter) {

        for (int i = 0; i < clients.size(); i++) {
            AgentClient client = clients.get(i);
            String sessionId = resolveSessionId(sessionIds, i);
            try {
                streamToEmitter(client, sessionId, message, event -> proxyEvent(event, emitter));
            } catch (Exception ex) {
                log.warn("Agent '{}' stream failed: {}", client.getName(), ex.getMessage());
            }
        }
    }

    private void streamToEmitter(
            final AgentClient client,
            final String sessionId,
            final String message,
            final Consumer<SseEvent> onEvent) {
        String resolvedSession = sessionId;
        if (resolvedSession == null || resolvedSession.isBlank() || "__failed__".equals(resolvedSession)) {
            resolvedSession = client.createSession(null, "multi-agent");
        }
        client.sendMessage(resolvedSession, message, onEvent);
    }

    private String resolveSessionId(final List<String> sessionIds, final int index) {
        return (sessionIds != null && index < sessionIds.size()) ? sessionIds.get(index) : null;
    }

    /**
     * Routes a single SSE event from a remote agent into the local {@link PipelineEmitter}.
     *
     * <p>Event name mapping:
     * <ul>
     *   <li>{@code message} → {@link PipelineEmitter#sendMessage(String)}</li>
     *   <li>{@code thinking} → {@link PipelineEmitter#sendThinking(String)}</li>
     *   <li>{@code tool_progress} → {@link PipelineEmitter#sendToolProgress(String)}</li>
     *   <li>{@code stage} → {@link PipelineEmitter#sendStage(String, String, int)} with stage {@code "remote_agent"} and progress {@code 50}</li>
     *   <li>{@code follow_up_questions} → {@link PipelineEmitter#sendThinking(String)}</li>
     *   <li>{@code error} → {@link PipelineEmitter#sendError(String)}</li>
     *   <li>{@code done} → skipped (the orchestrator sends done)</li>
     * </ul>
     *
     * @param event   the incoming SSE event from the remote agent
     * @param emitter the local pipeline emitter to write into
     */
    private void proxyEvent(final SseEvent event, final PipelineEmitter emitter) {
        String name = event.event();
        String data = event.data();
        if (name == null || data == null) {
            return;
        }
        switch (name) {
            case "message"             -> emitter.sendMessage(data);
            case "thinking", "follow_up_questions" -> emitter.sendThinking(data);
            case "tool_progress"       -> emitter.sendToolProgress(data);
            case "stage"               -> emitter.sendStage("remote_agent", data, 50);
            case "error"               -> emitter.sendError(data);
            case "done"                -> { /* orchestrator sends done */ }
            default                    -> log.debug("Unrecognised SSE event name '{}' from remote agent — skipping", name);
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link MultiAgentOrchestrator}.
     */
    public static final class Builder {

        private final List<AgentClient> clients = new ArrayList<>();
        private FanOutStrategy strategy = FanOutStrategy.PARALLEL;

        private Builder() {}

        /**
         * Adds a remote agent client to the fan-out pool.
         *
         * @param client the agent client to add; never null
         */
        public Builder client(final AgentClient client) {
            this.clients.add(Objects.requireNonNull(client));
            return this;
        }

        /**
         * Adds multiple remote agent clients.
         *
         * @param clients the clients to add; never null or empty
         */
        public Builder clients(final List<AgentClient> clients) {
            this.clients.addAll(Objects.requireNonNull(clients));
            return this;
        }

        /**
         * Sets the fan-out strategy (default: {@link FanOutStrategy#PARALLEL}).
         *
         * @param strategy the strategy; never null
         */
        public Builder strategy(final FanOutStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy);
            return this;
        }

        /** Builds the orchestrator. */
        public MultiAgentOrchestrator build() {
            return new MultiAgentOrchestrator(clients, strategy);
        }
    }
}
