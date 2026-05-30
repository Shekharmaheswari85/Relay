# relay-cache

**Pluggable caching abstractions — Caffeine for single-node, Redis for distributed deployments.**

This module provides the `AgentCache` interface and two concrete implementations, plus a tool-result deduplication cache that prevents redundant LLM tool executions across concurrent requests. Tool results support a **dedicated TTL** independent of the global cache TTL.

## What's inside

| Package  | Contents                                                                                                                                                           |
|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cache/` | `AgentCache`, `InMemoryAgentCache`, `RedisAgentCache`, `ToolDedupCache`, `LocalToolDedupCache`, `RedisToolDedupCache`, `DefaultToolResultCache`, `ToolResultCache` |

## Dependency

```xml
<dependency>
    <groupId>io.github.shekharmaheswari85</groupId>
    <artifactId>relay-cache</artifactId>
</dependency>
```

## AgentCache interface

```java
public interface AgentCache {
    <T> Optional<T> get(String key, Class<T> type);

    void put(String key, Object value);               // uses global TTL
    void put(String key, Object value, Duration ttl); // custom TTL per entry

    void evict(String key);
    int evictByPattern(String pattern);               // glob (Redis) / prefix (in-memory)

    boolean isDistributed();
    CacheStats getStats();
}
```

## Configuration

### In-memory (default)

```yaml
relay:
  cache:
    type: inmemory
    ttl: 30m                  # global entry TTL
    tool-ttl: 5m              # tool results expire faster (optional)
    inmemory:
      max-entries: 10000
      eviction-policy: LRU    # LRU | LFU | FIFO
```

### Redis (production / multi-pod)

```yaml
relay:
  cache:
    type: redis
    ttl: 30m
    tool-ttl: 10m             # tool results expire on a separate schedule
    key-prefix: "myapp:agent:"
    redis:
      host: ${REDIS_HOST}
      port: 6379
      password: ${REDIS_PASSWORD:}
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 2
```

When `type: redis`, `RedisToolDedupCache` is also wired automatically. This prevents concurrent identical tool calls across pods from executing more than once.

## Tool result cache TTL

`DefaultToolResultCache` wraps `AgentCache` and adds session-scoped semantics. When `relay.cache.tool-ttl` is set, tool results use that duration; otherwise they inherit the global `ttl`:

```yaml
relay:
  cache:
    ttl: 30m        # persona data, session metadata, etc.
    tool-ttl: 5m    # tool results (live inventory, pricing) expire sooner
```

Programmatic override:

```java
@Bean
public ToolResultCache myToolCache(AgentCache agentCache) {
    return new DefaultToolResultCache(agentCache, Duration.ofMinutes(2));
}
```

## ToolDedupCache

Prevents duplicate tool calls within a session (or across pods in Redis mode). Key format: `sessionId::toolName::inputHash`.

```java
Optional<String> cached = dedupCache.getResult(sessionId, toolName, inputHash);
if (cached.isEmpty()) {
    String result = expensiveToolCall();
    dedupCache.putResult(sessionId, toolName, inputHash, result);
}
```

## Cache statistics

```java
AgentCache.CacheStats stats = cache.getStats();
System.out.printf("hit-rate=%.1f%% hits=%d misses=%d size=%d%n",
        stats.hitRate() * 100, stats.hits(), stats.misses(), stats.size());
```

## Custom cache backend

```java
@Bean
public AgentCache myCustomCache() {
    return new MyDynamoDbCache();  // implements AgentCache
}
```

## Module dependencies

```
relay-cache
  ├── relay-core
  ├── com.github.ben-manes.caffeine:caffeine
  └── spring-boot-starter-data-redis (optional)
```

See the [root README](../README.md) for full documentation.
