# Changelog

All notable changes to Agent Core are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html)

---

## [Unreleased]

### Added

- **RagAdvisor auto-wiring** — `RagAdvisor` is now automatically registered as a bean when the
  application provides an `AgentRetriever` bean. No `@Bean` declaration needed for default settings.
  Override by declaring your own `RagAdvisor` bean with `RagAdvisor.builder(retriever)`.

- **Automatic session expiry** — `DefaultSessionExpiryScheduler` activates when
  `agent.session.expiry.enabled=true`. Transitions idle `ACTIVE` and `PAUSED` sessions to
  `EXPIRED` after a configurable idle window. Configurable via `agent.session.expiry.idle-hours`
  and `agent.session.expiry.check-interval-ms`. Override by declaring your own
  `BaseSessionExpiryScheduler` bean.

- **Confirmation gate REST protocol** — `POST /sessions/{sessionId}/confirm` endpoint added to
  `BaseAgentController`. Accepts `ConfirmMutationRequest { toolName, confirmed }` and streams back
  the agent continuation via SSE. `ConfirmationGateAdvisor` now detects the synthesised signal
  message, enriches context with `user_confirmed=true` on approval, and returns a clean rejection
  response on denial. Completes the full round-trip: `confirmation_required` SSE event → UI prompt
  → `/confirm` → agent continuation.

- **`ConfirmMutationRequest` DTO** — new DTO in `agentcore-core` describing the request body for
  the `/confirm` endpoint.

- **Concrete A2A auth implementations** — three `A2AAuthContributor` implementations ship with
  the framework:
  - `StaticBearerTokenA2AAuthContributor` — static Bearer token per agent, with `forAgents(Map)` factory
  - `ApiKeyA2AAuthContributor` — custom header (e.g. `X-Api-Key`) per agent, with `forAgents(headerName, Map)` factory
  - `BasicAuthA2AAuthContributor` — HTTP Basic credentials per agent, with `forAgents(Map<String, Credentials>)` factory

- **Tool result cache TTL** — `agent.cache.tool-ttl` property added to
  `AgentCoreProperties.CacheProperties`. When set, tool result entries use this dedicated `Duration`
  instead of the global `agent.cache.ttl`. `DefaultToolResultCache` accepts an optional `Duration`
  constructor argument wired from properties.

- **Observability common tags** — `agent.metrics.common-tags` property added. A `MeterFilter` bean
  (`agentCommonTagsMeterFilter`) injects configured key-value tags into every `agent.*` metric,
  enabling per-environment / per-service filtering in dashboards. Scoped to `agent.*` metrics only.
  Javadoc includes Prometheus and DataDog setup instructions.

- **`AgentCoreProperties.MetricsProperties`** — new nested properties class under `agent.metrics.*`
  with `enabled` (default `true`) and `common-tags` (default empty map).

- **`AgentCoreProperties.ExpiryProperties`** — new nested class under `agent.session.expiry.*`
  with `enabled`, `idle-hours`, and `check-interval-ms`.

- **`agent.cache.tool-ttl`** — new property on `CacheProperties` (type `Duration`, default `null`).

### Changed

- `DefaultToolResultCache` — new two-argument constructor `(AgentCache, Duration toolTtl)`.
  Existing no-arg-style wiring via `AgentCoreAutoConfiguration.toolResultCache()` now passes the
  configured `toolTtl` (or `null` to preserve original default-TTL behaviour).

- `AgentCoreAutoConfiguration.toolResultCache()` — now reads `agent.cache.tool-ttl` and passes it
  to `DefaultToolResultCache`.

- `AgentCoreAutoConfiguration` — new `SessionExpiryConfiguration` nested class enables
  `@EnableScheduling` conditionally (only when `agent.session.expiry.enabled=true` and JPA is
  present), avoiding interference with applications that manage their own scheduler.

- Root `README.md` — rewritten with Mermaid architecture diagrams (module dependency graph,
  full request lifecycle sequence diagram), updated configuration reference, A2A auth examples,
  confirmation gate protocol documentation, and REST endpoint table.

### Multi-module Maven project structure
- GitHub Actions CI workflow for PR validation
- Tag-triggered release workflow with automatic GitHub Release creation
- SNAPSHOT publishing workflow on main push
- PR and issue templates

---

## [1.0.3] - 2025-01-01

### Added

- Initial release of Agent Core framework
- Production-ready Spring Boot 3.4 / Spring AI 1.0 scaffolding for AI agents
- 5-tier memory system: entity facts, personas, workflow, knowledge, context
- 8 Spring AI advisors: Memory, RateLimit, CircuitBreaker, ConfirmationGate, Fallback, Thinking, BaseAudit, RAG
- Agent-to-Agent (A2A) multi-agent orchestration with HTTP + SSE streaming
- Pluggable storage backends: in-memory, JPA/Hibernate, Redis
- Tree of Thoughts, Self-Consistency, Least-to-Most, Decomposed Prompt reasoning strategies
- RAG integration via `AgentRetriever` SPI and `SpringAiVectorStoreRetriever` adapter
- Micrometer-based observability (metrics + distributed tracing)
- Resilience4j circuit breaker and rate limiting
- `@AgentTool` annotation with `ToolCategory` (QUERY / MUTATION) for auto-discovery
- SpotBugs and PMD static analysis integrated into `mvn verify`
- Virtual thread support (Project Loom, Java 21)
- In-memory test utilities: `MockChatModel`, `SseEventCaptor`, `InMemoryAgentSessionStore`,
  `InMemoryAgentAuditLogStore`, `InMemoryToolResultCacheStore`

---

[Unreleased]: https://github.com/Shekharmaheswari85/Agent-Core/compare/v1.0.3...HEAD
[1.0.3]: https://github.com/Shekharmaheswari85/Agent-Core/releases/tag/v1.0.3
