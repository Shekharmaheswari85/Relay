# relay-store

**Persistence layer — store interfaces, JPA adapters, Spring Data repositories, and session checkpoints.**

This module defines the storage SPI that the rest of Relay writes against. Swap in any backend — JPA, Redis, MongoDB, DynamoDB — without touching framework code.

## What's inside

| Package       | Contents                                                                                                                              |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `store/`      | `AgentSessionStore`, `AgentAuditLogStore`, `ToolResultCacheStore`, and their JPA implementations                                      |
| `repository/` | `BaseAgentSessionRepository`, `BaseAgentAuditLogRepository`, `BaseToolResultCacheRepository`                                          |
| `checkpoint/` | `BaseCheckpointManager`, `BaseChatHistoryManager`, `BaseConfirmationManager`, `BaseSessionStateManager`, `BaseToolResultCacheService` |

## Dependency

```xml
<dependency>
    <groupId>io.github.shekharmaheswari85</groupId>
    <artifactId>relay-store</artifactId>
</dependency>
```

Add JPA to activate the built-in JPA store implementations:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

## Store interfaces

```java
public interface AgentSessionStore<S extends BaseAgentSession> {
    S save(S session);
    Optional<S> findBySessionId(String sessionId);
    List<S> findByAgentId(String agentId);
    List<S> findByStatus(String status);
    void deleteBySessionId(String sessionId);
}

public interface AgentAuditLogStore<A extends BaseAuditLog> {
    A save(A entry);
    List<A> findBySessionId(String sessionId);
    List<A> findBySessionIdAndEventType(String sessionId, String eventType);
}

public interface ToolResultCacheStore<C extends BaseToolResultCache> {
    C save(C entry);
    Optional<C> findBySessionIdAndToolNameAndInputHash(String sessionId, String toolName, String inputHash);
    long countBySessionId(String sessionId);
    void deleteBySessionId(String sessionId);
}
```

## JPA activation (three steps)

```java
// 1. Extend the base session entity
@Entity
@Table(name = "my_sessions")
@Data @SuperBuilder @NoArgsConstructor @EqualsAndHashCode(callSuper = true)
public class MySession extends BaseAgentSession {
    @Column(name = "customer_id")
    private String customerId;
}

// 2. Extend the base repository
@Repository
public interface MySessionRepository extends BaseAgentSessionRepository<MySession> { }

// 3. Done — Relay auto-configures JpaAgentSessionStore<MySession>
```

The same pattern applies to `BaseAgentAuditLog` / `BaseAgentAuditLogRepository` and `BaseToolResultCache` / `BaseToolResultCacheRepository`.

## Custom backend

```java
@Bean
public AgentSessionStore<MySession> mongoSessionStore(MongoTemplate mongo) {
    return new MongoAgentSessionStore<>(mongo, MySession.class);
}
```

## Checkpoint system

`BaseCheckpointManager` persists workflow checkpoints so a paused session can resume from the exact step it stopped at — even across pod restarts:

```java
// Save checkpoint
checkpointManager.saveCheckpoint(sessionId, "STEP_VALIDATE");

// Resume
String lastCheckpoint = checkpointManager.getLastCheckpoint(sessionId);
service.resumeFromCheckpoint(sessionId, lastCheckpoint);
```

## Test utilities

In-memory implementations ship in `src/test/java/io/relay/test` for use in your unit and integration tests:

```java
@Autowired InMemoryAgentSessionStore<MySession> sessionStore;
@Autowired InMemoryAgentAuditLogStore<MyAuditLog> auditLogStore;
@Autowired InMemoryToolResultCacheStore<AgentToolResultCacheDO> cacheStore;
```

All expose test helpers: `findAll()`, `size()`, `isEmpty()`, `clear()`.

## Module dependencies

```
relay-store
  └── relay-core
  └── relay-cache
  └── spring-boot-starter-data-jpa (optional)
  └── jakarta.persistence-api
```

See the [root README](../README.md) for full documentation.
