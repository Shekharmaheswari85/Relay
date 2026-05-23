/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.aspect;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.relay.cache.ToolDedupCache;
import io.relay.observability.AgentObservabilityService;
import io.relay.session.SessionContextHolder;
import io.relay.stream.ToolProgressPublisher;

import io.micrometer.tracing.Span;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract AOP base class that wraps every {@code @AgentTool}-annotated method with
 * cross-cutting concerns: execution tracing, SSE progress events, Micrometer metrics,
 * and idempotent deduplication.
 *
 * <h3>What is intercepted</h3>
 * <p>Subclasses declare the pointcut by placing an {@code @Around} advice that delegates
 * to {@link #doLogToolExecution(ProceedingJoinPoint)}. The pointcut expression in the
 * subclass defines which tool methods are wrapped — typically all methods in the tool
 * package that carry a Spring AI {@code @Tool} annotation.
 *
 * <h3>Lifecycle around each tool call</h3>
 * <ol>
 *   <li><strong>Before:</strong> MDC context is set; a deduplication key is computed
 *       from the session ID, tool name, and SHA-256 hash of the first argument. If a
 *       matching result is found in the pluggable {@link ToolDedupCache} (Redis) or the
 *       in-process fallback map, the cached result is returned immediately and the tool
 *       body is never entered.</li>
 *   <li><strong>Proceed:</strong> The join point proceeds; a distributed trace span is
 *       open for its duration via {@link AgentObservabilityService}.</li>
 *   <li><strong>After success:</strong> Duration and result summary are logged; a success
 *       SSE event is published; the result is written to both caches; the
 *       {@link #onToolSuccess} extension hook fires.</li>
 *   <li><strong>After error:</strong> Duration and error are logged; a failure SSE event
 *       is published; the {@link #onToolError} extension hook fires; the original
 *       exception is re-thrown unchanged.</li>
 * </ol>
 *
 * <h3>Extending</h3>
 * <pre>{@code
 * @Aspect
 * @Component
 * public class AppToolExecutionAspect extends BaseToolExecutionAspect {
 *
 *     public AppToolExecutionAspect(ToolProgressPublisher pub,
 *                                   AgentObservabilityService obs,
 *                                   ObjectMapper mapper) {
 *         super(pub, obs, mapper);
 *     }
 *
 *     @Around("execution(* com.example.agent.tools..*.*(..))"
 *           + " && @annotation(org.springframework.ai.tool.annotation.Tool)")
 *     public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
 *         return doLogToolExecution(pjp);
 *     }
 *
 *     @Override
 *     protected void onToolSuccess(String sessionId, String toolName,
 *                                  Object result, long durationMs) {
 *         checkpointManager.saveIfNeeded(sessionId, toolName);
 *     }
 *
 *     @Override
 *     protected boolean isMutationTool(String toolName) {
 *         return MUTATION_TOOLS.contains(toolName);
 *     }
 * }
 * }</pre>
 *
 * <h3>Deduplication TTL</h3>
 * <p>The default TTL is 120 seconds. Override {@link #getDedupTtlMs(String)} to apply
 * a per-tool TTL, or provide a {@link ToolDedupCache} bean backed by Redis for
 * cross-pod deduplication in clustered deployments.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseToolExecutionAspect {

    private static final long DEFAULT_DEDUP_TTL_MS = 120_000L;
    private static final int HASH_HEX_CHARS_PER_BYTE = 2;
    private static final int HASH_PREFIX_LENGTH = 16;

    protected final ToolProgressPublisher toolProgressPublisher;
    protected final AgentObservabilityService observabilityService;
    protected final ObjectMapper objectMapper;

    /**
     * Optional pluggable deduplication cache. When a {@link ToolDedupCache} bean is present
     * in the application context (e.g., a Redis-backed implementation), it is preferred over
     * the in-process fallback map, enabling deduplication across multiple pods.
     */
    private ToolDedupCache toolDedupCache;

    /**
     * Injects the optional distributed deduplication cache.
     *
     * <p>Called automatically by Spring when a {@link ToolDedupCache} bean is present in the
     * application context. When absent, deduplication falls back to the in-process
     * {@link java.util.concurrent.ConcurrentHashMap}.
     *
     * @param toolDedupCache the cache implementation to use for cross-pod deduplication
     */
    @Autowired(required = false)
    public void setToolDedupCache(final ToolDedupCache toolDedupCache) {
        this.toolDedupCache = toolDedupCache;
    }

    private final Map<String, CachedToolResult> recentToolResults = new ConcurrentHashMap<>();

    private record CachedToolResult(Object result, long createdAtMs) {}

    // ─── Core execution method ────────────────────────────────────────────────

    /**
     * Executes the advised tool method with full cross-cutting instrumentation.
     *
     * <p>Invoke this from an {@code @Around} advice defined in your concrete subclass.
     * The method handles deduplication, tracing, SSE event publishing, metrics, and the
     * {@link #onToolSuccess}/{@link #onToolError} extension hooks. The original exception
     * is re-thrown if the join point fails.
     *
     * @param joinPoint the proceeding join point supplied by the AspectJ {@code @Around} advice
     * @return the value returned by the tool method, or a cached result when deduplication applies
     * @throws Throwable any exception thrown by the underlying tool method
     */
    protected Object doLogToolExecution(final ProceedingJoinPoint joinPoint) throws Throwable {
        String toolName = resolveToolName(joinPoint);
        Object[] args = joinPoint.getArgs();
        String argsText = summarizeArgs(args);
        String sessionId = SessionContextHolder.get();

        // Set MDC context
        observabilityService.setMdcContext();

        // Check dedup cache — pluggable cache first (supports distributed/Redis), then local map
        String dedupeKey = buildDedupeKey(sessionId, toolName, argsText, args);
        Optional<Object> cachedResult = lookupCachedResult(dedupeKey, toolName);
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }

        long startedAt = System.currentTimeMillis();

        log.info("TOOL_EXEC_START tool={} args={}", toolName, argsText);
        toolProgressPublisher.emitToolStart(toolName, "Started " + toolName, argsText);

        // Start distributed trace span (no-op when tracing is not configured)
        Span span = observabilityService.startToolSpan(toolName, sessionId);
        try {
            Object result = joinPoint.proceed();
            long durationMs = System.currentTimeMillis() - startedAt;
            String resultSummary = summarizeResult(result);

            handleSuccessfulExecution(toolName, sessionId, dedupeKey, result, durationMs, resultSummary);

            observabilityService.endSpan(span, null);
            return result;

        } catch (Exception ex) {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.error("TOOL_EXEC_ERROR tool={} durationMs={} error={}", toolName, durationMs, ex.getMessage());
            toolProgressPublisher.emitToolError(
                    toolName, "Failed " + toolName + ": " + ex.getMessage(), durationMs);
            observabilityService.recordToolCall(toolName, "error");
            observabilityService.endSpan(span, ex);

            // Extension hook for domain-specific error handling
            if (sessionId != null && !sessionId.isBlank()) {
                onToolError(sessionId, toolName, ex, durationMs);
            }

            throw ex;
        } catch (Error err) {
            observabilityService.endSpan(span, err);
            throw err;
        }
    }

    private Optional<Object> lookupCachedResult(final String dedupeKey, final String toolName) {
        if (dedupeKey == null) {
            return Optional.empty();
        }
        if (toolDedupCache != null) {
            Optional<byte[]> distributedCached = toolDedupCache.get(dedupeKey);
            if (distributedCached.isPresent()) {
                Object cachedResult = deserializeCachedResult(distributedCached.get());
                emitDuplicateCacheNotice(toolName);
                return Optional.ofNullable(cachedResult);
            }
        }
        CachedToolResult localCached = recentToolResults.get(dedupeKey);
        if (localCached != null && (System.currentTimeMillis() - localCached.createdAtMs) <= getDedupTtlMs(toolName)) {
            emitDuplicateCacheNotice(toolName);
            return Optional.ofNullable(localCached.result);
        }
        return Optional.empty();
    }

    private void emitDuplicateCacheNotice(final String toolName) {
        toolProgressPublisher.emitNotice(
                toolName,
                "Skipped duplicate tool invocation",
                "Returning cached result for identical arguments");
        observabilityService.recordToolCall(toolName, "cached");
    }

    private void handleSuccessfulExecution(
            final String toolName,
            final String sessionId,
            final String dedupeKey,
            final Object result,
            final long durationMs,
            final String resultSummary) {
        log.info("TOOL_EXEC_SUCCESS tool={} durationMs={} summary={}", toolName, durationMs, resultSummary);
        toolProgressPublisher.emitToolSuccess(toolName, "Completed " + toolName, durationMs, resultSummary);

        String status = isSkippedResult(result) ? "skipped" : "success";
        observabilityService.recordToolCall(toolName, status);

        if (isSkippedResult(result)) {
            String reason = extractAccessor(result, "skipReason");
            toolProgressPublisher.emitNotice(
                    toolName, "Skipped optional data discovery", reason == null ? "" : reason);
        }

        cacheToolResult(dedupeKey, toolName, result);

        // Extension hook for domain-specific post-success logic
        if (sessionId != null && !sessionId.isBlank()) {
            onToolSuccess(sessionId, toolName, result, durationMs);
        }
    }

    private void cacheToolResult(final String dedupeKey, final String toolName, final Object result) {
        if (dedupeKey == null) {
            return;
        }
        recentToolResults.put(dedupeKey, new CachedToolResult(result, System.currentTimeMillis()));
        if (toolDedupCache != null) {
            byte[] serialized = serializeCachedResult(result);
            if (serialized.length > 0) {
                toolDedupCache.put(dedupeKey, serialized, Duration.ofMillis(getDedupTtlMs(toolName)));
            }
        }
    }

    // ─── Extension points ─────────────────────────────────────────────────────

    /**
     * Invoked after a tool method returns successfully, within an active session context.
     *
     * <p>The default implementation is a no-op. Override to add domain-specific
     * post-success logic such as saving a checkpoint, clearing a confirmation gate, or
     * updating audit state.
     *
     * @param sessionId  the session ID bound to the current thread at the time of execution
     * @param toolName   the resolved {@code @Tool} name of the method that succeeded
     * @param result     the value returned by the tool method; may be {@code null}
     * @param durationMs wall-clock elapsed time from the start of the tool call to return
     */
    protected void onToolSuccess(
            final String sessionId, final String toolName, final Object result, final long durationMs) {
    }

    /**
     * Invoked after a tool method throws an exception, within an active session context.
     *
     * <p>The default implementation is a no-op. Override to add domain-specific error
     * handling such as rolling back an in-progress operation or alerting an on-call system.
     * The original exception is always re-thrown by the framework regardless of what this
     * method does.
     *
     * @param sessionId  the session ID bound to the current thread at the time of execution
     * @param toolName   the resolved {@code @Tool} name of the method that failed
     * @param error      the exception thrown by the tool method
     * @param durationMs wall-clock elapsed time from the start of the tool call to the throw
     */
    protected void onToolError(
            final String sessionId, final String toolName, final Throwable error, final long durationMs) {
    }

    /**
     * Returns {@code true} if the named tool modifies persistent state.
     *
     * <p>The default implementation returns {@code false} for every tool. Override to
     * classify domain-specific tools as mutations so that subclasses or advisors can
     * apply additional safeguards (e.g., requiring confirmation) selectively.
     *
     * @param toolName the resolved {@code @Tool} name to classify
     * @return {@code true} if the tool is a mutation; {@code false} otherwise
     */
    protected boolean isMutationTool(final String toolName) {
        return false;
    }

    /**
     * Returns the deduplication TTL in milliseconds for the named tool.
     *
     * <p>Results for a given session + tool + argument combination are considered
     * duplicate and returned from cache for this duration. The default is 120 seconds.
     * Override to apply a shorter or longer TTL for specific tools — for example, a
     * polling tool may warrant a 5-second TTL while a slow enrichment tool may warrant
     * 10 minutes.
     *
     * @param toolName the resolved {@code @Tool} name
     * @return TTL in milliseconds; must be positive
     */
    protected long getDedupTtlMs(final String toolName) {
        return DEFAULT_DEDUP_TTL_MS;
    }

    /**
     * Returns a fully-qualified deduplication key for the given tool invocation, or
     * {@code null} to fall back to the default SHA-256 hash-based key.
     *
     * <p>The session ID is automatically prepended to whatever value is returned, so the
     * key only needs to be unique within the tool's own namespace. Return {@code null}
     * (the default) to use the hash of the first serializable argument.
     *
     * @param toolName the resolved {@code @Tool} name
     * @param args     the raw method arguments supplied to the tool
     * @return a custom dedup key suffix, or {@code null} to use the default
     */
    protected String buildCustomDedupeKey(final String toolName, final Object[] args) {
        return null;
    }

    // ─── Tool name resolution ─────────────────────────────────────────────────

    /**
     * Resolves the canonical tool name for logging and metrics by reading the
     * {@code @Tool(name = ...)} attribute from the intercepted method.
     *
     * <p>Falls back to the simple class name of the target bean when the annotation is
     * absent or carries a blank name.
     *
     * @param joinPoint the proceeding join point whose method is being resolved
     * @return the tool name; never {@code null}
     */
    protected String resolveToolName(final ProceedingJoinPoint joinPoint) {
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null && !toolAnnotation.name().isEmpty()) {
                return toolAnnotation.name();
            }
        } catch (Exception ignored) {
            // Fall through to default
        }
        return joinPoint.getTarget().getClass().getSimpleName();
    }

    // ─── Deduplication ────────────────────────────────────────────────────────

    private String buildDedupeKey(
            final String sessionId, final String toolName, final String argsText, final Object[] args) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        // Check for custom key first
        String customKey = buildCustomDedupeKey(toolName, args);
        if (customKey != null) {
            return sessionId + "|" + toolName + "|" + customKey;
        }

        // Default: hash-based key
        String hash = computeCanonicalHash(args);
        return sessionId + "|" + toolName + "|" + (hash != null ? hash : argsText);
    }

    private String computeCanonicalHash(final Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(args[0]);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * HASH_HEX_CHARS_PER_BYTE);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, HASH_PREFIX_LENGTH);
        } catch (JsonProcessingException | NoSuchAlgorithmException ignored) {
            return null;
        }
    }

    /**
     * Removes expired entries from the in-memory deduplication map on a fixed schedule.
     *
     * <p>The cleanup interval is controlled by the
     * {@code agent.tool.cache.cleanup-interval-ms} property (default: 300 000 ms / 5 min).
     * An entry is considered expired when it has been in the map longer than the TTL
     * returned by {@link #getDedupTtlMs(String)} for its tool.
     */
    @Scheduled(fixedDelayString = "${agent.tool.cache.cleanup-interval-ms:300000}")
    public void purgeExpiredCacheEntries() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, CachedToolResult>> it =
                recentToolResults.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CachedToolResult> entry = it.next();
            String toolName = extractToolNameFromKey(entry.getKey());
            if (now - entry.getValue().createdAtMs() > getDedupTtlMs(toolName)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Purged {} expired tool cache entries (remaining={})", removed, recentToolResults.size());
        }
    }

    private String extractToolNameFromKey(final String key) {
        int first = key.indexOf('|');
        if (first < 0) {
            return "";
        }
        int second = key.indexOf('|', first + 1);
        return second < 0 ? key.substring(first + 1) : key.substring(first + 1, second);
    }

    // ─── Result summarization ─────────────────────────────────────────────────

    protected String summarizeArgs(final Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(", "));
    }

    protected String summarizeResult(final Object result) {
        if (result == null) {
            return "result=null";
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        appendAccessor(fields, result, "success");
        appendAccessor(fields, result, "error");
        appendAccessor(fields, result, "rowCount");
        appendAccessor(fields, result, "skipped");
        appendAccessor(fields, result, "skipReason");

        if (fields.isEmpty()) {
            return String.valueOf(result);
        }
        String joined = fields.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        return joined.isBlank() ? String.valueOf(result) : joined;
    }

    private void appendAccessor(final Map<String, Object> fields, final Object target, final String name) {
        String value = extractAccessor(target, name);
        if (value != null) {
            fields.put(name, value);
        }
    }

    protected String extractAccessor(final Object target, final String accessorName) {
        if (target == null) {
            return null;
        }
        try {
            Object value = target.getClass().getMethod(accessorName).invoke(target);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    protected boolean isSkippedResult(final Object result) {
        String skipped = extractAccessor(result, "skipped");
        return Boolean.parseBoolean(skipped);
    }

    // ─── ToolDedupCache serialization helpers ─────────────────────────────────

    private byte[] serializeCachedResult(final Object result) {
        if (result == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(result);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private Object deserializeCachedResult(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            // Attempt to deserialize as generic Object; String results are returned as-is
            return objectMapper.readValue(bytes, Object.class);
        } catch (Exception ignored) {
            // Fallback: return as UTF-8 string
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
