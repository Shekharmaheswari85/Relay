# agentcore-core

**Domain model, session context, DTOs, and exceptions — the foundation every other Agent Core module builds on.**

This module has no Spring AI or database dependencies. It contains only the domain types and session abstractions that the rest of the stack shares.

## What's inside

| Package      | Contents                                                                                 |
|--------------|------------------------------------------------------------------------------------------|
| `model/`     | `BaseAgentSession`, `BaseAuditLog`, `BaseAgentAuditLog`, `BaseToolResultCache`           |
| `session/`   | `SessionContextHolder`, `SessionContextManager`, `SessionStatus`, `ActiveAgentHolder`   |
| `dto/`       | All REST request/response types (`BaseMessageRequest`, `BaseCreateSessionRequest`, etc.) |
| `exception/` | `DirectToolCallBlockedException`, `McpDirectMutationBlockedException`                   |

## Dependency

```xml
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-core</artifactId>
</dependency>
```

## Key types

### BaseAgentSession

The `@MappedSuperclass` you extend to add domain-specific fields to your session entity:

```java
@Entity
@Table(name = "my_agent_sessions")
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MyAgentSession extends BaseAgentSession {

    @Column(name = "customer_id")
    private String customerId;

    @Override
    public String getDomainContext() {
        return "customer=" + customerId;
    }
}
```

Inherited columns:

| Column             | Type        | Purpose                                      |
|--------------------|-------------|----------------------------------------------|
| `session_id`       | `VARCHAR`   | External URL-safe identifier                 |
| `agent_id`         | `VARCHAR`   | Which agent owns the session                 |
| `current_step`     | `VARCHAR`   | Workflow step enum value                     |
| `status`           | `VARCHAR`   | `ACTIVE · PAUSED · COMPLETED · FAILED`       |
| `context_json`     | `CLOB`      | Full conversation history                    |
| `last_checkpoint`  | `VARCHAR`   | Step name to resume from                     |
| `active_sub_agent` | `VARCHAR`   | Currently handling sub-agent                 |
| `auto_approve`     | `VARCHAR`   | JSON array of pre-approved tool names        |
| `tenant_id`        | `VARCHAR`   | Multi-tenant isolation key                   |
| `created_at`       | `TIMESTAMP` | Immutable creation time                      |
| `updated_at`       | `TIMESTAMP` | Auto-refreshed on every save                 |

### SessionContextHolder

Thread-local (virtual-thread-safe) holder for the active session and agent name. Set by the framework at the start of each request:

```java
String sessionId = SessionContextHolder.getSessionId();
String agentName = SessionContextHolder.getAgentName();
```

### SessionStatus

```java
public enum SessionStatus {
    ACTIVE, PAUSED, COMPLETED, FAILED, EXPIRED
}
```

## Module dependencies

```
agentcore-core
  └── spring-boot-starter
  └── jakarta.persistence-api
  └── jackson-databind
  └── lombok
  └── jspecify
```

See the [root README](../README.md) for full documentation.
