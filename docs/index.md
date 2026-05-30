# 🌌 Relay

Welcome to the documentation for **Relay** – an enterprise-grade agentic LLM orchestration framework built on top of Spring AI, featuring support for dynamic connectors, rich observability, and multi-agent hierarchies.

!!! note "Overview"
    Relay simplifies building robust, resilient, and observable AI agents. It leverages the latest Spring AI capabilities to deliver modular, customizable, and enterprise-ready features.

## 🚀 Key Features

*   **⚡ Native Spring AI Integration:** Configured dynamically using standard Spring Boot auto-configurations.
*   **🛠️ Dynamic Connector Architecture:** Register, manage, and execute tools, agents, and sub-agents at runtime.
*   **👁️ Observability & Tracking:** Full integration with Micrometer and specialized advisor tracing.
*   **📦 Robust Caching & Store:** Integrated caching layers for LLM responses and persistence of agent states.

## 🏁 Getting Started

To add Relay to your project, include the Maven dependency:

```xml
<dependency>
    <groupId>io.relay</groupId>
    <artifactId>relay-orchestrator</artifactId>
    <version>1.0.7-SNAPSHOT</version>
</dependency>
```

---

*Documentation built automatically with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/).*
