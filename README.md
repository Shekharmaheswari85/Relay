<div align="center">

# Agent Core

**Production-ready, extensible foundation for building AI agents on Spring AI**

[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4.1-6db33f?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.0-6db33f)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-private-lightgrey)](LICENSE)

Agent Core is a Spring Boot library that gives you the batteries-included scaffolding to build, run, and scale production AI agents — without writing the same boilerplate every time.

[Quick Start](#quick-start) · [Modules](#modules) · [Architecture](#architecture) · [Features](#features) · [Configuration](#configuration-reference) · [Contributing](#contributing)

</div>

---

## Why Agent Core?

Building an AI agent from scratch means wiring together LLM calls, session state, audit logs, memory, tool caching, rate limiting, circuit breakers, SSE streaming, and multi-agent coordination. That's weeks of infrastructure work before you write a single line of business logic.

Agent Core gives you all of that — tested, observable, and extensible — as a set of focused Maven modules. You focus on what your agent *does*; Agent Core handles how it *runs*.

| Capability                   | What you get                                                                                               |
|------------------------------|------------------------------------------------------------------------------------------------------------|
| 🧠 5-tier memory system      | Entity facts · Persona profiles · Workflow memory · Knowledge base · Session context                       |
| 🔗 8 built-in advisors       | Rate limiting · Circuit breaker · RAG · Memory injection · Confirmation gate · Audit · Thinking · Fallback |
| 🤝 Agent-to-Agent (A2A)      | HTTP-native protocol for multi-agent fan-out with SSE streaming                                            |
| 🗄️ Swappable store backends | In-memory (dev) → JPA → Redis → your own, zero code changes                                                |
| 📊 Full observability        | Micrometer metrics · MDC logging · distributed tracing · SSE events                                        |
| 🛡️ Production resilience    | Tool-result caching · session checkpoints · mutation confirmation gates                                    |
| ⚡ Java 21 virtual threads    | Non-blocking I/O throughout; no reactive boilerplate                                                       |
| 🧩 Open SPI                  | Plug in any LLM provider, vector store, auth contributor, retriever, or memory backend                     |

---

## Modules

Agent Core is structured as a multi-module Maven project. Pick only what you need, or pull them all via `agentcore-starter`.

| Module                                               | Artifact ID              | What it contains                                                                          |
|------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------------|
| [agentcore-bom](agentcore-bom/README.md)             | `agentcore-bom`          | Bill of Materials — consistent version alignment across all modules                       |
| [agentcore-core](agentcore-core/README.md)           | `agentcore-core`         | Domain model (`BaseAgentSession`, `BaseAuditLog`), DTOs, session context, exceptions      |
| [agentcore-cache](agentcore-cache/README.md)         | `agentcore-cache`        | Caffeine and Redis caching abstractions, tool-result deduplication cache                  |
| [agentcore-store](agentcore-store/README.md)         | `agentcore-store`        | Store interfaces, JPA adapters, Spring Data repositories, session checkpoints             |
| [agentcore-llm](agentcore-llm/README.md)             | `agentcore-llm`          | ChatClient registry, prompt loading, SSE streaming, tool framework, AOP interceptors      |
| [agentcore-orchestrator](agentcore-orchestrator/README.md) | `agentcore-orchestrator` | A2A protocol, advisors, agent routing, memory, RAG, reasoning strategies, auto-config |
| [agentcore-starter](agentcore-starter/README.md)     | `agentcore-starter`      | Convenience starter — pulls in all modules with a single dependency                       |

### Dependency management (recommended)

Import the BOM to align all module versions automatically:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.agentcore</groupId>
            <artifactId>agentcore-bom</artifactId>
            <version>1.0.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Full stack (everything)

```xml
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-starter</artifactId>
</dependency>
```

### Selective dependency (pick only what you need)

```xml
<!-- Core domain model — always required -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-core</artifactId>
</dependency>

<!-- Add caching -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-cache</artifactId>
</dependency>

<!-- Add JPA persistence -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-store</artifactId>
</dependency>

<!-- Add LLM integration -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-llm</artifactId>
</dependency>

<!-- Add full orchestration (advisors, A2A, auto-config) -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-orchestrator</artifactId>
</dependency>
```

---

## Quick Start

### Prerequisites

- Java 21+
- Spring Boot 3.4.x
- Maven 3.8+

### 1. Import the BOM and add dependencies

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.agentcore</groupId>
            <artifactId>agentcore-bom</artifactId>
            <version>1.0.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.agentcore</groupId>
        <artifactId>agentcore-starter</artifactId>
    </dependency>

    <!-- LLM provider of your choice -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>

    <!-- Optional: JPA persistence (recommended for production) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
</dependencies>
```

### 2. Configure your LLM

```yaml
# application.yml
agent:
  llm:
    gateway-base-url: https://api.openai.com
    api-key: ${OPENAI_API_KEY}
    reasoning-model:
      provider: openai
      model: gpt-4o
    utility-model:
      provider: openai
      model: gpt-4o-mini
```

### 3. Define your session entity

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

### 4. Add your repository

```java
@Repository
public interface MySessionRepository
        extends BaseAgentSessionRepository<MyAgentSession> {
}
```

### 5. Build your agent service

```java
@Service
public class MyAgentService extends BaseSessionLifecycleService<MyAgentSession, MyAuditLog> {

    public MyAgentService(
            AgentSessionStore<MyAgentSession> sessionStore,
            AgentAuditLogStore<MyAuditLog> auditLogStore) {
        super(sessionStore, auditLogStore);
    }

    @Override
    protected MyAuditLog toAuditEvent(MyAgentSession session, String eventType) {
        return MyAuditLog.builder()
                .sessionId(session.getSessionId())
                .eventType(eventType)
                .build();
    }
}
```

Agent Core auto-configures the rest: cache, memory manager, advisor chain, observability, and virtual thread executor.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            Your Application                              │
│                                                                          │
│  @RestController          @Service                    @Component         │
│  BaseAgentController  →  BaseAgentRuntimeService  →  BaseSubAgent[]      │
└─────────────────────────────────┬────────────────────────────────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │    agentcore-orchestrator  │
                    │       Advisor Chain        │
                    │  ConfirmationGate          │
                    │  CircuitBreaker            │
                    │  RateLimiter               │
                    │  MemoryAdvisor             │
                    │  RagAdvisor                │
                    │  AuditAdvisor              │
                    │  ThinkingAdvisor           │
                    │  FallbackAdvisor           │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │      agentcore-llm         │
                    │   Spring AI ChatClient     │
                    │  (OpenAI / Anthropic /     │
                    │   Gemini / Llama / …)      │
                    └───────────────────────────┘
                                  │
         ┌────────────────────────┼───────────────────────┐
         │                        │                       │
┌────────▼────────┐  ┌────────────▼───────────┐  ┌────────▼────────┐
│  agentcore-     │  │    agentcore-store     │  │  agentcore-llm  │
│  orchestrator   │  │  Session & Checkpoint  │  │   Tool System   │
│  Memory System  │  │  BaseAgentSession      │  │  @AgentTool     │
│  EntityFact     │  │  Repositories          │  │  ToolResultCache│
│  PersonaMemory  │  │  AuditLog              │  │  GuardRules     │
└────────┬────────┘  └────────────┬───────────┘  └────────┬────────┘
         │                        │                       │
         └────────────────────────┼───────────────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │      agentcore-cache       │
                    │    AgentCache (Caffeine)   │
                    │    RedisAgentCache         │
                    │    ToolDedupCache          │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │      agentcore-core        │
                    │    BaseAgentSession        │
                    │    SessionContextHolder    │
                    │    DTOs / Exceptions       │
                    └───────────────────────────┘
```

### Module layout

```
io.agentcore
│
├── agentcore-core/
│   └── model/, session/, dto/, exception/
│
├── agentcore-cache/
│   └── cache/  (AgentCache, RedisAgentCache, ToolDedupCache)
│
├── agentcore-store/
│   └── store/, repository/, checkpoint/
│
├── agentcore-llm/
│   └── llm/, prompt/, stream/, tool/, aspect/, filter/, observability/, thread/
│
├── agentcore-orchestrator/
│   └── a2a/, advisor/, agent/, audit/, config/, executor/,
│       guard/, lifecycle/, memory/, orchestrator/, parser/,
│       pipeline/, rag/, reasoning/, router/, scheduler/,
│       session/TenantResolver, summary/, web/
│
├── agentcore-bom/            (version BOM, no Java sources)
└── agentcore-starter/        (pulls all modules above)
```

---

## Features

### Session Lifecycle

Every agent interaction lives inside a **session** — a durable unit of work that can be paused, resumed, checkpointed, and audited.

#### Base session entity

Extend `BaseAgentSession` (from `agentcore-core`) to add domain-specific fields:

| Column             | Type        | Purpose                                        |
|--------------------|-------------|------------------------------------------------|
| `session_id`       | `VARCHAR`   | External identifier (URL-safe, prefixed)       |
| `agent_id`         | `VARCHAR`   | Which agent owns this session                  |
| `current_step`     | `VARCHAR`   | Workflow step enum value                       |
| `status`           | `VARCHAR`   | `ACTIVE` · `PAUSED` · `COMPLETED` · `FAILED`   |
| `context_json`     | `CLOB`      | Full conversation history (LLM context window) |
| `last_checkpoint`  | `VARCHAR`   | Step name to resume from                       |
| `active_sub_agent` | `VARCHAR`   | Currently handling sub-agent                   |
| `auto_approve`     | `VARCHAR`   | JSON array of pre-approved tool names          |
| `tenant_id`        | `VARCHAR`   | Multi-tenant isolation key                     |
| `created_at`       | `TIMESTAMP` | Immutable — set on insert, never updated       |
| `updated_at`       | `TIMESTAMP` | Auto-refreshed on every save                   |

#### Lifecycle operations

```java
MySession session = service.createSession(request);
service.pauseSession(sessionId);
service.resumeFromCheckpoint(sessionId, "STEP_3");
service.transitionStatus(sessionId, SessionStatus.COMPLETED.name());
service.deleteSession(sessionId);
```

---

### Memory System

Agent Core provides a **5-tier memory hierarchy** that persists knowledge across sessions and users.

| Type        | Scope            | Use case                                          |
|-------------|------------------|---------------------------------------------------|
| `ENTITY`    | Cross-session    | Structured facts about products, users, locations |
| `PERSONA`   | Per-user         | Communication style, preferences, long-term goals |
| `WORKFLOW`  | Cross-session    | Past Q&A exchanges, learned patterns              |
| `KNOWLEDGE` | Global           | Domain facts, RAG-ingested documents              |
| `SESSION`   | Current session  | Working memory, conversation-local state          |

```java
memory.remember(MemoryEntry.builder()
        .sessionId(sessionId).userId(userId)
        .type(MemoryType.ENTITY).content(fact).build());

List<MemoryEntry> relevant = memory.recall(sessionId, userId, MemoryType.WORKFLOW, query, 5);
```

---

### Advisor Chain

Eight production-ready advisors that plug into Spring AI's `CallAroundAdvisor` chain in a deterministic order:

```
ConfirmationGateAdvisor  — block mutations without user approval
CircuitBreakerAdvisor    — prevent cascading LLM failures
RateLimitAdvisor         — throttle per-session or globally
MemoryAdvisor            — inject persona + entity + workflow memory
RagAdvisor               — retrieve & inject relevant documents
BaseAuditAdvisor         — record LLM call events
ThinkingAdvisor          — emit chain-of-thought SSE events
FallbackModelAdvisor     — fall back to utility model on error
```

#### Write a custom advisor

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class TokenBudgetAdvisor implements CallAroundAdvisor {

    @Override
    public String getName() { return "token-budget-advisor"; }

    @Override
    public ChatClientResponse aroundCall(ChatClientRequest request, CallAroundAdvisorChain chain) {
        // Pre-call: enforce token budget
        ChatClientResponse response = chain.nextAroundCall(request);
        // Post-call: track spend
        return response;
    }
}
```

---

### Tool System

```java
@Component
public class InventoryTools {

    @AgentTool(
        name        = "check_inventory",
        description = "Returns the current stock level for a given SKU",
        category    = ToolCategory.QUERY)
    public InventoryResult checkInventory(String sku) { ... }

    @AgentTool(
        name        = "create_purchase_order",
        description = "Creates a new PO — requires user confirmation",
        category    = ToolCategory.MUTATION)
    public PurchaseOrder createPurchaseOrder(String sku, int quantity) { ... }
}
```

`MUTATION` tools are intercepted by `ConfirmationGateAdvisor` and require explicit user confirmation before executing. Tool results are automatically cached using `sessionId::toolName::inputHash`.

---

### Agent-to-Agent (A2A) Communication

HTTP-native protocol for multi-agent fan-out with SSE streaming support:

```yaml
agent:
  a2a:
    enabled: true
    clients:
      inventory-agent:
        url: https://inventory-agent.example.com
        base-path: /api/agent
        response-timeout: 120s
```

```java
AgentClient inventoryAgent = registry.get("inventory-agent");
String remoteSession = inventoryAgent.createSession(agentId, createdBy);
inventoryAgent.sendMessage(remoteSession, message, event ->
    System.out.println(event.type() + ": " + event.data()));
```

---

### SSE Streaming

`SseStreamHandler` provides smart chunking, sanitization, thinking events, confirmation detection, and cached replay — from LLM token to browser in one pipeline.

```java
PipelineEmitter pipeline = new PipelineEmitter(emitter);
chatClient.prompt().user(query).stream().chatResponse()
        .doOnNext(response -> streamHandler.appendReadableStreamChunk(
                response.getResult().getOutput().getText(), pipeline, context))
        .blockLast();
```

---

### Observability

| Metric                   | Type    | Tags                           |
|--------------------------|---------|--------------------------------|
| `agent.session.count`    | Counter | `event`, `agentId`             |
| `agent.llm.calls`        | Counter | `provider`, `model`, `outcome` |
| `agent.llm.duration`     | Timer   | `provider`, `model`, `outcome` |
| `agent.tool.calls`       | Counter | `tool`, `outcome`              |
| `agent.handoff.count`    | Counter | `from`, `to`                   |
| `agent.cache.operations` | Counter | `operation`, `type`            |

All metrics appear in `/actuator/metrics` and export to any Micrometer registry without extra configuration.

---

### Store Abstraction

All persistence sits behind narrow interfaces. Swap backends without changing framework code:

| Interface                 | Responsibility                      |
|---------------------------|-------------------------------------|
| `AgentSessionStore<S>`    | Create, read, list, delete sessions |
| `AgentAuditLogStore<A>`   | Append and query audit trail        |
| `ToolResultCacheStore<C>` | Cache and retrieve tool results     |

Built-in backends: **in-memory** (default), **JPA** (add `spring-boot-starter-data-jpa`), or **custom** (`@Bean` implementing the interface).

---

## Configuration Reference

```yaml
agent:
  cache:
    type: inmemory           # inmemory | redis
    ttl: 30m
    inmemory:
      max-entries: 10000
      eviction-policy: LRU  # LRU | LFU | FIFO
    redis:
      host: ${REDIS_HOST}
      port: 6379
      password: ${REDIS_PASSWORD}

  session:
    id-prefix: "sess-"
    id-length: 12
    context-max-size: 1048576

  virtual-threads:
    enabled: true

  a2a:
    enabled: false
    connect-timeout: 10s
    clients:
      <logical-name>:
        url: https://...
        base-path: /api/agent
        response-timeout: 120s

  llm:
    gateway-base-url: https://api.openai.com
    api-key: ${LLM_API_KEY}
    reasoning-model:
      provider: openai
      model: gpt-4o
    utility-model:
      provider: openai
      model: gpt-4o-mini
    ssl:
      trust-all: false
      ca-path: ${CA_BUNDLE_PATH}
```

---

## Extending Agent Core

### Custom store backend (e.g. MongoDB)

```java
@Bean
public AgentSessionStore<MySession> mongoSessionStore(MongoTemplate mongo) {
    return new MongoAgentSessionStore<>(mongo, MySession.class);
}
```

### Custom persona store (e.g. Redis)

```java
@Bean
public PersonaStore redisPersonaStore(RedisTemplate<String, PersonaMemory> redis) {
    return new RedisPersonaStore(redis);
}
```

### Multi-tenant session isolation

```java
@Component
public class HeaderTenantResolver implements TenantResolver {

    @Override
    public String resolveTenantId(HttpServletRequest request) {
        return request.getHeader("X-Tenant-ID");
    }
}
```

### Advanced reasoning strategies

| Strategy         | Class                    | When to use                           |
|------------------|--------------------------|---------------------------------------|
| Least-to-most    | `LeastToMostSolver`      | Multi-step decomposition              |
| Self-consistency | `SelfConsistencyRunner`  | High-stakes decisions (majority vote) |
| Tree-of-thoughts | `TreeOfThoughtsExplorer` | Exploratory / creative tasks          |
| Decomposition    | `DecomposedPromptRunner` | Complex queries with independent sub-tasks |

---

## Test Utilities

`agentcore-store` ships in-memory implementations of all store interfaces for unit and integration tests:

```java
@SpringBootTest
class MyAgentServiceTest {

    @Autowired InMemoryAgentSessionStore<MySession> sessionStore;
    @Autowired InMemoryAgentAuditLogStore<MyAuditLog> auditLogStore;
    @Autowired InMemoryToolResultCacheStore<AgentToolResultCacheDO> cacheStore;

    @AfterEach
    void cleanup() {
        sessionStore.clear();
        auditLogStore.clear();
        cacheStore.clear();
    }
}
```

`MockChatModel` and `SseEventCaptor` are available in `agentcore-llm` (test scope) for unit-testing streaming agents.

---

## Contributing

### Build

```shell
# Full CI build with static analysis
mvn verify

# Skip static analysis for faster local iteration
mvn verify -Dspotbugs.skip=true -Dpmd.skip=true

# Build a single module and its dependencies
mvn verify -pl agentcore-llm --also-make

# Run static analysis standalone
mvn spotbugs:check
mvn pmd:check
mvn pmd:cpd-check
```

### Release

Releases are fully automated via GitHub Actions. Go to **Actions → Release → Run workflow**, pick the bump type (`patch` / `minor` / `major`), and click **Run**. No local steps.

### Code standards

- **Java 21** — prefer records, sealed classes, pattern matching, and virtual threads
- **Lombok** — use `@Data`, `@SuperBuilder`, `@RequiredArgsConstructor` consistently; never mix `@Builder` and `@SuperBuilder` in an inheritance hierarchy
- **Javadoc** — all `public` interfaces and classes require Javadoc
- **Static analysis** — SpotBugs + PMD must pass before merging (`mvn verify`)
- **Tests** — new features require unit tests using in-memory store utilities from `agentcore-store`

Exclusion rules live in `spotbugs-exclude.xml` and `pmd-ruleset.xml`. Do not add `@SuppressWarnings` to production code to silence analysers.

---

## License

Agent Core is currently a private personal project.

Copyright 2026 Shekhar Maheswari. All rights reserved.
