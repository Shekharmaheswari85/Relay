/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Root {@link org.springframework.boot.context.properties.ConfigurationProperties} binding
 * for the {@code agent.*} namespace, covering the cache backend, session identity, and
 * virtual-thread runtime options.
 *
 * <p>These properties are consumed by the beans registered in
 * {@link AgentCoreAutoConfiguration}. Add the following stanza to your
 * {@code application.yml} to customise behaviour:
 *
 * <pre>{@code
 * agent:
 *   cache:
 *     type: redis              # inmemory (default) | redis
 *     ttl: 30m
 *     key-prefix: "myapp:cache:"
 *     inmemory:
 *       max-entries: 10000
 *       eviction-policy: LRU   # LRU | LFU | FIFO
 *     redis:
 *       host: redis-host
 *       port: 6379
 *       password: ${REDIS_PASSWORD:}
 *       pool:
 *         max-active: 10
 *         max-idle: 5
 *         min-idle: 1
 *     maintenance:
 *       enabled: false
 *       cleanup-cron: "0 0 * * * *"
 *       max-entry-age: 24h
 *       stats-interval: 1m
 *   session:
 *     id-prefix: "sess-"
 *     id-length: 12
 *     context-max-size: 1048576   # 1 MB
 *   virtual-threads:
 *     enabled: true               # requires Java 21+
 * }</pre>
 *
 * @see AgentCoreAutoConfiguration
 */
@ConfigurationProperties(prefix = "agent")
@Data
public class AgentCoreProperties {

    // ─── Default value constants ─────────────────────────────────────────────

    private static final int DEFAULT_CACHE_TTL_MINUTES = 30;
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 10_000;
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final int DEFAULT_POOL_MAX_ACTIVE = 10;
    private static final int DEFAULT_POOL_MAX_IDLE = 5;
    private static final int DEFAULT_MAX_ENTRY_AGE_HOURS = 24;
    private static final int DEFAULT_SESSION_ID_LENGTH = 12;
    private static final int DEFAULT_CONTEXT_MAX_SIZE = 1_048_576; // 1MB

    /**
     * Cache backend configuration ({@code agent.cache.*}).
     */
    private CacheProperties cache = new CacheProperties();

    /**
     * Session identity and size configuration ({@code agent.session.*}).
     */
    private SessionProperties session = new SessionProperties();

    /**
     * Virtual-thread runtime configuration ({@code agent.virtual-threads.*}).
     */
    private VirtualThreadProperties virtualThreads = new VirtualThreadProperties();

    /**
     * Cache backend settings ({@code agent.cache.*}).
     */
    @Data
    public static class CacheProperties {

        /**
         * Selects the cache backend implementation.
         * Supported values: {@code inmemory} (default), {@code redis}.
         */
        private String type = "inmemory";

        /**
         * Default time-to-live applied to every cache entry.
         * Accepts standard Spring {@link Duration} notation, e.g., {@code 30m}, {@code 1h}.
         * Default: 30 minutes.
         */
        private Duration ttl = Duration.ofMinutes(DEFAULT_CACHE_TTL_MINUTES);

        /**
         * Prefix prepended to every cache key for namespace isolation.
         * Override when multiple applications share the same Redis instance.
         * Default: {@code "agent:cache:"}.
         */
        private String keyPrefix = "agent:cache:";

        /**
         * Settings specific to the in-memory cache backend ({@code agent.cache.inmemory.*}).
         * Only used when {@code agent.cache.type=inmemory}.
         */
        private InMemoryProperties inmemory = new InMemoryProperties();

        /**
         * Settings specific to the Redis cache backend ({@code agent.cache.redis.*}).
         * Only used when {@code agent.cache.type=redis}.
         */
        private RedisProperties redis = new RedisProperties();

        /**
         * Scheduled cache maintenance settings ({@code agent.cache.maintenance.*}).
         * Disabled by default; enable for automatic eviction of stale entries.
         */
        private MaintenanceProperties maintenance = new MaintenanceProperties();
    }

    /**
     * In-memory cache tuning ({@code agent.cache.inmemory.*}).
     */
    @Data
    public static class InMemoryProperties {

        /**
         * Maximum number of entries the in-memory cache will hold before eviction triggers.
         * Default: 10,000.
         */
        private int maxEntries = DEFAULT_MAX_CACHE_ENTRIES;

        /**
         * Eviction policy applied when the cache reaches {@code maxEntries}.
         * Supported values: {@code LRU} (default), {@code LFU}, {@code FIFO}.
         */
        private String evictionPolicy = "LRU";
    }

    /**
     * Redis connection settings ({@code agent.cache.redis.*}).
     */
    @Data
    public static class RedisProperties {

        /**
         * Redis server hostname. Default: {@code localhost}.
         */
        private String host = "localhost";

        /**
         * Redis server port. Default: {@code 6379}.
         */
        private int port = DEFAULT_REDIS_PORT;

        /**
         * Redis authentication password. Leave blank for unauthenticated connections.
         */
        private String password;

        /**
         * Zero-indexed Redis database number. Default: {@code 0}.
         */
        private int database = 0;

        /**
         * Connection pool settings ({@code agent.cache.redis.pool.*}).
         */
        private PoolProperties pool = new PoolProperties();
    }

    /**
     * Redis connection pool settings ({@code agent.cache.redis.pool.*}).
     */
    @Data
    public static class PoolProperties {

        /**
         * Maximum number of active connections. Default: 10.
         */
        private int maxActive = DEFAULT_POOL_MAX_ACTIVE;

        /**
         * Maximum number of idle connections held in the pool. Default: 5.
         */
        private int maxIdle = DEFAULT_POOL_MAX_IDLE;

        /**
         * Minimum number of idle connections to maintain. Default: 1.
         */
        private int minIdle = 1;
    }

    /**
     * Scheduled cache maintenance settings ({@code agent.cache.maintenance.*}).
     */
    @Data
    public static class MaintenanceProperties {

        /**
         * Enables scheduled cache eviction and stats logging. Default: {@code false}.
         */
        private boolean enabled = false;

        /**
         * Cron expression for the cache cleanup job.
         * Default: {@code "0 0 * * * *"} (top of every hour).
         */
        private String cleanupCron = "0 0 * * * *";

        /**
         * Entries older than this duration are eligible for eviction by the maintenance job.
         * Default: 24 hours.
         */
        private Duration maxEntryAge = Duration.ofHours(DEFAULT_MAX_ENTRY_AGE_HOURS);

        /**
         * How often cache statistics are written to the log.
         * Default: every 1 minute.
         */
        private Duration statsInterval = Duration.ofMinutes(1);
    }

    /**
     * Session identity settings ({@code agent.session.*}).
     */
    @Data
    public static class SessionProperties {

        /**
         * String prepended to every generated session ID for human readability.
         * Default: {@code "sess-"}.
         */
        private String idPrefix = "sess-";

        /**
         * Number of random characters appended after the prefix in generated session IDs.
         * Default: 12.
         */
        private int idLength = DEFAULT_SESSION_ID_LENGTH;

        /**
         * Maximum size in bytes of the serialised context JSON stored per session.
         * Writes that exceed this limit are rejected to prevent unbounded growth.
         * Default: 1,048,576 bytes (1 MB).
         */
        private int contextMaxSize = DEFAULT_CONTEXT_MAX_SIZE;
    }

    /**
     * Virtual-thread execution settings ({@code agent.virtual-threads.*}).
     */
    @Data
    public static class VirtualThreadProperties {

        /**
         * Enables Java virtual threads for tool execution and async scheduling.
         * Requires Java 21 or later. Disable on Java 17 environments.
         * Default: {@code true}.
         */
        private boolean enabled = true;
    }
}
