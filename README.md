<div align="center">

# Agent Core

**Production-ready, extensible foundation for building AI agents on Spring AI**

[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4.1-6db33f?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.0-6db33f)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

Agent Core is a Spring Boot library that gives you the batteries-included scaffolding to build, run, and scale production AI agents — without writing the same boilerplate every time.

[Quick Start](#quick-start) · [Architecture](#architecture) · [Features](#features) · [Configuration](#configuration-reference) · [Extending Agent Core](#extending-agent-core) · [Contributing](#contributing)

</div>

---

## Why Agent Core?

Building an AI agent from scratch means wiring together LLM calls, session state, audit logs, memory, tool caching, rate limiting, circuit breakers, SSE streaming, and multi-agent coordination. That's weeks of infrastructure work before you write a single line of business logic.

Agent Core gives you all of that — tested, observable, and extensible — as a single Maven dependency. You focus on what your agent *does*; Agent Core handles how it *runs*.

**Key features at a glance:**

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

## Table of Contents

- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [Features](#features)
  - [Session Lifecycle](#session-lifecycle)
  - [Memory System](#memory-system)
  - [Advisor Chain](#advisor-chain)
  - [RAG (Retrieval-Augmented Generation)](#rag-retrieval-augmented-generation)
  - [Tool System](#tool-system)
  - [Multi-Agent Orchestration](#multi-agent-orchestration)
  - [Agent-to-Agent (A2A) Communication](#agent-to-agent-a2a-communication)
  - [SSE Streaming](#sse-streaming)
  - [Observability](#observability)
  - [Cache & Deduplication](#cache--deduplication)
  - [Store Abstraction](#store-abstraction)
- [Configuration Reference](#configuration-reference)
- [Extending Agent Core](#extending-agent-core)
- [Test Utilities](#test-utilities)
- [Contributing](#contributing)
- [License](#license)

---

## Quick Start

### Prerequisites

- Java 21+
- Spring Boot 3.4.x
- Maven 3.8+

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore</artifactId>
    <version>1.0.3</version>
</dependency>

<!-- Optional: JPA persistence (recommended for production) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
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

    @Column(name = "product_category")
    private String productCategory;

    @Override
    public String getDomainContext() {
        return "customer=" + customerId + ", category=" + productCategory;
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

That's it. Agent Core auto-configures the rest: cache, memory manager, advisor chain, observability, and virtual thread executor.

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
                    │       Advisor Chain       │
                    │  ConfirmationGate         │
                    │  CircuitBreaker           │
                    │  RateLimiter              │
                    │  MemoryAdvisor            │
                    │  RagAdvisor               │
                    │  AuditAdvisor             │
                    │  ThinkingAdvisor          │
                    │  FallbackAdvisor          │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │     Spring AI ChatClient  │
                    │  (OpenAI / Anthropic /    │
                    │   Gemini / Llama / …)     │
                    └───────────────────────────┘
                                  │
         ┌────────────────────────┼───────────────────────┐
         │                        │                       │
┌────────▼────────┐  ┌────────────▼───────────┐  ┌────────▼────────┐
│  Memory System  │  │  Session & Checkpoint  │  │   Tool System   │
│  EntityFact     │  │  BaseAgentSession      │  │  @AgentTool     │
│  PersonaMemory  │  │  Checkpointing         │  │  ToolResultCache│
│  WorkflowMemory │  │  AuditLog              │  │  GuardRules     │
│  KnowledgeBase  │  │  ExpiryScheduler       │  │  Deduplication  │
└────────┬────────┘  └────────────┬───────────┘  └────────┬────────┘
         │                        │                       │
         └────────────────────────┼───────────────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │      Store Abstraction    │
                    │  AgentSessionStore        │
                    │  AgentAuditLogStore       │
                    │  ToolResultCacheStore     │
                    │  PersonaStore             │
                    │  EntityMemoryStore        │
                    │                           │
                    │  Backends:                │
                    │  In-Memory / JPA / Redis  │
                    └───────────────────────────┘
```

### Package structure

```
io.agentcore
├── advisor/         # 8 built-in Spring AI advisors
├── a2a/             # Agent-to-Agent HTTP protocol + SSE parser
├── agent/           # BaseSubAgent SPI + orchestration
├── cache/           # AgentCache (Caffeine / Redis)
├── checkpoint/      # Tool result caching, session checkpoints
├── config/          # Spring Boot auto-configuration
├── dto/             # REST API request/response types
├── executor/        # AgentExecutor, AgentRegistry
├── lifecycle/       # BaseSessionLifecycleService
├── llm/             # ChatClientRegistry, LlmProvider, ModelTier
├── memory/          # AgentMemoryManager + 5 MemoryType impls
│   ├── entity/      # EntityFact, EntityMemoryStore
│   └── persona/     # PersonaMemory, PersonaStore
├── model/           # JPA base entities (@MappedSuperclass)
├── observability/   # AgentObservabilityService, Micrometer metrics
├── rag/             # RagAdvisor, AgentRetriever SPI
├── reasoning/       # ReasoningStrategy impls (ToT, Self-Consistency, …)
├── repository/      # Base Spring Data repository interfaces
├── router/          # Intent routing (LLM-based)
├── session/         # SessionContextHolder, TenantResolver
├── store/           # Store interfaces + JPA adapters
├── stream/          # SSE streaming, SseStreamHandler, PipelineEmitter
├── test/            # In-memory test implementations of all stores
└── tool/            # @AgentTool, ToolCategory, ToolExecutionSupport
```

---

## Features

### Session Lifecycle

Every agent interaction lives inside a **session** — a durable unit of work that can be paused, resumed, checkpointed, and audited.

#### Base session entity

Extend `BaseAgentSession` to add domain-specific fields. All framework columns are inherited automatically:

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
| `created_by`       | `VARCHAR`   | User or service that opened the session        |
| `tenant_id`        | `VARCHAR`   | Multi-tenant isolation key                     |
| `created_at`       | `TIMESTAMP` | Immutable — set on insert, never updated       |
| `updated_at`       | `TIMESTAMP` | Auto-refreshed on every save                   |

#### Lifecycle operations

```java
// Create
MySession session = service.createSession(request);

// Retrieve
MySession session = service.getSession(sessionId);

// List (most-recent-first)
List<MySession> active = service.listSessions(SessionStatus.ACTIVE.name());

// Pause and resume
service.pauseSession(sessionId);
service.resumeFromCheckpoint(sessionId, "STEP_3");

// Transition status
service.transitionStatus(sessionId, SessionStatus.COMPLETED.name());

// Delete (COMPLETED sessions are preserved by default for audit trail integrity)
service.deleteSession(sessionId);

// Bulk operations
service.deleteSessions(List.of(id1, id2, id3));
```

#### Audit trail

Every status change and LLM call is automatically recorded. Retrieve the full trail at any time:

```java
List<MyAuditLog> trail  = service.getAuditTrail(sessionId);
List<MyAuditLog> errors = service.getAuditTrail(sessionId, "CALL_ERROR");
```

---

### Memory System

Agent Core provides a **5-tier memory hierarchy** that persists knowledge across sessions and users.

#### Memory types

| Type        | Scope                    | Use case                                          |
|-------------|--------------------------|---------------------------------------------------|
| `ENTITY`    | Cross-session            | Structured facts about products, users, locations |
| `PERSONA`   | Per-user                 | Communication style, preferences, long-term goals |
| `WORKFLOW`  | Cross-session (semantic) | Past Q&A exchanges, learned patterns              |
| `KNOWLEDGE` | Global                   | Domain facts, RAG-ingested documents              |
| `SESSION`   | Current session only     | Working memory, conversation-local state          |

#### AgentMemoryManager

```java
@Service
public class MyAgent {

    private final AgentMemoryManager memory;

    // Store a fact extracted from an LLM response
    public void learnFact(String sessionId, String userId, String fact) {
        memory.remember(MemoryEntry.builder()
                .sessionId(sessionId)
                .userId(userId)
                .type(MemoryType.ENTITY)
                .content(fact)
                .build());
    }

    // Retrieve the most relevant memories for the current query
    public List<MemoryEntry> recall(String sessionId, String userId, String query) {
        return memory.recall(sessionId, userId, MemoryType.WORKFLOW, query, 5);
    }

    // Wipe session memory on completion
    public void cleanup(String sessionId) {
        memory.forgetSession(sessionId);
    }
}
```

#### PersonaMemory

The persona system tracks what the agent knows about each user — persisted indefinitely and injected into every context window:

```java
PersonaMemory persona = PersonaMemory.empty(userId)
        .withPreference("prefers concise bullet-point responses")
        .withGoal("reduce quarterly operating costs by 15%")
        .withCommunicationStyle("technical")
        .withAttribute("role", "Operations Manager")
        .withAttribute("region", "Southwest");

// Formats as a system prompt block automatically:
// [USER PERSONA]
// Preferences: prefers concise bullet-point responses
// Goals: reduce quarterly operating costs by 15%
// Communication style: technical
// Profile: role=Operations Manager, region=Southwest
// [END USER PERSONA]
String fragment = persona.toPromptFragment();
```

#### EntityFact

Type-safe, structured facts about any domain object — retrieved semantically by the `MemoryAdvisor`:

```java
EntityFact fact = new EntityFact(
        "SKU-12345",           // entityId
        "PRODUCT",             // entityType
        "reorder_threshold",   // attribute
        "150 units",           // value
        sessionId,
        userId,
        Instant.now());

// Formats as: PRODUCT(SKU-12345).reorder_threshold = 150 units
String fragment = fact.toPromptFragment();
```

---

### Advisor Chain

Agent Core ships **8 production-ready advisors** that plug into Spring AI's `CallAdvisor` chain. They compose in a deterministic order — no wiring required.

#### Execution order

```
HIGHEST_PRECEDENCE      ConfirmationGateAdvisor  — block mutations without user approval
HIGHEST_PRECEDENCE + 3  CircuitBreakerAdvisor    — prevent cascading LLM failures
HIGHEST_PRECEDENCE + 5  RateLimitAdvisor         — throttle per-session or globally
HIGHEST_PRECEDENCE + 5  MemoryAdvisor            — inject persona + entity + workflow memory
HIGHEST_PRECEDENCE + 10 RagAdvisor               — retrieve & inject relevant documents
                        BaseAuditAdvisor          — record LLM call events
HIGHEST_PRECEDENCE + 20 ThinkingAdvisor          — emit chain-of-thought SSE events
                        FallbackModelAdvisor      — fall back to utility model on error
```

#### MemoryAdvisor

Automatically retrieves and injects all relevant memories before every LLM call, then stores the Q&A exchange afterward:

```java
@Bean
public MemoryAdvisor memoryAdvisor(AgentMemoryManager memoryManager) {
    return MemoryAdvisor.builder(memoryManager)
            .maxWorkflowContentLength(2000)
            .injectPrefix("\n<!-- AGENT MEMORY -->\n")
            .injectSuffix("\n<!-- END MEMORY -->\n")
            .build();
}
```

Override `extractEntities()` to parse and store structured facts from LLM responses:

```java
@Override
protected List<EntityFact> extractEntities(String sessionId, String userId, String response) {
    // Your extraction logic — NLP, regex, or a second LLM call
    return entityExtractor.extract(response, sessionId, userId);
}
```

#### RateLimitAdvisor

Token-bucket rate limiting with per-session or global scope:

```java
@Bean
public RateLimitAdvisor rateLimitAdvisor() {
    return RateLimitAdvisor.builder()
            .requestsPerMinute(30)
            .strategy(RateLimitAdvisor.Strategy.PER_SESSION)
            .build();
}
```

#### CircuitBreakerAdvisor

Wraps LLM calls with Resilience4j to prevent thundering-herd failures:

```java
@Bean
public CircuitBreakerAdvisor circuitBreakerAdvisor() {
    return CircuitBreakerAdvisor.builder()
            .failureRateThreshold(50)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build();
}
```

#### ConfirmationGateAdvisor

Blocks any tool marked `@AgentTool(category = ToolCategory.MUTATION)` until the user explicitly confirms. No configuration needed — activated automatically for mutation tools:

```
User: "Delete all records from last quarter."
Agent: "This action will permanently delete 1,247 records. Please confirm to proceed."
User: "Yes, confirmed."
Agent: [executes deletion]
```

#### RagAdvisor

Retrieve and inject documents from any vector store before each LLM call:

```java
@Bean
public RagAdvisor ragAdvisor(AgentRetriever retriever) {
    return RagAdvisor.builder(retriever)
            .maxDocuments(5)
            .minScore(0.75)
            .contextPrefix("\n[RELEVANT DOCUMENTS]\n")
            .contextSuffix("\n[END DOCUMENTS]\n")
            .build();
}
```

#### ThinkingAdvisor

Emits structured SSE events containing chain-of-thought metadata — token usage, latency, model tier, and query previews — so your UI can display a "thinking" indicator with real data:

```json
{ "type": "thinking", "data": "{ \"promptTokens\": 1240, \"latencyMs\": 820, \"model\": \"gpt-4o\" }" }
```

#### Writing a custom advisor

Implement Spring AI's `CallAroundAdvisor` and register as a `@Bean`:

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

### RAG (Retrieval-Augmented Generation)

Implement `AgentRetriever` to connect any document source:

```java
@Component
public class MyRetriever implements AgentRetriever {

    private final VectorStore vectorStore;

    @Override
    public List<RetrievedDocument> retrieve(String query, Map<String, Object> context) {
        return vectorStore.similaritySearch(
                        SearchRequest.query(query).withTopK(10))
                .stream()
                .map(doc -> RetrievedDocument.builder()
                        .id(doc.getId())
                        .content(doc.getContent())
                        .score(doc.getScore())
                        .source(doc.getMetadata().getOrDefault("source", "unknown").toString())
                        .build())
                .toList();
    }
}
```

The built-in `SpringAiVectorStoreRetriever` wraps any Spring AI `VectorStore` with no extra code:

```java
@Bean
public AgentRetriever retriever(VectorStore vectorStore) {
    return new SpringAiVectorStoreRetriever(vectorStore);
}
```

---

### Tool System

Mark any Spring bean method as an agent tool with `@AgentTool`:

```java
@Component
public class InventoryTools {

    @AgentTool(
        name        = "check_inventory",
        description = "Returns the current stock level for a given SKU",
        category    = ToolCategory.QUERY)
    public InventoryResult checkInventory(String sku) {
        return inventoryService.getStock(sku);
    }

    @AgentTool(
        name        = "create_purchase_order",
        description = "Creates a new purchase order — requires user confirmation",
        category    = ToolCategory.MUTATION)
    public PurchaseOrder createPurchaseOrder(String sku, int quantity) {
        return poService.create(sku, quantity);
    }
}
```

- **`QUERY`** tools execute immediately.
- **`MUTATION`** tools are intercepted by `ConfirmationGateAdvisor` and require an explicit user confirmation before running.

#### Tool result caching

Idempotent tool results are automatically cached using the key format `sessionId::toolName::inputHash`. On session resume or LLM retry, the cached result is returned instantly — no re-execution:

```java
// Inspect the cache
long count = toolResultCacheStore.countBySessionId(sessionId);

// Clear on session end
toolResultCacheStore.deleteBySessionId(sessionId);
```

---

### Multi-Agent Orchestration

Decompose complex workflows across specialised sub-agents. Each sub-agent declares the workflow steps it handles; the orchestrator routes automatically:

```java
@Component
@Order(1)
public class DataCollectionAgent implements BaseSubAgent<MySession, WorkflowStep> {

    @Override
    public String name() { return "data-collection"; }

    @Override
    public boolean canHandle(MySession session, WorkflowStep step) {
        return step == WorkflowStep.COLLECT_DATA;
    }

    @Override
    public String systemPrompt(MySession session, Map<String, Object> context) {
        return """
               You are a data collection specialist. Gather all required information
               before passing control to the analysis agent.
               """;
    }

    @Override
    public List<String> ownedTools()    { return List.of("fetch_records", "validate_input"); }

    @Override
    public List<String> handledSteps()  { return List.of("COLLECT_DATA", "VALIDATE"); }
}
```

The orchestrator queries every registered `BaseSubAgent` bean in `@Order` priority, calls `canHandle()`, and routes to the first match. Handoff events are counted in the `agent.handoff.count` metric.

---

### Agent-to-Agent (A2A) Communication

Agent Core includes a first-class HTTP protocol for agents to call other agents, supporting both **SSE streaming** and **JSON** response formats with content-type-based parser routing.

#### Configure remote agents

```yaml
agent:
  a2a:
    enabled: true
    connect-timeout: 10s
    clients:
      inventory-agent:
        url: https://inventory-agent.example.com
        base-path: /api/agent
        response-timeout: 120s
      analytics-agent:
        url: https://analytics-agent.example.com
        base-path: /api/agent
        response-timeout: 60s
```

#### Wrap as a sub-agent (recommended)

```java
@Component
public class RemoteInventorySubAgent extends RemoteAgentSubAgent<MySession, WorkflowStep> {

    public RemoteInventorySubAgent(AgentClientRegistry registry) {
        super(registry.get("inventory-agent"));
    }

    @Override
    public boolean canHandle(MySession session, WorkflowStep step) {
        return step == WorkflowStep.INVENTORY_CHECK;
    }
}
```

#### Use the client directly

```java
@Service
public class OrchestrationService {

    private final AgentClient inventoryAgent;

    public OrchestrationService(AgentClientRegistry registry) {
        this.inventoryAgent = registry.get("inventory-agent");
    }

    public void runInventoryCheck(String message) {
        String remoteSession = inventoryAgent.createSession(agentId, createdBy);
        try {
            inventoryAgent.sendMessage(remoteSession, message, event -> {
                // Called once per SSE block (text/event-stream)
                // or once for the full body (application/json)
                System.out.println(event.type() + ": " + event.data());
            });
        } finally {
            inventoryAgent.deleteSession(remoteSession);
        }
    }
}
```

`sendMessage` automatically routes to `SseEventParser` for `text/event-stream` responses and reads the full body as a single event for `application/json` — the parser is chosen from the actual `Content-Type` header returned by the remote agent.

**A single `A2AHttpClient` singleton** is shared across all agent clients, providing HTTP/1.1 keep-alive and HTTP/2 connection pooling with no per-request overhead.

#### Custom auth headers

```java
@Component
public class BearerTokenContributor implements A2AAuthContributor {

    @Override
    public void contribute(HttpHeaders headers, String agentName) {
        headers.setBearerAuth(tokenService.getToken(agentName));
    }
}
```

---

### SSE Streaming

`SseStreamHandler` provides a full-featured streaming pipeline from LLM token to browser:

```java
@Service
public class MyStreamingAgent {

    private final SseStreamHandler streamHandler;

    public void stream(String query, SseEmitter emitter) {
        PipelineEmitter pipeline = new PipelineEmitter(emitter);

        chatClient.prompt()
                .user(query)
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String token = response.getResult().getOutput().getText();
                    streamHandler.appendReadableStreamChunk(token, pipeline, context);
                })
                .blockLast();
    }
}
```

**What `SseStreamHandler` does automatically:**

- **Smart chunking** — buffers tokens; flushes at natural sentence boundaries (`.`, `!`, `?`, `\n`) or at a 240-character word-boundary overflow
- **Sanitization** — strips SSE wire artifacts (`data:`, `event:`, `:keep-alive`), masks internal session IDs
- **Thinking events** — emits `type=thinking` with token usage and latency before/after each LLM call
- **Confirmation detection** — recognises confirmation-request language for `ConfirmationGateAdvisor`
- **Cached replay** — `chunkAssistantForUi(fullText)` replays a stored response over SSE without re-calling the LLM

---

### Observability

#### Micrometer metrics

| Metric                   | Type    | Tags                           |
|--------------------------|---------|--------------------------------|
| `agent.session.count`    | Counter | `event`, `agentId`             |
| `agent.llm.calls`        | Counter | `provider`, `model`, `outcome` |
| `agent.llm.duration`     | Timer   | `provider`, `model`, `outcome` |
| `agent.tool.calls`       | Counter | `tool`, `outcome`              |
| `agent.tool.duration`    | Timer   | `tool`, `outcome`              |
| `agent.handoff.count`    | Counter | `from`, `to`                   |
| `agent.cache.operations` | Counter | `operation`, `type`            |

All metrics are zero-dependency — they appear in `/actuator/metrics` and export to any Micrometer registry (Prometheus, Datadog, CloudWatch, etc.) without extra configuration.

#### MDC structured logging

`sessionId`, `agentName`, and `toolName` are set in MDC for every log line produced by the framework. Add them to your log pattern:

```xml
<!-- logback-spring.xml -->
<pattern>%d{HH:mm:ss} [%X{sessionId}] [%X{agentName}] %-5level %logger{36} - %msg%n</pattern>
```

#### Distributed tracing

Agent Core integrates with Micrometer Tracing. Add your bridge (OpenTelemetry or Brave) and trace context propagates automatically across virtual threads and A2A hops.

#### Programmatic observability

```java
@Service
public class MyAgent {

    private final AgentObservabilityService observability;

    public void execute(String sessionId, String agentName) {
        observability.setMdcContext(sessionId, agentName);
        observability.recordLlmCall("openai", "gpt-4o", "success");
        observability.recordToolCall("check_inventory", "success", Duration.ofMillis(120));
    }
}
```

---

### Cache & Deduplication

#### In-memory (development / single-pod)

```yaml
agent:
  cache:
    type: inmemory
    ttl: 30m
    inmemory:
      max-entries: 10000
      eviction-policy: LRU   # LRU | LFU | FIFO
```

#### Redis (production / multi-pod)

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

Redis mode activates `RedisToolDedupCache` automatically — concurrent identical tool calls across pods deduplicate to a single execution, preventing thundering-herd re-work on scale-out.

---

### Store Abstraction

All persistence is behind three narrow interfaces. Swap backends (JPA → MongoDB → DynamoDB → custom) by providing a `@Bean` — zero framework code changes required.

| Interface                 | Responsibility                            |
|---------------------------|-------------------------------------------|
| `AgentSessionStore<S>`    | Create, read, list, delete sessions       |
| `AgentAuditLogStore<A>`   | Append and query audit trail entries      |
| `ToolResultCacheStore<C>` | Cache and retrieve tool execution results |

#### Built-in backends

| Backend       | Activation                                                        |
|---------------|-------------------------------------------------------------------|
| **In-memory** | Default — no configuration needed                                 |
| **JPA**       | Add `spring-boot-starter-data-jpa` + concrete entity + repository |
| **Custom**    | Declare a `@Bean` implementing the interface                      |

#### JPA activation (three steps)

```java
// 1. Extend the base entity
@Entity
@Table(name = "my_sessions")
public class MySession extends BaseAgentSession { ... }

// 2. Extend the base repository
@Repository
public interface MySessionRepository extends BaseAgentSessionRepository<MySession> { }

// 3. Done — Agent Core auto-configures JpaAgentSessionStore<MySession>
```

#### Implementing a custom backend

```java
@Bean
public AgentSessionStore<MySession> mongoSessionStore(MongoTemplate mongo) {
    return new MongoAgentSessionStore<>(mongo, MySession.class);
}
```

The same pattern works for `AgentAuditLogStore` and `ToolResultCacheStore`.

---

## Configuration Reference

### `agent.*`

```yaml
agent:
  cache:
    type: inmemory                    # inmemory | redis
    ttl: 30m
    key-prefix: "agent:cache:"
    inmemory:
      max-entries: 10000
      eviction-policy: LRU           # LRU | LFU | FIFO
    redis:
      host: localhost
      port: 6379
      password: ""
      database: 0
      pool:
        max-active: 10
        max-idle: 5
        min-idle: 1
    maintenance:
      enabled: false
      cleanup-cron: "0 0 * * * *"
      max-entry-age: 24h
      stats-interval: 1m

  session:
    id-prefix: "sess-"
    id-length: 12
    context-max-size: 1048576        # bytes (default 1 MB)

  virtual-threads:
    enabled: true                    # requires Java 21+

  a2a:
    enabled: false
    connect-timeout: 10s
    clients:
      <logical-name>:
        url: https://...
        base-path: /api/agent
        response-timeout: 120s
```

### `agent.llm.*`

```yaml
agent:
  llm:
    gateway-base-url: https://api.openai.com
    api-key: ${LLM_API_KEY}
    default-provider: openai
    temperature: 0.1
    max-tokens: 4096

    reasoning-model:
      provider: openai               # openai | anthropic | google | llama | gemma
      model: gpt-4o
      version: "2025-04-14"
      api-version: "2024-02-01"

    utility-model:
      provider: openai
      model: gpt-4o-mini
      version: "2024-07-18"

    system-prompts:
      default: prompts/default-system-prompt.txt
      my-agent: prompts/my-agent-system-prompt.txt

    ssl:
      trust-all: false               # DEVELOPMENT ONLY — never use in production
      ca-path: ${CA_BUNDLE_PATH}     # path to custom CA bundle (PEM)
```

---

## Extending Agent Core

### Custom LLM provider

```java
@Bean
public ChatClient myCustomChatClient(ChatClientBuilder builder) {
    return builder
            .defaultAdvisors(myAdvisor1, myAdvisor2)
            .build();
}
```

### Custom persona store (e.g., Redis)

```java
@Bean
public PersonaStore redisPersonaStore(RedisTemplate<String, PersonaMemory> redis) {
    return new RedisPersonaStore(redis);
}
```

### Custom memory manager (e.g., vector-store backed)

```java
@Bean
public AgentMemoryManager vectorMemoryManager(VectorStore vectorStore, ObjectMapper mapper) {
    return new VectorStoreMemoryManager(vectorStore, mapper);
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

### Dynamic system prompts

```java
@Component
public class MySystemPromptProvider implements AgentSystemPromptProvider {

    @Override
    public String getSystemPrompt(String agentName, Map<String, Object> context) {
        return promptRepository.load("prompts/" + agentName + ".txt")
                .formatted(context.get("tenantId"), context.get("userId"));
    }
}
```

### Advanced reasoning strategies

Agent Core includes several built-in reasoning strategy implementations:

| Strategy         | Class                    | When to use                                |
|------------------|--------------------------|--------------------------------------------|
| Chain-of-thought | Default                  | Most queries                               |
| Least-to-most    | `LeastToMostSolver`      | Multi-step decomposition                   |
| Self-consistency | `SelfConsistencyRunner`  | High-stakes decisions (majority vote)      |
| Tree-of-thoughts | `TreeOfThoughtsExplorer` | Exploratory / creative tasks               |
| Decomposition    | `DecomposedPromptRunner` | Complex queries with independent sub-tasks |

---

## Test Utilities

Agent Core ships in-memory implementations of all store interfaces under `io.agentcore.test`, designed for unit and integration tests:

```java
@SpringBootTest
class MyAgentServiceTest {

    @Autowired
    InMemoryAgentSessionStore<MySession> sessionStore;

    @Autowired
    InMemoryAgentAuditLogStore<MyAuditLog> auditLogStore;

    @Autowired
    InMemoryToolResultCacheStore<AgentToolResultCacheDO> cacheStore;

    @Test
    void shouldCreateSessionAndAuditEvent() {
        MySession session = MySession.builder()
                .sessionId("test-session-1")
                .agentId("my-agent")
                .status("ACTIVE")
                .build();

        sessionStore.save(session);

        assertThat(sessionStore.findBySessionId("test-session-1")).isPresent();
        assertThat(auditLogStore.size()).isGreaterThan(0);
    }

    @AfterEach
    void cleanup() {
        sessionStore.clear();
        auditLogStore.clear();
        cacheStore.clear();
    }
}
```

All in-memory stores expose test-only helpers: `findAll()`, `size()`, `isEmpty()`, `clear()`.

---

## Contributing

Contributions are welcome. Here's how to get started:

### Build

```shell
# Compile + test (skip static analysis for speed)
mvn verify -Dspotbugs.skip=true -Dpmd.skip=true

# Full CI build with static analysis
mvn verify

# Individual checks
mvn spotbugs:check
mvn pmd:check
mvn pmd:cpd-check
```

### Code standards

- **Java 21** — prefer records, sealed classes, pattern matching, and virtual threads where idiomatic
- **Lombok** — use `@Data`, `@SuperBuilder`, `@RequiredArgsConstructor` consistently with existing code; never mix `@Builder` and `@SuperBuilder` across an inheritance hierarchy
- **Imports** — always `import` at the top of the file; never inline fully-qualified class names in method bodies
- **Javadoc** — all `public` interfaces and classes require Javadoc; `@param` and `@return` on all public methods
- **Static analysis** — both SpotBugs and PMD must pass before merging (`mvn verify`)
- **Tests** — new features require unit tests using the in-memory store utilities in `io.agentcore.test`

### Static analysis rules

Exclusion rules live in `spotbugs-exclude.xml` and `pmd-ruleset.xml`. Edit those files to tune rules — do not add `@SuppressWarnings` to production code to silence analysers.

---

## License

Agent Core is released under the [Apache License, Version 2.0](LICENSE).

```
Copyright 2024-2025 the original authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```
