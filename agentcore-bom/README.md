# agentcore-bom

**Bill of Materials — version alignment for all Agent Core modules**

Import this BOM into your project's `dependencyManagement` to lock all Agent Core module versions to a single, tested release without specifying versions on each individual dependency.

## Usage

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

After importing the BOM, declare Agent Core modules without versions:

```xml
<dependencies>
    <dependency>
        <groupId>io.agentcore</groupId>
        <artifactId>agentcore-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.agentcore</groupId>
        <artifactId>agentcore-llm</artifactId>
    </dependency>
    <!-- add other modules as needed -->
</dependencies>
```

## Managed artifacts

| Artifact ID              | Description                           |
|--------------------------|---------------------------------------|
| `agentcore-core`         | Domain model, DTOs, session context   |
| `agentcore-cache`        | Caching abstractions                  |
| `agentcore-store`        | Persistence, repositories             |
| `agentcore-llm`          | LLM integration, tools, streaming     |
| `agentcore-orchestrator` | Advisors, A2A, auto-configuration     |
| `agentcore-starter`      | All-in-one starter                    |

See the [root README](../README.md) for full documentation.
