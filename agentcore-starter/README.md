# agentcore-starter

**Convenience starter — a single dependency that pulls in all Agent Core modules.**

Use this when you want the full stack and don't need to cherry-pick individual modules.

## Dependency

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
</dependencies>
```

## What it pulls in

| Module                   | Description                                          |
|--------------------------|------------------------------------------------------|
| `agentcore-core`         | Domain model, session context, DTOs                  |
| `agentcore-cache`        | Caffeine / Redis caching                             |
| `agentcore-store`        | Persistence interfaces and JPA adapters              |
| `agentcore-llm`          | ChatClient registry, tools, streaming                |
| `agentcore-orchestrator` | Advisors, A2A, memory, auto-configuration            |

## Selective usage

If you only need specific capabilities, depend on individual modules instead:

```xml
<!-- Just the domain model -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-core</artifactId>
</dependency>

<!-- LLM integration + core -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-llm</artifactId>
</dependency>

<!-- Full stack without the convenience starter -->
<dependency>
    <groupId>io.agentcore</groupId>
    <artifactId>agentcore-orchestrator</artifactId>
</dependency>
```

See the [root README](../README.md) for the complete getting-started guide and feature documentation.
