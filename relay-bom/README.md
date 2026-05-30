# relay-bom

**Bill of Materials — version alignment for all Relay modules**

Import this BOM into your project's `dependencyManagement` to lock all Relay module versions to a single, tested release without specifying versions on each individual dependency.

## Usage

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.shekharmaheswari85</groupId>
            <artifactId>relay-bom</artifactId>
            <version>1.0.7-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

After importing the BOM, declare Relay modules without versions:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.shekharmaheswari85</groupId>
        <artifactId>relay-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.shekharmaheswari85</groupId>
        <artifactId>relay-llm</artifactId>
    </dependency>
    <!-- add other modules as needed -->
</dependencies>
```

## Managed artifacts

| Artifact ID              | Description                           |
|--------------------------|---------------------------------------|
| `relay-core`         | Domain model, DTOs, session context   |
| `relay-cache`        | Caching abstractions                  |
| `relay-store`        | Persistence, repositories             |
| `relay-llm`          | LLM integration, tools, streaming     |
| `relay-orchestrator` | Advisors, A2A, auto-configuration     |
See the [root README](../README.md) for full documentation.
