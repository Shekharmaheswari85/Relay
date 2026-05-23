/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.config;

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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.a2a.AgentCardController;
import io.agentcore.a2a.AgentClientRegistry;
import io.agentcore.advisor.ConfirmationGateAdvisor;
import io.agentcore.aspect.DefaultMcpCallInterceptor;
import io.agentcore.cache.AgentCache;
import io.agentcore.cache.DefaultToolResultCache;
import io.agentcore.cache.InMemoryAgentCache;
import io.agentcore.cache.LocalToolDedupCache;
import io.agentcore.cache.RedisAgentCache;
import io.agentcore.cache.RedisToolDedupCache;
import io.agentcore.cache.ToolDedupCache;
import io.agentcore.cache.ToolResultCache;
import io.agentcore.executor.AgentRegistry;
import io.agentcore.filter.McpSseAuthFilter;
import io.agentcore.memory.AgentMemoryManager;
import io.agentcore.memory.InMemoryAgentMemoryManager;
import io.agentcore.memory.entity.EntityMemoryStore;
import io.agentcore.memory.entity.InMemoryEntityMemoryStore;
import io.agentcore.memory.persona.InMemoryPersonaStore;
import io.agentcore.memory.persona.PersonaStore;
import io.agentcore.model.AgentToolResultCacheDO;
import io.agentcore.observability.AgentObservabilityService;
import io.agentcore.prompt.ClasspathPromptRepository;
import io.agentcore.prompt.PromptLoader;
import io.agentcore.repository.AgentToolResultCacheRepository;
import io.agentcore.session.SessionContextManager;
import io.agentcore.store.AgentAuditLogStore;
import io.agentcore.store.AgentSessionStore;
import io.agentcore.store.JpaAgentAuditLogStore;
import io.agentcore.store.JpaAgentSessionStore;
import io.agentcore.store.JpaToolResultCacheStore;
import io.agentcore.store.ToolResultCacheStore;
import io.agentcore.stream.SseStreamHandler;
import io.agentcore.stream.ToolProgressPublisher;
import io.agentcore.summary.DefaultSummaryPromptProvider;
import io.agentcore.thread.VirtualThreadTaskExecutorUtil;
import io.agentcore.tool.DefaultToolTierRegistry;
import io.agentcore.tool.ToolExecutionSupport;
import io.agentcore.tool.ToolTierRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for agentcore.
 *
 * <p>Automatically configures:
 * <ul>
 *   <li>Cache backend based on {@code agent.cache.type}</li>
 *   <li>Virtual thread executor for LLM and pipeline operations</li>
 *   <li>Tool result cache</li>
 *   <li>Prompt loader and tool tier registry</li>
 * </ul>
 *
 * <p>Session context propagation is handled by {@link io.agentcore.session.SessionContextHolder},
 * which uses a plain {@code ThreadLocal}. No Reactor context propagation hooks are required
 * because the entire agent turn runs on a single virtual thread end-to-end.
 */
@AutoConfiguration
@EnableConfigurationProperties({AgentCoreProperties.class, AgentLlmProperties.class})
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
public class AgentCoreAutoConfiguration {

    private final AgentCoreProperties properties;

    // ─── Cache Beans ──────────────────────────────────────────────────────────

    /**
     * In-memory agent cache (default when {@code agent.cache.type} is {@code inmemory}
     * or not set).
     *
     * <p>Backed by Caffeine. Configurable via {@code agent.cache.inmemory.max-entries}
     * and {@code agent.cache.ttl}.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used for value serialization
     * @return an {@link InMemoryAgentCache} configured from {@link AgentCoreProperties}
     */
    @Bean
    @ConditionalOnProperty(name = "agent.cache.type", havingValue = "inmemory", matchIfMissing = true)
    @ConditionalOnMissingBean(AgentCache.class)
    public AgentCache inMemoryAgentCache(final ObjectMapper objectMapper) {
        AgentCoreProperties.CacheProperties cacheProps = properties.getCache();
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
     * @return a {@link RedisAgentCache} configured from {@link AgentCoreProperties}
     */
    @Bean
    @ConditionalOnProperty(name = "agent.cache.type", havingValue = "redis")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(AgentCache.class)
    public AgentCache redisAgentCache(final StringRedisTemplate stringRedisTemplate,
                                      final ObjectMapper objectMapper) {
        AgentCoreProperties.CacheProperties cacheProps = properties.getCache();
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
    @ConditionalOnProperty(name = "agent.cache.type", havingValue = "redis")
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
     * @param agentCache the backing agent cache
     * @return a {@link DefaultToolResultCache} delegating to {@code agentCache}
     */
    @Bean
    @ConditionalOnMissingBean(ToolResultCache.class)
    public ToolResultCache toolResultCache(final AgentCache agentCache) {
        return new DefaultToolResultCache(agentCache);
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
     * Default tool tier registry that discovers {@link io.agentcore.tool.AgentTool} beans
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
     * into {@link io.agentcore.orchestrator.BaseAgentOrchestrator} and any other component
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

    // ─── Private helpers ─────────────────────────────────────────────────────

    private String stripClasspathPrefix(final String path) {
        if (path != null && path.startsWith("classpath:")) {
            return path.substring("classpath:".length());
        }
        return path;
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
         * bean is present and no custom {@link io.agentcore.store.AgentSessionStore} has been declared.
         *
         * <p>Consumer modules that extend {@code BaseAgentSession} and create a concrete
         * {@code BaseAgentSessionRepository} sub-interface will trigger this bean automatically.
         */
        @Bean
        @ConditionalOnBean(name = "agentSessionRepository")
        @ConditionalOnMissingBean(AgentSessionStore.class)
        public AgentSessionStore<?> jpaAgentSessionStore(
                final io.agentcore.repository.BaseAgentSessionRepository<?> repository) {
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
                final io.agentcore.repository.BaseAgentAuditLogRepository<?> repository) {
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
