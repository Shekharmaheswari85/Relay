/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.executor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Central discovery and lookup registry for all {@link AgentExecutor} beans in the application.
 *
 * <p>{@code AgentRegistry} is a singleton Spring {@code @Component} that collects every
 * {@link AgentExecutor} bean at startup via constructor injection, indexes them by their
 * {@link AgentExecutor#agentId()} value (normalized to lowercase), and exposes typed lookup
 * methods. It is the authoritative source for "which agents are available and how do I find one".
 *
 * <p>No configuration is required. Add a new agent to the system by declaring an
 * {@link AgentExecutor} implementation as a Spring bean — the registry picks it up automatically.
 * At startup, the registry logs the complete list of registered agent IDs at {@code INFO} level.
 *
 * <p>Agent ID matching is case-insensitive and trims leading/trailing whitespace, so
 * {@code "Order-Agent"}, {@code "order-agent"}, and {@code " ORDER-AGENT "} all resolve to the
 * same executor.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Autowired
 * AgentRegistry registry;
 *
 * // Resolve by ID — throws IllegalArgumentException if not found
 * AgentExecutor<?, ?, ?, ?, ?, ?, ?> executor = registry.resolve("order-agent");
 *
 * // Resolve by ID — returns null if not found (safe probe)
 * AgentExecutor<?, ?, ?, ?, ?, ?, ?> executor = registry.resolveOrNull(agentId);
 *
 * // Enumerate all registered agents for a capabilities API
 * List<AgentMetadataDTO> all = registry.listExecutors().stream()
 *         .map(AgentExecutor::metadata)
 *         .toList();
 *
 * // Guard before routing
 * if (registry.hasAgent(requestedId)) {
 *     registry.resolve(requestedId).sendMessage(sessionId, content);
 * }
 * }</pre>
 *
 * @see AgentExecutor
 * @see BaseAgentRuntimeService
 */
@Component
@Slf4j
public class AgentRegistry {

    private final Map<String, AgentExecutor<?, ?, ?, ?, ?, ?, ?>> executorsByAgentId;

    @SuppressWarnings({"rawtypes"})
    public AgentRegistry(final List<AgentExecutor> executors) {
        Map<String, AgentExecutor<?, ?, ?, ?, ?, ?, ?>> map = new HashMap<>();
        for (AgentExecutor executor : executors) {
            map.put(executor.agentId().toLowerCase(), executor);
        }
        this.executorsByAgentId = Collections.unmodifiableMap(map);
        log.info(
                "AgentRegistry initialized with {} agents: {}",
                executors.size(),
                executors.stream().map(AgentExecutor::agentId).toList());
    }

    /**
     * Resolves the executor registered under the given agent ID.
     *
     * <p>Matching is case-insensitive. The returned value is cast to the caller's target
     * type {@code E}; the cast is unchecked — callers that know the concrete executor type
     * can assign it directly, while callers that only need the base contract should use
     * {@code AgentExecutor<?, ?, ?, ?, ?, ?, ?>}.
     *
     * @param <E>     the expected executor type
     * @param agentId the agent identifier; matching is case-insensitive and trims whitespace
     * @return the registered executor cast to {@code E}; never {@code null}
     * @throws IllegalArgumentException if {@code agentId} is {@code null}, blank, or unknown;
     *                                  the exception message lists all registered agent IDs
     */
    @SuppressWarnings("unchecked")
    public <E extends AgentExecutor<?, ?, ?, ?, ?, ?, ?>> E resolve(final String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        AgentExecutor<?, ?, ?, ?, ?, ?, ?> executor =
                executorsByAgentId.get(agentId.trim().toLowerCase());
        if (executor == null) {
            throw new IllegalArgumentException(
                    "Unsupported agentId: " + agentId + ". Available agents: " + executorsByAgentId.keySet());
        }
        return (E) executor;
    }

    /**
     * Resolves the executor registered under the given agent ID, or returns {@code null} if none
     * is found.
     *
     * <p>Use this method when the caller needs to probe for an executor without triggering an
     * exception — for example when deciding whether to fall back to a default agent. Prefer
     * {@link #resolve(String)} in paths where the agent is expected to exist.
     *
     * @param <E>     the expected executor type
     * @param agentId the agent identifier; matching is case-insensitive and trims whitespace
     * @return the registered executor cast to {@code E}, or {@code null} if not found or if
     *         {@code agentId} is {@code null} or blank
     */
    @SuppressWarnings("unchecked")
    public <E extends AgentExecutor<?, ?, ?, ?, ?, ?, ?>> E resolveOrNull(final String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return (E) executorsByAgentId.get(agentId.trim().toLowerCase());
    }

    /**
     * Returns an immutable snapshot of all registered executors.
     *
     * <p>The order of the list is undefined; do not rely on it for routing priority.
     * Use {@link #resolve(String)} for targeted lookup by agent ID.
     *
     * @return an unmodifiable {@link List} of all registered executors; never {@code null}
     */
    public List<AgentExecutor<?, ?, ?, ?, ?, ?, ?>> listExecutors() {
        return List.copyOf(executorsByAgentId.values());
    }

    /**
     * Returns the number of executors currently registered in the registry.
     *
     * @return the count of registered executors; always {@code >= 0}
     */
    public int size() {
        return executorsByAgentId.size();
    }

    /**
     * Returns {@code true} if an executor registered under the given agent ID exists.
     *
     * <p>Matching is case-insensitive. Returns {@code false} for {@code null} or blank input
     * without throwing.
     *
     * @param agentId the agent identifier to probe; matching is case-insensitive
     * @return {@code true} if a registered executor exists for the given ID
     */
    public boolean hasAgent(final String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        return executorsByAgentId.containsKey(agentId.trim().toLowerCase());
    }
}
