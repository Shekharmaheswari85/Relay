/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.observability;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.relay.session.ActiveAgentHolder;
import io.relay.session.SessionContextHolder;
import io.relay.session.TenantContextHolder;
import io.relay.tool.AgentTool;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;

/**
 * Centralised observability service that gives every agent module a single,
 * consistent
 * surface for structured logging (MDC), Micrometer metrics, and distributed
 * tracing.
 *
 * <p>
 * Inject this service wherever agent code needs to record business events,
 * measure
 * latency, or propagate trace context — rather than calling Micrometer or SLF4J
 * MDC APIs
 * directly. All methods are null-safe and silently tolerate missing optional
 * dependencies
 * (e.g., no {@link Tracer} on the classpath).
 *
 * <h3>MDC keys</h3>
 * <p>
 * The following SLF4J MDC keys are managed by this service and appear in every
 * log
 * statement emitted while they are set:
 * <ul>
 * <li>{@link #MDC_SESSION_ID} ({@code "sessionId"}) — active agent session ID,
 * read
 * from {@link io.relay.session.SessionContextHolder}</li>
 * <li>{@link #MDC_AGENT_NAME} ({@code "agentName"}) — name of the currently
 * routing
 * sub-agent, read from {@link io.relay.session.ActiveAgentHolder}</li>
 * <li>{@link #MDC_TOOL_NAME} ({@code "toolName"}) — name of the tool being
 * executed</li>
 * </ul>
 *
 * <h3>Metrics published</h3>
 * <table>
 * <tr>
 * <th>Metric name</th>
 * <th>Type</th>
 * <th>Tags</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td>{@code agent.session.count}</td>
 * <td>Counter</td>
 * <td>{@code event}, {@code agentId}</td>
 * <td>Session lifecycle events: started, completed, failed, resumed</td>
 * </tr>
 * <tr>
 * <td>{@code agent.llm.calls}</td>
 * <td>Counter</td>
 * <td>{@code provider}, {@code model}, {@code outcome}</td>
 * <td>LLM invocation counts</td>
 * </tr>
 * <tr>
 * <td>{@code agent.llm.duration}</td>
 * <td>Timer</td>
 * <td>{@code provider}, {@code model}, {@code outcome}</td>
 * <td>End-to-end LLM call duration</td>
 * </tr>
 * <tr>
 * <td>{@code agent.tool.calls}</td>
 * <td>Counter</td>
 * <td>{@code tool}, {@code outcome}</td>
 * <td>Tool invocation counts</td>
 * </tr>
 * <tr>
 * <td>{@code agent.tool.duration}</td>
 * <td>Timer</td>
 * <td>{@code tool}, {@code outcome}</td>
 * <td>Tool execution duration</td>
 * </tr>
 * <tr>
 * <td>{@code agent.handoff.count}</td>
 * <td>Counter</td>
 * <td>{@code from}, {@code to}</td>
 * <td>Sub-agent handoff events</td>
 * </tr>
 * <tr>
 * <td>{@code agent.cache.operations}</td>
 * <td>Counter</td>
 * <td>{@code operation}, {@code type}</td>
 * <td>Cache hit/miss/put/evict events</td>
 * </tr>
 * </table>
 *
 * <h3>Distributed tracing</h3>
 * <p>
 * When a Micrometer Tracing bridge is present on the classpath
 * (e.g., {@code micrometer-tracing-bridge-otel} or
 * {@code micrometer-tracing-bridge-brave}),
 * a {@link Tracer} is auto-wired and span methods produce real spans. When
 * absent, all
 * span methods return {@link io.micrometer.tracing.Span#NOOP} and are
 * effectively no-ops.
 *
 * <h3>Typical usage pattern</h3>
 * 
 * <pre>{@code
 * observabilityService.setMdcContext(sessionId, agentName);
 * Timer.Sample timer = observabilityService.startLlmTimer();
 * Span span = observabilityService.startRouteSpan(agentName, sessionId);
 * try {
 *     String result = chatClient.prompt().user(input).call().content();
 *     observabilityService.recordLlmCall("openai", "gpt-4o", "success");
 *     observabilityService.stopLlmTimer(timer, "openai", "gpt-4o", "success");
 *     return result;
 * } catch (Exception e) {
 *     observabilityService.recordLlmCall("openai", "gpt-4o", "error");
 *     observabilityService.stopLlmTimer(timer, "openai", "gpt-4o", "error");
 *     observabilityService.endSpan(span, e);
 *     throw e;
 * } finally {
 *     observabilityService.endSpan(span, null);
 *     observabilityService.clearMdcContext();
 * }
 * }</pre>
 *
 * <p>
 * This service is thread-safe and safe for concurrent use from virtual threads.
 */
@Service
@Slf4j
public class AgentObservabilityService {

    public static final String MDC_SESSION_ID = "sessionId";
    public static final String MDC_AGENT_NAME = "agentName";
    public static final String MDC_TOOL_NAME = "toolName";

    private final MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    private final Map<String, String> toolCategoryCache = new ConcurrentHashMap<>();
    private volatile boolean toolCacheInitialized = false;

    /**
     * Optional distributed tracer. Auto-wired when a Micrometer Tracing bridge is
     * on the
     * classpath (e.g., {@code micrometer-tracing-bridge-otel} or
     * {@code micrometer-tracing-bridge-brave}). When absent, all span methods
     * return
     * {@link io.micrometer.tracing.Span#NOOP} and produce no trace data.
     */
    @Nullable
    private Tracer tracer;

    public AgentObservabilityService(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Autowired(required = false)
    public void setTracer(final @Nullable Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Populates MDC with the session ID and agent name from the current thread's
     * {@link io.relay.session.SessionContextHolder} and
     * {@link io.relay.session.ActiveAgentHolder} respectively.
     *
     * <p>
     * Call this at the start of each request-handling method, or after any thread
     * or
     * scheduler boundary where MDC is not automatically restored (e.g., after
     * submitting
     * work to a virtual-thread executor). Values that are null or blank are
     * silently skipped.
     */
    public void setMdcContext() {
        String sessionId = SessionContextHolder.get();
        String agentName = ActiveAgentHolder.get();

        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(MDC_SESSION_ID, sessionId);
        }
        if (agentName != null && !agentName.isBlank()) {
            MDC.put(MDC_AGENT_NAME, agentName);
        }
    }

    /**
     * Populates MDC with explicitly supplied values, bypassing the thread-local
     * holders.
     *
     * <p>
     * Use this overload when the session ID and agent name are already available in
     * the
     * calling scope (e.g., inside a scheduled job that receives them as
     * parameters).
     *
     * @param sessionId the session identifier to write to MDC; null or blank values
     *                  are skipped
     * @param agentName the sub-agent name to write to MDC; null or blank values are
     *                  skipped
     */
    public void setMdcContext(final String sessionId, final String agentName) {
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(MDC_SESSION_ID, sessionId);
        }
        if (agentName != null && !agentName.isBlank()) {
            MDC.put(MDC_AGENT_NAME, agentName);
        }
    }

    /**
     * Writes the tool name to MDC so that all log statements emitted during tool
     * execution
     * include a {@code toolName} field.
     *
     * <p>
     * Pair with {@link #clearMdcContext()} in a {@code finally} block to prevent
     * leakage.
     *
     * @param toolName the canonical tool name; null or blank values are skipped
     */
    public void setToolMdcContext(final String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            MDC.put(MDC_TOOL_NAME, toolName);
        }
    }

    /**
     * Removes all agent-managed MDC keys ({@link #MDC_SESSION_ID},
     * {@link #MDC_AGENT_NAME},
     * {@link #MDC_TOOL_NAME}) from the current thread's MDC context.
     *
     * <p>
     * Always call this in a {@code finally} block after any set-MDC call to prevent
     * context bleed-through to subsequent requests on the same thread.
     */
    public void clearMdcContext() {
        MDC.remove(MDC_SESSION_ID);
        MDC.remove(MDC_AGENT_NAME);
        MDC.remove(MDC_TOOL_NAME);
    }

    /**
     * Increments the {@code agent.session.count} counter for the given lifecycle
     * event.
     *
     * @param event a short lifecycle label; conventional values are
     *              {@code started},
     *              {@code completed}, {@code failed}, {@code resumed}
     */
    private Counter.Builder enrich(final Counter.Builder builder) {
        String tenantId = TenantContextHolder.get();
        if (tenantId != null && !tenantId.isBlank()) {
            builder.tag("tenantId", tenantId);
        }
        return builder;
    }

    private Timer.Builder enrich(final Timer.Builder builder) {
        String tenantId = TenantContextHolder.get();
        if (tenantId != null && !tenantId.isBlank()) {
            builder.tag("tenantId", tenantId);
        }
        return builder;
    }

    private void ensureToolCacheInitialized() {
        if (toolCacheInitialized) {
            return;
        }
        synchronized (this) {
            if (toolCacheInitialized) {
                return;
            }
            if (applicationContext != null) {
                try {
                    applicationContext.getBeansWithAnnotation(AgentTool.class).forEach((beanName, bean) -> {
                        AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(bean.getClass(),
                                AgentTool.class);
                        if (annotation == null) {
                            return;
                        }
                        String resolvedName = beanName;
                        for (var method : bean.getClass().getMethods()) {
                            var toolAnnotation = method
                                    .getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                            if (toolAnnotation != null) {
                                resolvedName = toolAnnotation.name().isBlank() ? method.getName()
                                        : toolAnnotation.name();
                                break;
                            }
                        }
                        toolCategoryCache.put(resolvedName, annotation.category().name());
                    });
                } catch (Exception ex) {
                    log.warn("Failed to pre-populate tool category cache: {}", ex.getMessage());
                }
            }
            toolCacheInitialized = true;
        }
    }

    private String getToolCategory(final String toolName) {
        ensureToolCacheInitialized();
        return toolCategoryCache.getOrDefault(toolName, "UNKNOWN");
    }

    /**
     * Increments the {@code agent.session.count} counter for the given lifecycle
     * event.
     *
     * @param event a short lifecycle label; conventional values are
     *              {@code started},
     *              {@code completed}, {@code failed}, {@code resumed}
     */
    public void recordSessionEvent(final String event) {
        enrich(Counter.builder("agent.session.count")
                .tag("event", event)
                .description("Agent session lifecycle events"))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the {@code agent.session.count} counter, tagging both the
     * lifecycle event
     * and the agent type for per-agent breakdown in dashboards.
     *
     * @param event   a short lifecycle label; conventional values are
     *                {@code started},
     *                {@code completed}, {@code failed}, {@code resumed}
     * @param agentId the agent type identifier (e.g., {@code "onboarding-market"});
     *                null is
     *                recorded as {@code "unknown"}
     */
    public void recordSessionEvent(final String event, final String agentId) {
        enrich(Counter.builder("agent.session.count")
                .tag("event", event)
                .tag("agentId", agentId != null ? agentId : "unknown")
                .description("Agent session lifecycle events"))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the {@code agent.llm.calls} counter with provider, model, and
     * outcome tags.
     *
     * @param provider the LLM provider identifier (e.g., {@code "azure-openai"},
     *                 {@code "vertex-ai"}); null is recorded as {@code "unknown"}
     * @param model    the model name (e.g., {@code "gpt-4o"}); null is recorded as
     *                 {@code "unknown"}
     * @param outcome  the call outcome; conventional values are {@code success},
     *                 {@code error}, {@code timeout}
     */
    public void recordLlmCall(final String provider, final String model, final String outcome) {
        enrich(Counter.builder("relay.llm.calls")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("model", model != null ? model : "unknown")
                .tag("outcome", outcome)
                .description("LLM invocation counts"))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the {@code agent.llm.calls} counter using the model tier as the
     * model
     * identifier and {@code "spring-ai"} as the provider.
     *
     * <p>
     * Use this convenience overload when the specific provider and model name are
     * not
     * available at the call site (e.g., inside a generic advisor that operates on
     * tiers).
     *
     * @param modelTier the {@link io.relay.llm.ModelTier} name (e.g.,
     *                  {@code "UTILITY"},
     *                  {@code "REASONING"})
     * @param outcome   the call outcome; conventional values are {@code success},
     *                  {@code error}, {@code timeout}
     */
    public void recordLlmCall(final String modelTier, final String outcome) {
        recordLlmCall("spring-ai", modelTier, outcome);
    }

    /**
     * Starts a timer sample for measuring end-to-end LLM call duration.
     *
     * <p>
     * Store the returned sample and pass it to
     * {@link #stopLlmTimer(Timer.Sample, String, String, String)} when the call
     * completes
     * (or fails) to record the elapsed time against the {@code agent.llm.duration}
     * timer.
     *
     * @return a new {@link Timer.Sample} anchored to the current instant
     */
    public Timer.Sample startLlmTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Stops the given timer sample and records the elapsed duration against the
     * {@code agent.llm.duration} histogram with provider, model, and outcome tags.
     *
     * @param sample   the sample returned by {@link #startLlmTimer()}; must not be
     *                 null
     * @param provider the LLM provider identifier; null is recorded as
     *                 {@code "unknown"}
     * @param model    the model name; null is recorded as {@code "unknown"}
     * @param outcome  the call outcome; conventional values are {@code success},
     *                 {@code error}, {@code timeout}
     */
    public void stopLlmTimer(
            final Timer.Sample sample, final String provider, final String model, final String outcome) {
        sample.stop(enrich(Timer.builder("relay.llm.duration")
                .tag("provider", provider != null ? provider : "unknown")
                .tag("model", model != null ? model : "unknown")
                .tag("outcome", outcome)
                .description("LLM call duration"))
                .register(meterRegistry));
    }

    /**
     * Stops the given timer sample and records the elapsed duration, using
     * {@code "spring-ai"}
     * as the provider and the tier name as the model identifier.
     *
     * @param sample    the sample returned by {@link #startLlmTimer()}; must not be
     *                  null
     * @param modelTier the {@link io.relay.llm.ModelTier} name used as the model
     *                  tag
     * @param outcome   the call outcome; conventional values are {@code success},
     *                  {@code error}, {@code timeout}
     */
    public void stopLlmTimer(final Timer.Sample sample, final String modelTier, final String outcome) {
        stopLlmTimer(sample, "spring-ai", modelTier, outcome);
    }

    /**
     * Increments the {@code agent.tool.calls} counter with tool name and outcome
     * tags.
     *
     * @param toolName the canonical tool name as declared in {@code @Tool}; must
     *                 not be null
     * @param outcome  the execution outcome; conventional values are
     *                 {@code success},
     *                 {@code error}, {@code skipped}
     */
    public void recordToolCall(final String toolName, final String outcome) {
        enrich(Counter.builder("agent.tool.calls")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .tag("category", getToolCategory(toolName))
                .description("Tool invocation counts"))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Starts a timer sample for measuring tool execution duration.
     *
     * <p>
     * Store the returned sample and pass it to
     * {@link #stopToolTimer(Timer.Sample, String, String)} when the tool finishes.
     *
     * @return a new {@link Timer.Sample} anchored to the current instant
     */
    public Timer.Sample startToolTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Stops the given timer sample and records the elapsed duration against the
     * {@code agent.tool.duration} histogram with tool name and outcome tags.
     *
     * @param sample   the sample returned by {@link #startToolTimer()}; must not be
     *                 null
     * @param toolName the canonical tool name; must not be null
     * @param outcome  the execution outcome; conventional values are
     *                 {@code success},
     *                 {@code error}, {@code skipped}
     */
    public void stopToolTimer(final Timer.Sample sample, final String toolName, final String outcome) {
        sample.stop(enrich(Timer.builder("agent.tool.duration")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .tag("category", getToolCategory(toolName))
                .description("Tool execution duration"))
                .register(meterRegistry));
    }

    /**
     * Increments the {@code agent.handoff.count} counter when the orchestrator
     * routes
     * execution from one sub-agent to another.
     *
     * @param fromAgent the name of the sub-agent that is handing off; null is
     *                  recorded as
     *                  {@code "none"} (e.g., for the initial routing)
     * @param toAgent   the name of the sub-agent that receives control; must not be
     *                  null
     */
    public void recordAgentHandoff(final String fromAgent, final String toAgent) {
        enrich(Counter.builder("agent.handoff.count")
                .tag("from", fromAgent != null ? fromAgent : "none")
                .tag("to", toAgent)
                .description("Sub-agent handoff events"))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the {@code agent.cache.operations} counter with operation and
     * cache-type tags.
     *
     * @param operation the operation performed; conventional values are
     *                  {@code hit},
     *                  {@code miss}, {@code put}, {@code evict}
     * @param cacheType identifies which cache was accessed; conventional values are
     *                  {@code tool}, {@code session}
     */
    public void recordCacheOperation(final String operation, final String cacheType) {
        enrich(Counter.builder("relay.cache.operations")
                .tag("operation", operation)
                .tag("type", cacheType)
                .description("Cache operation counts"))
                .register(meterRegistry)
                .increment();
    }

    /**
     * Starts and returns a new span named {@code agent.tool.execute} for the given
     * tool
     * invocation.
     *
     * <p>
     * The span carries two tags: {@code tool.name} and {@code session.id}. When no
     * {@link Tracer} is configured, returns {@link Span#NOOP} so callers need not
     * guard
     * against null.
     *
     * <p>
     * Always end the span in a {@code finally} block via
     * {@link #endSpan(Span, Throwable)}.
     *
     * @param toolName  the canonical tool name; null is recorded as
     *                  {@code "unknown"}
     * @param sessionId the active session identifier; null is recorded as
     *                  {@code "unknown"}
     * @return a running {@link Span} (real or no-op depending on tracing
     *         configuration)
     */
    public Span startToolSpan(final String toolName, final String sessionId) {
        if (tracer == null) {
            return Span.NOOP;
        }
        return tracer.nextSpan()
                .name("agent.tool.execute")
                .tag("tool.name", toolName != null ? toolName : "unknown")
                .tag("session.id", sessionId != null ? sessionId : "unknown")
                .start();
    }

    /**
     * Starts and returns a new span named {@code agent.route} for an orchestrator
     * routing
     * turn to the given sub-agent.
     *
     * <p>
     * The span carries two tags: {@code agent.name} and {@code session.id}. When no
     * {@link Tracer} is configured, returns {@link Span#NOOP}.
     *
     * <p>
     * Always end the span in a {@code finally} block via
     * {@link #endSpan(Span, Throwable)}.
     *
     * @param agentName the sub-agent name receiving control; null is recorded as
     *                  {@code "unknown"}
     * @param sessionId the active session identifier; null is recorded as
     *                  {@code "unknown"}
     * @return a running {@link Span} (real or no-op depending on tracing
     *         configuration)
     */
    public Span startRouteSpan(final String agentName, final String sessionId) {
        if (tracer == null) {
            return Span.NOOP;
        }
        return tracer.nextSpan()
                .name("agent.route")
                .tag("agent.name", agentName != null ? agentName : "unknown")
                .tag("session.id", sessionId != null ? sessionId : "unknown")
                .start();
    }

    /**
     * Finishes the given span, optionally recording an error on it first.
     *
     * <p>
     * This method is null-safe: passing a {@code null} span or {@link Span#NOOP} is
     * a
     * no-op, so callers do not need to guard against either case.
     *
     * @param span  the span to finish; may be null or {@link Span#NOOP}
     * @param error an exception to record against the span before finishing; pass
     *              {@code null} for a successful completion
     */
    public void endSpan(final Span span, @Nullable final Throwable error) {
        if (span == null || span == Span.NOOP) {
            return;
        }
        if (error != null) {
            span.error(error);
        }
        span.end();
    }

    /**
     * Returns {@code true} when a {@link Tracer} is available on the classpath and
     * has been
     * auto-wired into this service.
     *
     * <p>
     * Callers can use this to conditionally enrich spans with extra tags without
     * the
     * overhead of calling span methods when tracing is not configured.
     *
     * @return {@code true} if real distributed tracing is active; {@code false} if
     *         all span
     *         methods will be no-ops
     */
    public boolean isTracingEnabled() {
        return tracer != null;
    }
}
