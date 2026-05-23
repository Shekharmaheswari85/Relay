/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.relay.a2a.AgentCardController;
import io.relay.a2a.AgentClientRegistry;
import io.relay.advisor.ConfirmationGateAdvisor;
import io.relay.aspect.DefaultMcpCallInterceptor;
import io.relay.rag.AgentRetriever;
import io.relay.rag.RagAdvisor;
import io.relay.scheduler.BaseSessionExpiryScheduler;
import io.relay.scheduler.DefaultSessionExpiryScheduler;
import io.relay.cache.AgentCache;
import io.relay.cache.DefaultToolResultCache;
import io.relay.cache.InMemoryAgentCache;
import io.relay.cache.LocalToolDedupCache;
import io.relay.cache.RedisAgentCache;
import io.relay.cache.RedisToolDedupCache;
import io.relay.cache.ToolDedupCache;
import io.relay.cache.ToolResultCache;
import io.relay.executor.AgentRegistry;
import io.relay.filter.McpSseAuthFilter;
import io.relay.memory.AgentMemoryManager;
import io.relay.memory.InMemoryAgentMemoryManager;
import io.relay.memory.entity.EntityMemoryStore;
import io.relay.memory.entity.InMemoryEntityMemoryStore;
import io.relay.memory.persona.InMemoryPersonaStore;
import io.relay.memory.persona.PersonaStore;
import io.relay.model.AgentToolResultCacheDO;
import io.relay.observability.AgentObservabilityService;
import io.relay.prompt.ClasspathPromptRepository;
import io.relay.prompt.PromptLoader;
import io.relay.repository.AgentToolResultCacheRepository;
import io.relay.session.SessionContextManager;
import io.relay.store.AgentAuditLogStore;
import io.relay.store.AgentSessionStore;
import io.relay.store.JpaAgentAuditLogStore;
import io.relay.store.JpaAgentSessionStore;
import io.relay.store.JpaToolResultCacheStore;
import io.relay.store.ToolResultCacheStore;
import io.relay.stream.SseStreamHandler;
import io.relay.stream.ToolProgressPublisher;
import io.relay.summary.DefaultSummaryPromptProvider;
import io.relay.thread.VirtualThreadTaskExecutorUtil;
import io.relay.tool.DefaultToolTierRegistry;
import io.relay.tool.ToolExecutionSupport;
import io.relay.tool.ToolTierRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for Relay.
 *
 * <p>Automatically configures:
 * <ul>
 *   <li>Cache backend based on {@code agent.cache.type}</li>
 *   <li>Virtual thread executor for LLM and pipeline operations</li>
 *   <li>Tool result cache</li>
 *   <li>Prompt loader and tool tier registry</li>
 * </ul>
 *
 * <p>Session context propagation is handled by {@link io.relay.session.SessionContextHolder},
 * which uses a plain {@code ThreadLocal}. No Reactor context propagation hooks are required
 * because the entire agent turn runs on a single virtual thread end-to-end.
 */
@AutoConfiguration
@EnableConfigurationProperties({RelayProperties.class, AgentLlmProperties.class})
@Import({
        AgentCardController.class,
        AgentClientRegistry.AutoConfig.class,
        AgentRegistry.class,
        AgentObservabilityService.class,
        ClasspathPromptRepository.class,
        ConfirmationGateAdvisor.class,
        DefaultMcpCallInterceptor.class,
        DefaultSummaryPromptProvider.class,
        LocalToolDedupCache.class,
        McpSseAuthFilter.class,
        SessionContextManager.class,
        SseStreamHandler.class,
        ToolExecutionSupport.class,
        ToolProgressPublisher.class,
        VirtualThreadTaskExecutorUtil.class
})
@RequiredArgsConstructor
@Slf4j
public class RelayAutoConfiguration {

    private final RelayProperties properties;

    // ─── Cache Beans ──────────────────────────────────────────────────────────

    /**
     * In-memory agent cache (default when {@code agent.cache.type} is {@code inmemory}
     * or not set).
     *
     * <p>Backed by Caffeine. Configurable via {@code agent.cache.inmemory.max-entries}
     * and {@code agent.cache.ttl}.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used for value serialization
     * @return an {@link InMemoryAgentCache} configured from {@link RelayProperties}
     */
    @Bean
    @ConditionalOnProperty(name = "relay.cache.type", havingValue = "inmemory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentCache.class)
    public AgentCache inMemoryAgentCache(final ObjectMapper objectMapper) {
        RelayProperties.CacheProperties cacheProps = properties.getCache();
        return new InMemoryAgentCache(objectMapper, cacheProps.getInmemory().getMaxEntries(), cacheProps.getTtl());
    }

    /**
     * Redis-backed agent cache (activated when {@code agent.cache.type=redis}).
     *
     * <p>Requires {@code spring-boot-starter-data-redis} on the classpath and a
     * configured {@link RedisConnectionFactory}.
     *
     * @param stringRedisTemplate the Spring Data Redis template for string operations
     * @param objectMapper        the Jackson {@link ObjectMapper} used for value serialization
     * @return a {@link RedisAgentCache} configured from {@link RelayProperties}
     */
    @Bean
    @ConditionalOnProperty(name = "relay.cache.type", havingValue = "redis")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(AgentCache.class)
    public AgentCache redisAgentCache(final StringRedisTemplate stringRedisTemplate,
                                      final ObjectMapper objectMapper) {
        RelayProperties.CacheProperties cacheProps = properties.getCache();
        return new RedisAgentCache(stringRedisTemplate, objectMapper, cacheProps.getTtl(), cacheProps.getKeyPrefix());
    }

    /**
     * Redis-backed tool deduplication cache.
     *
     * <p>Provides cross-pod dedup for horizontally-scaled deployments. Automatically
     * activated when the cache type is {@code redis}.
     *
     * @param connectionFactory the Redis connection factory
     * @return a {@link RedisToolDedupCache} backed by a byte-array {@link RedisTemplate}
     */
    @Bean
    @ConditionalOnProperty(name = "relay.cache.type", havingValue = "redis")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(ToolDedupCache.class)
    public ToolDedupCache redisToolDedupCache(final RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return new RedisToolDedupCache(template);
    }

    /**
     * Tool result cache wrapping the configured {@link AgentCache}.
     *
     * <p>When {@code agent.cache.tool-ttl} is set, tool result entries use that dedicated
     * TTL instead of the global {@code agent.cache.ttl}. This is useful when tool outputs
     * are short-lived (e.g., live inventory data) while other cache entries should persist
     * longer (e.g., persona data).
     *
     * @param agentCache the backing agent cache
     * @return a {@link DefaultToolResultCache} delegating to {@code agentCache}
     */
    @Bean
    @ConditionalOnMissingBean(ToolResultCache.class)
    public ToolResultCache toolResultCache(final AgentCache agentCache) {
        java.time.Duration toolTtl = properties.getCache().getToolTtl();
        if (toolTtl != null) {
            log.info("Tool result cache using dedicated TTL: {}", toolTtl);
        }
        return new DefaultToolResultCache(agentCache, toolTtl);
    }

    // ─── Prompt / Tool Tier Beans ─────────────────────────────────────────────

    /**
     * Default system-prompt provider that loads prompt text from classpath resources
     * declared in {@link AgentLlmProperties#systemPrompts}.
     *
     * <p>If the agent name is absent or blank, the {@code "default"} key is used.
     * If no path is configured for the requested agent and the default is also absent,
     * an empty string is returned (resulting in no system prompt — use with care).
     *
     * @param llmProperties the LLM properties containing per-agent prompt file paths
     * @return an {@link AgentSystemPromptProvider} backed by classpath resource loading
     */
    @Bean
    @ConditionalOnMissingBean(AgentSystemPromptProvider.class)
    public AgentSystemPromptProvider agentSystemPromptProvider(final AgentLlmProperties llmProperties) {
        return agentName -> {
            String resolvedAgent =
                    (agentName == null || agentName.isBlank()) ? AgentSystemPromptProvider.DEFAULT_AGENT : agentName;
            Map<String, String> prompts =
                    llmProperties.getSystemPrompts() != null ? llmProperties.getSystemPrompts() : Map.of();
            String path = prompts.get(resolvedAgent);
            if ((path == null || path.isBlank()) && !AgentSystemPromptProvider.DEFAULT_AGENT.equals(resolvedAgent)) {
                path = prompts.get(AgentSystemPromptProvider.DEFAULT_AGENT);
            }
            if (path == null || path.isBlank()) {
                return "";
            }
            return PromptLoader.load(stripClasspathPrefix(path));
        };
    }

    /**
     * Default tool tier registry that discovers {@link io.relay.tool.AgentTool} beans
     * from the Spring {@link ApplicationContext}.
     *
     * @param applicationContext the Spring application context used for bean discovery
     * @return a {@link DefaultToolTierRegistry} backed by the application context
     */
    @Bean
    @ConditionalOnMissingBean(ToolTierRegistry.class)
    public ToolTierRegistry toolTierRegistry(final ApplicationContext applicationContext) {
        return new DefaultToolTierRegistry(applicationContext);
    }

    // ─── Memory Beans ─────────────────────────────────────────────────────────

    /**
     * In-memory agent memory manager — the default Memory Manager when no custom
     * {@link AgentMemoryManager} bean is defined.
     *
     * <p>Uses a {@link java.util.concurrent.ConcurrentHashMap} for storage. Suitable for
     * development and single-node deployments. Override by providing your own
     * {@code AgentMemoryManager} bean.
     *
     * @return a new {@link InMemoryAgentMemoryManager}
     */
    @Bean
    @ConditionalOnMissingBean(AgentMemoryManager.class)
    public AgentMemoryManager inMemoryAgentMemoryManager() {
        return new InMemoryAgentMemoryManager();
    }

    /**
     * In-memory entity memory store — the default {@link EntityMemoryStore} when no custom
     * bean is defined.
     *
     * @return a new {@link InMemoryEntityMemoryStore}
     */
    @Bean
    @ConditionalOnMissingBean(EntityMemoryStore.class)
    public EntityMemoryStore inMemoryEntityMemoryStore() {
        return new InMemoryEntityMemoryStore();
    }

    /**
     * In-memory persona store — the default {@link PersonaStore} when no custom bean is defined.
     *
     * @return a new {@link InMemoryPersonaStore}
     */
    @Bean
    @ConditionalOnMissingBean(PersonaStore.class)
    public PersonaStore inMemoryPersonaStore() {
        return new InMemoryPersonaStore();
    }

    // ─── Virtual Thread Beans ────────────────────────────────────────────────

    /**
     * Virtual-thread-backed {@link ThreadPoolTaskExecutor} for LLM pipeline and async
     * operations.
     *
     * <p>Uses Project Loom virtual threads (Java 21+) so that blocking I/O inside agent
     * tool calls and LLM streaming never pins a platform thread. The executor is injected
     * into {@link io.relay.orchestrator.BaseAgentOrchestrator} and any other component
     * that needs to submit work outside the current request thread.
     *
     * <p>Activated when {@code agent.virtual-threads.enabled} is {@code true} (the default).
     * Override with your own {@code ThreadPoolTaskExecutor} bean named
     * {@code "virtualThreadExecutor"} to customize pool settings.
     *
     * @return a {@link ThreadPoolTaskExecutor} configured to use virtual threads
     */
    @Bean("virtualThreadExecutor")
    @ConditionalOnProperty(name = "agent.virtual-threads.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "virtualThreadExecutor")
    public ThreadPoolTaskExecutor virtualThreadExecutor() {
        log.info("Virtual thread executor enabled for agent pipeline operations");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadFactory(Thread.ofVirtual().factory());
        executor.setThreadNamePrefix("agent-vt-");
        executor.initialize();
        return executor;
    }

    // ─── Observability / Metrics Beans ───────────────────────────────────────

    /**
     * {@link MeterFilter} that injects user-configured common tags into every
     * {@code agent.*} metric, enabling dashboard filtering by environment, service name,
     * region, or any other dimension.
     *
     * <p>Only activated when {@code agent.metrics.enabled=true} (the default) and at least
     * one tag is configured under {@code agent.metrics.common-tags}.
     *
     * <h3>Configuration</h3>
     * <pre>{@code
     * agent:
     *   metrics:
     *     common-tags:
     *       service: order-agent
     *       environment: production
     *       region: us-east-1
     * }</pre>
     *
     * <h3>Prometheus scrape endpoint (add to your application)</h3>
     * <pre>{@code
     * # pom.xml dependency
     * <dependency>
     *   <groupId>io.micrometer</groupId>
     *   <artifactId>micrometer-registry-prometheus</artifactId>
     * </dependency>
     *
     * # application.yml
     * management:
     *   endpoints:
     *     web:
     *       exposure:
     *         include: prometheus,health,info
     *   endpoint:
     *     prometheus:
     *       enabled: true
     * }</pre>
     *
     * @return a {@link MeterFilter} that tags all {@code agent.*} metrics with the configured
     *         common tags; returns an identity filter when no tags are configured
     */
    @Bean
    @ConditionalOnProperty(name = "relay.metrics.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "agentCommonTagsMeterFilter")
    public MeterFilter agentCommonTagsMeterFilter() {
        Map<String, String> rawTags = properties.getMetrics().getCommonTags();
        if (rawTags == null || rawTags.isEmpty()) {
            return new MeterFilter() {};
        }
        List<Tag> tagList = rawTags.entrySet().stream()
                .map(e -> Tag.of(e.getKey(), e.getValue()))
                .toList();
        log.info("Applying {} common tag(s) to all agent.* metrics: {}", tagList.size(), rawTags.keySet());
        return new MeterFilter() {
            @Override
            public Meter.Id map(final Meter.Id id) {
                if (id.getName().startsWith("agent.")) {
                    return id.withTags(tagList);
                }
                return id;
            }
        };
    }

    // ─── RAG Beans ───────────────────────────────────────────────────────────

    /**
     * Default {@link RagAdvisor} that activates automatically when the application
     * declares an {@link AgentRetriever} bean.
     *
     * <p>Retrieves up to 5 documents per request with no minimum score threshold and
     * standard context delimiters. Override by declaring your own {@link RagAdvisor}
     * bean with custom settings via {@link RagAdvisor#builder(AgentRetriever)}.
     *
     * @param retriever the application-supplied retriever; connects the advisor to a
     *                  vector store, search index, or custom corpus
     * @return a {@link RagAdvisor} using default settings
     */
    @Bean
    @ConditionalOnBean(AgentRetriever.class)
    @ConditionalOnMissingBean(RagAdvisor.class)
    public RagAdvisor ragAdvisor(final AgentRetriever retriever) {
        log.info("Auto-configuring RagAdvisor with default settings (maxDocuments=5, minScore=0.0)");
        return RagAdvisor.builder(retriever).build();
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private String stripClasspathPrefix(final String path) {
        if (path != null && path.startsWith("classpath:")) {
            return path.substring("classpath:".length());
        }
        return path;
    }

    // ─── Session Expiry ───────────────────────────────────────────────────────

    /**
     * Nested configuration that registers the default session expiry scheduler only when
     * {@code agent.session.expiry.enabled=true} and the JPA starter is present.
     *
     * <p>{@code @EnableScheduling} is scoped to this class so that Spring's scheduling
     * post-processor is registered only when session expiry is actually needed, avoiding
     * interference with applications that manage their own scheduling configuration.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
    @ConditionalOnProperty(name = "agent.session.expiry.enabled", havingValue = "true")
    @EnableScheduling
    static class SessionExpiryConfiguration {

        /**
         * Registers the {@link DefaultSessionExpiryScheduler} when a
         * {@code BaseAgentSessionRepository} bean is present and no custom
         * {@link BaseSessionExpiryScheduler} has been declared.
         *
         * @param agentSessionRepository the session repository used to query and expire sessions
         * @param properties             the root agent properties carrying expiry configuration
         * @return a scheduler that sweeps for idle sessions on the configured interval
         */
        @Bean
        @ConditionalOnBean(name = "agentSessionRepository")
        @ConditionalOnMissingBean(BaseSessionExpiryScheduler.class)
        public DefaultSessionExpiryScheduler defaultSessionExpiryScheduler(
                final io.relay.repository.BaseAgentSessionRepository<?> agentSessionRepository,
                final RelayProperties properties) {
            long idleHours = properties.getSession().getExpiry().getIdleHours();
            return new DefaultSessionExpiryScheduler(agentSessionRepository, idleHours);
        }
    }

    // ─── JPA Store Beans ─────────────────────────────────────────────────────

    /**
     * Nested configuration that registers JPA-backed store beans only when
     * {@code spring-boot-starter-data-jpa} (i.e. {@code JpaRepository}) is on the classpath.
     *
     * <p>The class-level {@code @ConditionalOnClass} prevents this configuration from being
     * instantiated — and its {@code @Bean} methods from being processed — when JPA is absent,
     * avoiding {@code NoClassDefFoundError} from the JPA-specific types referenced inside.
     *
     * <p>Consumers who use a non-JPA backend (MongoDB, DynamoDB, Redis, etc.) simply do not
     * add {@code spring-boot-starter-data-jpa} and instead declare their own
     * {@code @Primary} store beans — this configuration will be skipped entirely.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")
    static class JpaStoreConfiguration {

        /**
         * Registers the JPA-backed session store when a {@code BaseAgentSessionRepository}
         * bean is present and no custom {@link io.relay.store.AgentSessionStore} has been declared.
         *
         * <p>Consumer modules that extend {@code BaseAgentSession} and create a concrete
         * {@code BaseAgentSessionRepository} sub-interface will trigger this bean automatically.
         */
        @Bean
        @ConditionalOnBean(name = "agentSessionRepository")
        @ConditionalOnMissingBean(AgentSessionStore.class)
        public AgentSessionStore<?> jpaAgentSessionStore(
                final io.relay.repository.BaseAgentSessionRepository<?> repository) {
            return new JpaAgentSessionStore<>(repository);
        }

        /**
         * Registers the JPA-backed audit log store when a {@code BaseAgentAuditLogRepository}
         * bean is present and no custom {@link AgentAuditLogStore} has been declared.
         *
         * <p>Consumer modules that extend {@code BaseAgentAuditLog} and create a concrete
         * {@code BaseAgentAuditLogRepository} sub-interface will trigger this bean automatically.
         */
        @Bean
        @ConditionalOnBean(name = "agentAuditLogRepository")
        @ConditionalOnMissingBean(AgentAuditLogStore.class)
        public AgentAuditLogStore<?> jpaAgentAuditLogStore(
                final io.relay.repository.BaseAgentAuditLogRepository<?> repository) {
            return new JpaAgentAuditLogStore<>(repository);
        }

        /**
         * Registers the JPA-backed tool result cache store using the framework's built-in
         * {@link AgentToolResultCacheDO} entity.
         *
         * <p>Activated automatically when {@link AgentToolResultCacheRepository} is present
         * (which it is whenever {@code spring-boot-starter-data-jpa} is on the classpath and
         * the framework's entity scan is configured). Teams that extend {@code BaseToolResultCache}
         * with a custom entity must declare their own {@link ToolResultCacheStore} bean and
         * annotate it with {@code @Primary}.
         */
        @Bean
        @ConditionalOnBean(AgentToolResultCacheRepository.class)
        @ConditionalOnMissingBean(ToolResultCacheStore.class)
        public ToolResultCacheStore<AgentToolResultCacheDO> jpaToolResultCacheStore(
                final AgentToolResultCacheRepository repository) {
            return new JpaToolResultCacheStore<>(repository);
        }
    }
}
