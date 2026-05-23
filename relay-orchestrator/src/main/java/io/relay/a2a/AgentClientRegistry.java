/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Registry of configured {@link AgentClient} instances, keyed by logical agent name.
 *
 * <p>Registered as a Spring bean when {@code agent.a2a.enabled=true}. Sub-agents that
 * delegate to remote agents inject this registry and look up their target by name:
 *
 * <pre>{@code
 * @Component
 * public class InventorySubAgent extends RemoteAgentSubAgent<MySession, MyStep> {
 *
 *     public InventorySubAgent(AgentClientRegistry registry) {
 *         super(registry.get("inventory-agent"));
 *     }
 * }
 * }</pre>
 *
 * <h3>How clients are created</h3>
 * <p>At startup, {@code AgentClientRegistry} reads {@code agent.a2a.clients.*} properties
 * and constructs one {@link AgentClient} per entry. All registered {@link A2AAuthContributor}
 * beans are passed to every client so each can selectively contribute headers by agent name.
 *
 * @see AgentClient
 * @see AgentClientProperties
 * @see A2AAuthContributor
 */
@Slf4j
public class AgentClientRegistry {

    private final Map<String, AgentClient> clients;

    /**
     * Constructs the registry from a pre-built map of clients.
     *
     * @param clients the map of logical name → {@link AgentClient}; never null
     */
    public AgentClientRegistry(final Map<String, AgentClient> clients) {
        this.clients = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(clients, "clients map must not be null")));
        log.info("AgentClientRegistry initialised with {} remote agent(s): {}",
                clients.size(), clients.keySet());
    }

    /**
     * Returns the {@link AgentClient} for the given logical agent name.
     *
     * @param agentName the logical name configured under {@code agent.a2a.clients}
     * @return the client
     * @throws IllegalArgumentException if no client is configured for the given name
     */
    public AgentClient get(final String agentName) {
        AgentClient client = clients.get(agentName);
        if (client == null) {
            throw new IllegalArgumentException(
                    "No A2A client configured for agent '" + agentName
                    + "'. Available: " + clients.keySet());
        }
        return client;
    }

    /**
     * Returns all registered agent names, in the order they were configured.
     *
     * @return unmodifiable list of agent names
     */
    public List<String> registeredAgents() {
        return List.copyOf(clients.keySet());
    }

    /**
     * Returns all registered clients.
     *
     * @return unmodifiable map of logical name → client
     */
    public Map<String, AgentClient> all() {
        return clients;
    }

    // ─── Auto-configuration ───────────────────────────────────────────────────

    /**
     * Auto-configuration that constructs the registry from YAML properties.
     *
     * <p>Registers a shared {@link A2AHttpClient} singleton (overridable via
     * {@code @ConditionalOnMissingBean}) and one {@link AgentClient} per entry under
     * {@code agent.a2a.clients}. All clients share the same {@code A2AHttpClient} and
     * the Spring-managed {@link ObjectMapper}.
     */
    @Configuration
    @EnableConfigurationProperties(AgentClientProperties.class)
    @ConditionalOnProperty(prefix = "agent.a2a", name = "enabled", havingValue = "true")
    public static class AutoConfig {

        /**
         * Creates the shared singleton HTTP client used by all {@link AgentClient} instances.
         * Override this bean in your application context to customise TLS settings, proxy
         * configuration, or connection pool behaviour.
         *
         * @param properties A2A properties containing the global connect timeout
         * @return the shared {@code A2AHttpClient}
         */
        @Bean
        @ConditionalOnMissingBean
        public A2AHttpClient a2aHttpClient(final AgentClientProperties properties) {
            return new A2AHttpClient(properties.getConnectTimeout());
        }

        @Bean
        public AgentClientRegistry agentClientRegistry(
                final AgentClientProperties properties,
                final List<A2AAuthContributor> authContributors,
                final A2AHttpClient a2aHttpClient,
                final ObjectMapper objectMapper) {

            Map<String, AgentClient> clientMap = new LinkedHashMap<>();
            properties.getClients().forEach((name, config) -> {
                AgentClient client = new AgentClient(
                        name,
                        config.getUrl(),
                        config.getBasePath(),
                        config.getResponseTimeout(),
                        authContributors,
                        a2aHttpClient,
                        objectMapper);
                clientMap.put(name, client);
                log.debug("Registered A2A client '{}' → {}{}", name, config.getUrl(), config.getBasePath());
            });
            return new AgentClientRegistry(clientMap);
        }
    }
}
