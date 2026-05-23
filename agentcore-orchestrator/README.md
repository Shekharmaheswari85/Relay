# agentcore-orchestrator

**Full orchestration layer — advisors, A2A protocol, agent routing, memory, RAG, reasoning strategies, and Spring Boot auto-configuration.**

This is the top-level Agent Core module. It wires together every other module, provides all 8 built-in advisors, and registers the auto-configuration that activates Agent Core in any Spring Boot application.

## What's inside

| Package        | Contents                                                                                                   |
|----------------|------------------------------------------------------------------------------------------------------------|
| `advisor/`     | 8 built-in advisors: `BaseAuditAdvisor`, `CircuitBreakerAdvisor`, `ConfirmationGateAdvisor`, `FallbackModelAdvisor`, `MemoryAdvisor`, `RagAdvisor`, `RateLimitAdvisor`, `ReflectionAdvisor`, `ThinkingAdvisor` |
| `a2a/`         | `AgentClient`, `AgentClientRegistry`, `A2AHttpClient`, `A2AAuthContributor`, `AgentCard`, `AgentCardController`, `SseEventParser` |
| `agent/`       | `BaseSubAgent`, `AgentExecutionContext`                                                                    |
| `audit/`       | `AgentRequestTrace`                                                                                        |
| `config/`      | `AgentCoreAutoConfiguration`, `ChatClientAutoConfiguration`, `AgentCoreProperties`, `EnableAgentCore`     |
| `executor/`    | `AgentExecutor`, `AgentRegistry`, `BaseAgentRuntimeService`                                               |
| `guard/`       | `MutationToolGuard`, `ToolSessionGuard`                                                                    |
| `lifecycle/`   | `BaseSessionLifecycleService`                                                                              |
| `memory/`      | `AgentMemoryManager`, `InMemoryAgentMemoryManager`, `MemoryEntry`, `MemoryType`                           |
| `memory/entity`| `EntityFact`, `EntityMemoryStore`, `InMemoryEntityMemoryStore`                                            |
| `memory/persona`| `PersonaMemory`, `PersonaStore`, `InMemoryPersonaStore`                                                  |
| `orchestrator/`| `BaseAgentOrchestrator`                                                                                   |
| `parser/`      | `BaseMessageParser`                                                                                        |
| `pipeline/`    | `BaseAgentPipelineExecutor`                                                                               |
| `rag/`         | `RagAdvisor`, `AgentRetriever`, `SpringAiVectorStoreRetriever`, `RetrievedDocument`                       |
| `reasoning/`   | `LeastToMostSolver`, `SelfConsistencyRunner`, `TreeOfThoughtsExplorer`, `DecomposedPromptRunner`          |
| `router/`      | `BaseIntentRouter`, `LlmIntentRouter`                                                                     |
| `scheduler/`   | `BaseSessionExpiryScheduler`                                                                              |
| `session/`     | `TenantResolver`                                                                                           |
| `summary/`     | `SessionSummarizer`, `BaseLlmSessionSummarizer`, `SummaryPromptProvider`                                  |
| `web/`         | `BaseAgentController`                                                                                      |

## Dependency

```xml
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-orchestrator</artifactId>
</dependency>
```

## Auto-configuration

`AgentCoreAutoConfiguration` activates when `agentcore-orchestrator` is on the classpath. It wires:

- `AgentMemoryManager` (in-memory by default)
- `AgentCache` (Caffeine by default; Redis when configured)
- `ToolDedupCache`
- `AgentRegistry` (scans for `@Component BaseSubAgent` beans)
- `AgentCardController` (when A2A is enabled)
- JPA store beans (when `spring-boot-starter-data-jpa` is on the classpath and concrete repos are defined)
- Redis cache beans (when `spring-boot-starter-data-redis` is on the classpath)

Alternatively, use `@EnableAgentCore` on your main application class for explicit opt-in:

```java
@SpringBootApplication
@EnableAgentCore
public class MyApplication { ... }
```

## Advisor Chain

Eight advisors execute in a deterministic order:

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

### MemoryAdvisor

```java
@Bean
public MemoryAdvisor memoryAdvisor(AgentMemoryManager memoryManager) {
    return MemoryAdvisor.builder(memoryManager)
            .maxWorkflowContentLength(2000)
            .injectPrefix("\n<!-- AGENT MEMORY -->\n")
            .build();
}

// Override to extract and persist structured facts from LLM responses
@Override
protected List<EntityFact> extractEntities(String sessionId, String userId, String response) {
    return entityExtractor.extract(response, sessionId, userId);
}
```

### RagAdvisor

```java
@Bean
public RagAdvisor ragAdvisor(AgentRetriever retriever) {
    return RagAdvisor.builder(retriever)
            .maxDocuments(5)
            .minScore(0.75)
            .contextPrefix("\n[RELEVANT DOCUMENTS]\n")
            .build();
}
```

Use the built-in Spring AI `VectorStore` adapter:

```java
@Bean
public AgentRetriever retriever(VectorStore vectorStore) {
    return new SpringAiVectorStoreRetriever(vectorStore);
}
```

### ConfirmationGateAdvisor

Automatically activated — requires no bean registration. Blocks any `@AgentTool(category = MUTATION)` until the user confirms:

```
User: "Delete all records from last quarter."
Agent: "This will permanently delete 1,247 records. Please confirm to proceed."
User: "Yes, confirmed."
Agent: [executes deletion]
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

## Agent-to-Agent (A2A) Communication

HTTP-native protocol for multi-agent fan-out with SSE and JSON support:

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

```java
// Wrap as a sub-agent (recommended)
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

```java
// Or call directly
AgentClient inventoryAgent = registry.get("inventory-agent");
String remoteSession = inventoryAgent.createSession(agentId, createdBy);
try {
    inventoryAgent.sendMessage(remoteSession, message, event ->
        System.out.println(event.type() + ": " + event.data()));
} finally {
    inventoryAgent.deleteSession(remoteSession);
}
```

Custom auth headers:

```java
@Component
public class BearerTokenContributor implements A2AAuthContributor {

    @Override
    public void contribute(HttpHeaders headers, String agentName) {
        headers.setBearerAuth(tokenService.getToken(agentName));
    }
}
```

## Multi-Agent Orchestration

Register sub-agents as Spring beans; the orchestrator routes automatically:

```java
@Component
@Order(1)
public class DataCollectionAgent implements BaseSubAgent<MySession, WorkflowStep> {

    @Override
    public boolean canHandle(MySession session, WorkflowStep step) {
        return step == WorkflowStep.COLLECT_DATA;
    }

    @Override
    public String systemPrompt(MySession session, Map<String, Object> context) {
        return "You are a data collection specialist...";
    }

    @Override
    public List<String> ownedTools() { return List.of("fetch_records"); }
}
```

## Memory System

```java
@Autowired
private AgentMemoryManager memory;

// Store
memory.remember(MemoryEntry.builder()
        .sessionId(sessionId).userId(userId)
        .type(MemoryType.ENTITY).content(fact).build());

// Recall
List<MemoryEntry> relevant = memory.recall(sessionId, userId, MemoryType.WORKFLOW, query, 5);

// Wipe session memory
memory.forgetSession(sessionId);
```

### PersonaMemory

```java
PersonaMemory persona = PersonaMemory.empty(userId)
        .withPreference("prefers concise bullet-point responses")
        .withGoal("reduce quarterly operating costs by 15%")
        .withAttribute("role", "Operations Manager");

// Injected automatically by MemoryAdvisor; or format manually:
String fragment = persona.toPromptFragment();
```

### EntityFact

```java
EntityFact fact = new EntityFact(
        "SKU-12345", "PRODUCT", "reorder_threshold", "150 units",
        sessionId, userId, Instant.now());

// Formats as: PRODUCT(SKU-12345).reorder_threshold = 150 units
String fragment = fact.toPromptFragment();
```

## Reasoning Strategies

| Strategy         | Class                    | When to use                              |
|------------------|--------------------------|------------------------------------------|
| Least-to-most    | `LeastToMostSolver`      | Multi-step decomposition                 |
| Self-consistency | `SelfConsistencyRunner`  | High-stakes decisions (majority vote)    |
| Tree-of-thoughts | `TreeOfThoughtsExplorer` | Exploratory / creative tasks             |
| Decomposition    | `DecomposedPromptRunner` | Complex queries with independent subtasks |

## Session Lifecycle

`BaseSessionLifecycleService` is the primary entry point for agent service classes:

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

## Multi-tenant isolation

```java
@Component
public class HeaderTenantResolver implements TenantResolver {

    @Override
    public String resolveTenantId(HttpServletRequest request) {
        return request.getHeader("X-Tenant-ID");
    }
}
```

## Module dependencies

```
agentcore-orchestrator
  └── agentcore-core
  └── agentcore-cache
  └── agentcore-store
  └── agentcore-llm
  └── spring-boot-starter-web
  └── spring-boot-starter-aop
  └── spring-ai-starter-model-openai (optional)
  └── spring-ai-vector-store (optional)
  └── spring-boot-starter-data-redis (optional)
  └── spring-boot-starter-data-jpa (optional)
  └── resilience4j-circuitbreaker
```

See the [root README](../README.md) for full documentation.
