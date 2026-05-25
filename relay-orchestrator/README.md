# relay-orchestrator

**Full orchestration layer — advisors, A2A protocol, agent routing, memory, RAG, reasoning strategies, session expiry, and Spring Boot auto-configuration.**

This is the top-level Relay module. It wires together every other module, provides all 8 built-in advisors, and registers the auto-configuration that activates Relay in any Spring Boot application.

## What's inside

| Package          | Contents                                                                                                                                                                                                                            |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `advisor/`       | 8 built-in advisors: `ConfirmationGateAdvisor`, `CircuitBreakerAdvisor`, `RateLimitAdvisor`, `MemoryAdvisor`, `RagAdvisor`, `BaseAuditAdvisor`, `ThinkingAdvisor`, `FallbackModelAdvisor`                                           |
| `a2a/`           | `AgentClient`, `AgentClientRegistry`, `A2AHttpClient`, `A2AAuthContributor`, `StaticBearerTokenA2AAuthContributor`, `ApiKeyA2AAuthContributor`, `BasicAuthA2AAuthContributor`, `AgentCard`, `AgentCardController`, `SseEventParser` |
| `agent/`         | `BaseSubAgent`, `AgentExecutionContext`                                                                                                                                                                                             |
| `audit/`         | `AgentRequestTrace`                                                                                                                                                                                                                 |
| `config/`        | `RelayAutoConfiguration`, `ChatClientAutoConfiguration`, `RelayProperties`, `AgentLlmProperties`, `EnableRelay`                                                                                                         |
| `executor/`      | `AgentExecutor`, `AgentRegistry`, `BaseAgentRuntimeService`                                                                                                                                                                         |
| `lifecycle/`     | `BaseSessionLifecycleService`                                                                                                                                                                                                       |
| `memory/`        | `AgentMemoryManager`, `InMemoryAgentMemoryManager`, `MemoryEntry`, `MemoryType`                                                                                                                                                     |
| `memory/entity`  | `EntityFact`, `EntityMemoryStore`, `InMemoryEntityMemoryStore`                                                                                                                                                                      |
| `memory/persona` | `PersonaMemory`, `PersonaStore`, `InMemoryPersonaStore`                                                                                                                                                                             |
| `orchestrator/`  | `BaseAgentOrchestrator`                                                                                                                                                                                                             |
| `rag/`           | `RagAdvisor`, `AgentRetriever`, `SpringAiVectorStoreRetriever`, `RetrievedDocument`                                                                                                                                                 |
| `reasoning/`     | `LeastToMostSolver`, `SelfConsistencyRunner`, `TreeOfThoughtsExplorer`, `DecomposedPromptRunner`                                                                                                                                    |
| `scheduler/`     | `BaseSessionExpiryScheduler`, `DefaultSessionExpiryScheduler`                                                                                                                                                                       |
| `session/`       | `TenantResolver`                                                                                                                                                                                                                    |
| `summary/`       | `SessionSummarizer`, `BaseLlmSessionSummarizer`                                                                                                                                                                                     |
| `web/`           | `BaseAgentController`                                                                                                                                                                                                               |

## Dependency

```xml
<dependency>
    <groupId>io.relay</groupId>
    <artifactId>relay-orchestrator</artifactId>
</dependency>
```

## Auto-configuration

`RelayAutoConfiguration` activates when `relay-orchestrator` is on the classpath. It automatically wires:

- `AgentMemoryManager` (in-memory by default)
- `EntityMemoryStore` and `PersonaStore` (in-memory by default)
- `AgentCache` — Caffeine (`inmemory`) or Redis (`redis`) based on `agent.cache.type`
- `ToolResultCache` — with optional dedicated TTL from `agent.cache.tool-ttl`
- `ToolDedupCache` — local or Redis-backed
- `RagAdvisor` — **auto-wired** when an `AgentRetriever` bean is present
- `DefaultSessionExpiryScheduler` — **auto-wired** when `agent.session.expiry.enabled=true` and JPA is present
- `agentCommonTagsMeterFilter` — adds `agent.metrics.common-tags` to all `agent.*` Micrometer metrics
- `AgentRegistry` (scans for `AgentExecutor` beans)
- `AgentCardController` (when `agent.a2a.enabled=true`)
- JPA store beans (when `spring-boot-starter-data-jpa` is present and concrete repos are defined)

## Advisor Chain

Eight advisors execute in a deterministic order:

```
HIGHEST_PRECEDENCE      ConfirmationGateAdvisor   — block MUTATION tools without user approval
HIGHEST_PRECEDENCE + 3  CircuitBreakerAdvisor     — prevent cascading LLM failures (Resilience4j)
HIGHEST_PRECEDENCE + 5  RateLimitAdvisor          — throttle per-session or globally
HIGHEST_PRECEDENCE + 5  MemoryAdvisor             — inject persona + entity + workflow memory
HIGHEST_PRECEDENCE + 10 RagAdvisor                — retrieve & inject relevant documents
                        BaseAuditAdvisor           — record LLM call events
HIGHEST_PRECEDENCE + 20 ThinkingAdvisor           — emit chain-of-thought SSE events
                        FallbackModelAdvisor       — fall back to utility model on error
```

### ConfirmationGateAdvisor + REST protocol

Automatically activated — requires no bean registration. Blocks any `@AgentTool(category = MUTATION)` until the user confirms via `POST /sessions/{id}/confirm`:

```http
POST /api/my-agent/sessions/sess-abc123/confirm
Content-Type: application/json
Accept: text/event-stream

{ "toolName": "delete_order", "confirmed": true }
```

The advisor detects the confirmation signal, enriches the request context with `user_confirmed=true`, and the pipeline continues. A `{ "confirmed": false }` request returns a clean rejection response without calling the LLM.

Override `getAdditionalMutationTools()` or `getBlockedMessage()` for custom behaviour:

```java
@Component
public class MyConfirmationGate extends ConfirmationGateAdvisor {

    public MyConfirmationGate(MeterRegistry registry, ApplicationContext ctx) {
        super(registry, ctx);
    }

    @Override
    protected Set<String> getAdditionalMutationTools() {
        return Set.of("bulk_export");   // extra tools not annotated with @AgentTool
    }

    @Override
    protected String getBlockedMessage() {
        return "Please confirm this sensitive operation before I proceed.";
    }
}
```

### RagAdvisor — zero-config wiring

Declare an `AgentRetriever` bean and `RagAdvisor` is auto-configured with default settings:

```java
@Bean
public AgentRetriever ragRetriever(VectorStore vectorStore) {
    return new SpringAiVectorStoreRetriever(vectorStore);
}
// No @Bean RagAdvisor needed — auto-wired with maxDocuments=5, minScore=0.0
```

Override to customise:

```java
@Bean
public RagAdvisor ragAdvisor(AgentRetriever retriever) {
    return RagAdvisor.builder(retriever)
            .maxDocuments(8)
            .minScore(0.75)
            .contextPrefix("\n[RELEVANT KNOWLEDGE]\n")
            .contextSuffix("\n[END KNOWLEDGE]\n")
            .build();
}
```

### MemoryAdvisor

```java
@Bean
public MemoryAdvisor memoryAdvisor(AgentMemoryManager memoryManager) {
    return MemoryAdvisor.builder(memoryManager)
            .maxWorkflowContentLength(2000)
            .injectPrefix("\n<!-- AGENT MEMORY -->\n")
            .build();
}
```

### CircuitBreakerAdvisor

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

### RateLimitAdvisor

```java
@Bean
public RateLimitAdvisor rateLimitAdvisor() {
    return RateLimitAdvisor.builder()
            .requestsPerMinute(30)
            .strategy(RateLimitAdvisor.Strategy.PER_SESSION)
            .build();
}
```

## Automatic Session Expiry

Enable the built-in scheduler to transition idle sessions to `EXPIRED`:

```yaml
agent:
  session:
    expiry:
      enabled: true
      idle-hours: 24
      check-interval-ms: 3600000
```

`DefaultSessionExpiryScheduler` is auto-wired when this flag is `true` and JPA is present. It expires `ACTIVE` and `PAUSED` sessions idle longer than `idle-hours`. Override with your own `BaseSessionExpiryScheduler<S>` bean for custom post-expiry hooks (e.g. closing SSE sinks):

```java
@Component
public class MyExpiryScheduler extends BaseSessionExpiryScheduler<MySessionDO> {

    public MyExpiryScheduler(MySessionRepository repo) { super(repo); }

    @Override protected long getSessionExpiryHours() { return 12; }
    @Override protected List<String> getExpirableStatuses() {
        return List.of(SessionStatus.ACTIVE.name());
    }
    @Override protected String getExpiredStatus() { return SessionStatus.EXPIRED.name(); }

    @Override
    protected void onSessionExpired(MySessionDO session) {
        sseRegistry.close(session.getSessionId());
        super.onSessionExpired(session);
    }

    @Scheduled(fixedDelayString = "${agent.session.expiry.check-interval-ms:3600000}")
    public void runExpiry() { expireInactiveSessions(); }
}
```

## Custom LLM Headers

Relay supports custom outbound headers at both global and per-model levels.

```yaml
relay:
  llm:
    custom-headers:
      X-TENANT-ID: self-serve
      X-PLATFORM: smart-serve-agent

    reasoning-model:
      provider: openai
      model: gpt-5.2
      version: "2025-12-11"
      api-version: "2025-04-01-preview"
      headers:
        X-CLIENT-ID: onboarding-reasoning

    utility-model:
      provider: openai
      model: gpt-5.2-mini
      headers:
        X-CLIENT-ID: onboarding-utility

    providers:
      - provider: anthropic
        model: claude-sonnet-4
        headers:
          X-CLIENT-ID: onboarding-anthropic
```

Header merge order (lowest to highest precedence):

1. Provider auth/default headers from `LlmProvider`
2. `relay.llm.custom-headers` (global)
3. Per-model `headers` (reasoning / utility / providers[])
4. `LlmGatewayHeadersContributor` beans (final override layer)

If the same key appears multiple times, the later layer overrides the earlier value.

## Agent-to-Agent (A2A) Communication

HTTP-native protocol for multi-agent fan-out with SSE and JSON support.

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
```

### A2A Authentication

Three concrete `A2AAuthContributor` implementations are provided. Declare as `@Bean` and they are picked up automatically:

```java
// Bearer token (one agent)
@Bean
A2AAuthContributor bearerAuth(@Value("${inv.token}") String t) {
    return new StaticBearerTokenA2AAuthContributor("inventory-agent", t);
}

// Bearer tokens (multiple agents)
@Bean
A2AAuthContributor multiAgentBearerAuth() {
    return StaticBearerTokenA2AAuthContributor.forAgents(Map.of(
            "inventory-agent",   System.getenv("INV_TOKEN"),
            "fulfillment-agent", System.getenv("FUL_TOKEN")));
}

// Custom API key header
@Bean
A2AAuthContributor apiKeyAuth(@Value("${pricing.key}") String k) {
    return new ApiKeyA2AAuthContributor("pricing-agent", "X-Api-Key", k);
}

// HTTP Basic auth
@Bean
A2AAuthContributor basicAuth(
        @Value("${svc.user}") String u, @Value("${svc.pass}") String p) {
    return new BasicAuthA2AAuthContributor("legacy-agent", u, p);
}

// Custom (dynamic tokens, OAuth2, etc.)
@Bean
A2AAuthContributor dynamicAuth(TokenService tokens) {
    return (headers, agentName) -> headers.setBearerAuth(tokens.getToken(agentName));
}
```

### Sub-agent wiring

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

## Memory System

```java
// Store
memory.remember(MemoryEntry.builder()
        .sessionId(sessionId).userId(userId)
        .type(MemoryType.ENTITY).content(fact).build());

// Recall
List<MemoryEntry> relevant = memory.recall(sessionId, userId, MemoryType.WORKFLOW, query, 5);

// Persona
PersonaMemory persona = PersonaMemory.empty(userId)
        .withPreference("prefers concise bullet-point responses")
        .withGoal("reduce quarterly operating costs by 15%")
        .withAttribute("role", "Operations Manager");
```

## Reasoning Strategies

| Strategy         | Class                    | When to use                               |
|------------------|--------------------------|-------------------------------------------|
| Least-to-most    | `LeastToMostSolver`      | Multi-step decomposition                  |
| Self-consistency | `SelfConsistencyRunner`  | High-stakes decisions (majority vote)     |
| Tree-of-thoughts | `TreeOfThoughtsExplorer` | Exploratory / creative tasks              |
| Decomposition    | `DecomposedPromptRunner` | Complex queries with independent subtasks |

## Multi-tenant isolation

```java
@Component
public class HeaderTenantResolver implements TenantResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        return request.getHeader("X-Tenant-ID");
    }
}
```

## Module dependencies

```
relay-orchestrator
  ├── relay-core
  ├── relay-cache
  ├── relay-store
  ├── relay-llm
  ├── spring-boot-starter-web
  ├── spring-boot-starter-aop
  ├── resilience4j-circuitbreaker
  ├── spring-ai-vector-store (optional — for RagAdvisor)
  ├── spring-boot-starter-data-redis (optional — for Redis cache/dedup)
  └── spring-boot-starter-data-jpa (optional — for JPA stores + session expiry)
```

See the [root README](../README.md) for full documentation.
