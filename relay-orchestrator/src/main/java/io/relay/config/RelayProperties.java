/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Root {@link org.springframework.boot.context.properties.ConfigurationProperties} binding
 * for the {@code agent.*} namespace, covering the cache backend, session identity, and
 * virtual-thread runtime options.
 *
 * <p>These properties are consumed by the beans registered in
 * {@link RelayAutoConfiguration}. Add the following stanza to your
 * {@code application.yml} to customise behaviour:
 *
 * <pre>{@code
 * relay:
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
 * @see RelayAutoConfiguration
 */
@ConfigurationProperties(prefix = "relay")
@Data
public class RelayProperties {

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
     * Cache backend configuration ({@code relay.cache.*}).
     */
    private CacheProperties cache = new CacheProperties();

    /**
     * Session identity and size configuration ({@code relay.session.*}).
     */
    private SessionProperties session = new SessionProperties();

    /**
     * Virtual-thread runtime configuration ({@code relay.virtual-threads.*}).
     */
    private VirtualThreadProperties virtualThreads = new VirtualThreadProperties();

    /**
     * Metrics and observability configuration ({@code relay.metrics.*}).
     */
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * Cache backend settings ({@code relay.cache.*}).
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
         * Dedicated time-to-live for tool result cache entries ({@code relay.cache.tool-ttl}).
         * When set, tool results expire after this duration regardless of the global
         * {@link #ttl}. When absent ({@code null}), tool results inherit the global TTL.
         * Accepts standard Spring {@link Duration} notation, e.g., {@code 10m}, {@code 2h}.
         * Default: {@code null} (inherit global TTL).
         */
        private Duration toolTtl;

        /**
         * Settings specific to the in-memory cache backend ({@code relay.cache.inmemory.*}).
         * Only used when {@code relay.cache.type=inmemory}.
         */
        private InMemoryProperties inmemory = new InMemoryProperties();

        /**
         * Settings specific to the Redis cache backend ({@code relay.cache.redis.*}).
         * Only used when {@code relay.cache.type=redis}.
         */
        private RedisProperties redis = new RedisProperties();

        /**
         * Scheduled cache maintenance settings ({@code relay.cache.maintenance.*}).
         * Disabled by default; enable for automatic eviction of stale entries.
         */
        private MaintenanceProperties maintenance = new MaintenanceProperties();
    }

    /**
     * In-memory cache tuning ({@code relay.cache.inmemory.*}).
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
     * Redis connection settings ({@code relay.cache.redis.*}).
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
         * Connection pool settings ({@code relay.cache.redis.pool.*}).
         */
        private PoolProperties pool = new PoolProperties();
    }

    /**
     * Redis connection pool settings ({@code relay.cache.redis.pool.*}).
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
     * Scheduled cache maintenance settings ({@code relay.cache.maintenance.*}).
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
     * Session identity settings ({@code relay.session.*}).
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

        /**
         * Automatic session expiry settings ({@code relay.session.expiry.*}).
         * Disabled by default; set {@code enabled: true} to activate the scheduler.
         */
        private ExpiryProperties expiry = new ExpiryProperties();
    }

    /**
     * Session expiry scheduler settings ({@code relay.session.expiry.*}).
     *
     * <p>When enabled, sessions that have not received any activity for longer than
     * {@link #idleHours} are automatically transitioned to
     * {@link io.relay.session.SessionStatus#EXPIRED} by
     * {@link io.relay.scheduler.DefaultSessionExpiryScheduler}.
     *
     * <pre>{@code
     * relay:
     *   session:
     *     expiry:
     *       enabled: true
     *       idle-hours: 24
     *       check-interval-ms: 3600000
     * }</pre>
     */
    @Data
    public static class ExpiryProperties {

        /**
         * Enables the automatic session expiry scheduler.
         * Requires {@code spring-boot-starter-data-jpa} on the classpath.
         * Default: {@code false}.
         */
        private boolean enabled = false;

        /**
         * Number of hours of inactivity (no {@code updatedAt} change) after which a
         * session is considered stale and eligible for expiry.
         * Default: 24 hours.
         */
        private long idleHours = 24;

        /**
         * How often (in milliseconds) the expiry sweep runs. Measured as a fixed delay
         * from the completion of the previous run, so overlapping sweeps never occur.
         * Default: 3,600,000 ms (1 hour).
         */
        private long checkIntervalMs = 3_600_000;
    }

    /**
     * Virtual-thread execution settings ({@code relay.virtual-threads.*}).
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

    /**
     * Metrics and observability settings ({@code relay.metrics.*}).
     *
     * <p>Controls whether agent metrics are published and allows injecting common tags
     * into every {@code agent.*} metric for dashboard filtering.
     *
     * <pre>{@code
     * relay:
     *   metrics:
     *     enabled: true
     *     common-tags:
     *       service: my-order-agent
     *       environment: production
     *       region: us-east-1
     * }</pre>
     *
     * <h3>Prometheus scrape endpoint</h3>
     * <p>Add {@code micrometer-registry-prometheus} to your application POM and expose
     * the actuator Prometheus endpoint:
     * <pre>{@code
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
     * <h3>DataDog export</h3>
     * <p>Add {@code micrometer-registry-datadog} and configure:
     * <pre>{@code
     * management:
     *   datadog:
     *     metrics:
     *       export:
     *         api-key: ${DATADOG_API_KEY}
     *         application-key: ${DATADOG_APP_KEY}
     *         uri: https://api.datadoghq.com
     * }</pre>
     */
    @Data
    public static class MetricsProperties {

        /**
         * Enables or disables agent metric recording.
         * When {@code false}, the {@code MeterFilter} that applies common tags is not
         * registered. Individual metrics are still defined but carry no custom tags.
         * Default: {@code true}.
         */
        private boolean enabled = true;

        /**
         * Key-value pairs added as tags to every {@code agent.*} metric.
         * Use to distinguish deployments in shared dashboards (e.g., by environment,
         * service name, or region).
         * Default: empty (no extra tags).
         */
        private Map<String, String> commonTags = new LinkedHashMap<>();
    }
}
