# agentcore-llm

**LLM integration layer — ChatClient registry, prompt loading, SSE streaming, tool framework, and AOP interceptors.**

This module bridges Spring AI's `ChatClient` with Agent Core's tool, streaming, and observability infrastructure.

## What's inside

| Package          | Contents                                                                                                           |
|------------------|--------------------------------------------------------------------------------------------------------------------|
| `llm/`           | `ChatClientRegistry`, `LlmModelConfig`, `LlmProvider`, `ModelTier`, `CompletionsPathStrategy`                      |
| `prompt/`        | `PromptLoader`, `PromptRepository`, `ClasspathPromptRepository`, `BasePromptBuilder`                               |
| `stream/`        | `SseStreamHandler`, `PipelineEmitter`, `ThinkingStreamParser`, `ToolProgressPublisher`                             |
| `tool/`          | `@AgentTool`, `ToolCategory`, `ToolContract`, `ToolExecutionSupport`, `BaseToolDecider`, `AutoDiscoveryToolConfig` |
| `aspect/`        | `BaseToolExecutionAspect`, `BaseMcpCallInterceptor`, `DefaultMcpCallInterceptor`                                   |
| `filter/`        | `BaseMcpSseAuthFilter`, `McpSseAuthFilter`                                                                         |
| `observability/` | `AgentObservabilityService`                                                                                        |
| `thread/`        | `VirtualThreadTaskExecutorUtil`                                                                                    |

## Dependency

```xml
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-llm</artifactId>
</dependency>

<!-- Your Spring AI provider -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

## ChatClientRegistry

Manages multiple named `ChatClient` instances — one per LLM provider or model tier:

```java
@Autowired
private ChatClientRegistry chatClientRegistry;

// Get the reasoning-tier client (gpt-4o by default)
ChatClient reasoningClient = chatClientRegistry.get(ModelTier.REASONING);

// Get the utility-tier client (gpt-4o-mini by default)
ChatClient utilityClient = chatClientRegistry.get(ModelTier.UTILITY);

// Get a named client
ChatClient customClient = chatClientRegistry.get("my-custom-model");
```

Configuration:

```yaml
agent:
  llm:
    gateway-base-url: https://api.openai.com
    api-key: ${LLM_API_KEY}
    reasoning-model:
      provider: openai
      model: gpt-4o
    utility-model:
      provider: openai
      model: gpt-4o-mini
```

## Tool System

Mark any Spring bean method as an agent tool:

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
        description = "Creates a new purchase order — requires user confirmation",
        category    = ToolCategory.MUTATION)
    public PurchaseOrder createPurchaseOrder(String sku, int quantity) { ... }
}
```

- **`QUERY`** — executes immediately with result caching.
- **`MUTATION`** — intercepted by `ConfirmationGateAdvisor` in `agentcore-orchestrator`; requires explicit user confirmation.

`AutoDiscoveryToolConfig` scans for all `@AgentTool`-annotated beans and registers them with Spring AI's tool registry automatically — no manual wiring.

## Prompt Loading

Load prompts from the classpath with variable substitution:

```java
@Autowired
private PromptLoader promptLoader;

// Loads src/main/resources/prompts/my-agent.txt
String prompt = promptLoader.load("prompts/my-agent.txt");

// With placeholders
String prompt = promptLoader.load("prompts/my-agent.txt",
    Map.of("agentName", "inventory-bot", "tenantId", "acme"));
```

## SSE Streaming

`SseStreamHandler` handles the full pipeline from LLM token to browser:

```java
@Service
public class MyStreamingAgent {

    private final SseStreamHandler streamHandler;

    public void stream(String query, SseEmitter emitter) {
        PipelineEmitter pipeline = new PipelineEmitter(emitter);

        chatClient.prompt().user(query).stream().chatResponse()
                .doOnNext(response -> streamHandler.appendReadableStreamChunk(
                        response.getResult().getOutput().getText(), pipeline, context))
                .blockLast();
    }
}
```

**What `SseStreamHandler` does automatically:**

- **Smart chunking** — buffers tokens; flushes at sentence boundaries or 240-char overflow
- **Sanitization** — strips SSE wire artifacts, masks internal session IDs
- **Thinking events** — emits `type=thinking` with token usage and latency
- **Confirmation detection** — recognises confirmation-request language
- **Cached replay** — `chunkAssistantForUi(fullText)` replays stored responses without re-calling the LLM

## Observability

```java
@Autowired
private AgentObservabilityService observability;

observability.setMdcContext(sessionId, agentName);
observability.recordLlmCall("openai", "gpt-4o", "success");
observability.recordToolCall("check_inventory", "success", Duration.ofMillis(120));
```

Emits Micrometer metrics: `agent.llm.calls`, `agent.llm.duration`, `agent.tool.calls`, `agent.tool.duration`.

## Virtual Threads

`VirtualThreadTaskExecutorUtil` creates a Spring `TaskExecutor` backed by Java 21 virtual threads. Activated automatically when `agent.virtual-threads.enabled: true` (default).

## Test utilities (test scope)

`agentcore-llm` includes test helpers in `src/test/java/io/agentcore/test`:

| Class             | Purpose                                                   |
|-------------------|-----------------------------------------------------------|
| `MockChatModel`   | Scripted `ChatModel` — enqueue canned responses for tests |
| `SseEventCaptor`  | Collects all SSE events emitted during a test run         |

## Module dependencies

```
agentcore-llm
  └── agentcore-core
  └── agentcore-cache
  └── spring-boot-starter-web
  └── spring-boot-starter-aop
  └── spring-boot-starter-actuator
  └── spring-ai-starter-model-openai (optional)
  └── spring-ai-vector-store (optional)
  └── resilience4j-circuitbreaker
  └── micrometer-core, micrometer-tracing
  └── httpclient5
```

See the [root README](../README.md) for full documentation.
