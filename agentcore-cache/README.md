# agentcore-cache

**Pluggable caching abstractions — Caffeine for single-node, Redis for distributed deployments.**

This module provides the `AgentCache` interface and two concrete implementations, plus a tool-call deduplication cache that prevents redundant LLM tool executions across concurrent requests.

## What's inside

| Package   | Contents                                                                                          |
|-----------|---------------------------------------------------------------------------------------------------|
| `cache/`  | `AgentCache`, `InMemoryAgentCache`, `RedisAgentCache`, `ToolDedupCache`, `LocalToolDedupCache`, `RedisToolDedupCache`, `DefaultToolResultCache`, `ToolResultCache` |

## Dependency

```xml
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-cache</artifactId>
</dependency>
```

## AgentCache

The core caching interface:

```java
public interface AgentCache {
    void put(String key, Object value);
    Optional<Object> get(String key);
    void evict(String key);
    void evictByPrefix(String prefix);
    long size();
}
```

## Configuration

### In-memory (default)

```yaml
agent:
  cache:
    type: inmemory
    ttl: 30m
    inmemory:
      max-entries: 10000
      eviction-policy: LRU   # LRU | LFU | FIFO
```

### Redis (production / multi-pod)

```yaml
agent:
  cache:
    type: redis
    ttl: 30m
    key-prefix: "myapp:agent:cache:"
    redis:
      host: ${REDIS_HOST}
      port: 6379
      password: ${REDIS_PASSWORD}
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 2
```

When `type: redis` is active, `RedisToolDedupCache` is automatically wired. This prevents concurrent identical tool calls across pods from executing more than once — critical at scale-out.

## ToolDedupCache

Deduplication cache with key format `sessionId::toolName::inputHash`. A result cached by one pod is visible to all pods (Redis mode) or within the same pod (in-memory mode):

```java
Optional<String> cached = dedupCache.getResult(sessionId, toolName, inputHash);
if (cached.isEmpty()) {
    String result = executeTool();
    dedupCache.putResult(sessionId, toolName, inputHash, result);
}
```

## Custom cache backend

```java
@Bean
public AgentCache myCustomCache() {
    return new MyDynamoDbCache();
}
```

## Module dependencies

```
agentcore-cache
  └── agentcore-core
  └── spring-boot-starter-cache (Caffeine)
  └── spring-boot-starter-data-redis (optional)
```

See the [root README](../README.md) for full documentation.
