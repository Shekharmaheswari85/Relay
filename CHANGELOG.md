# Changelog

All notable changes to Agent Core are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html)

---

## [Unreleased]

### Added
- Multi-module Maven structure (agentcore-core, agentcore-cache, agentcore-store, agentcore-llm, agentcore-orchestrator)
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
- 8 Spring AI advisors: Memory, Reflection, RateLimit, CircuitBreaker, Confirmation, Fallback, Thinking, BaseAudit
- Agent-to-Agent (A2A) multi-agent orchestration with HTTP + SSE streaming
- Pluggable storage backends: in-memory, JPA/Hibernate, Redis
- Tree of Thoughts, Self-Consistency, Least-to-Most, Decomposed Prompt reasoning strategies
- RAG integration via Spring AI vector store
- Micrometer-based observability (metrics + distributed tracing)
- Resilience4j circuit breaker and rate limiting
- `@AgentTool` annotation for auto-discovery of tool beans
- SpotBugs and PMD static analysis integrated into `mvn verify`
- Virtual thread support (Project Loom, Java 21)

---

[Unreleased]: https://github.com/agentcore/agentcore/compare/v1.0.3...HEAD
[1.0.3]: https://github.com/agentcore/agentcore/releases/tag/v1.0.3
