/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.tool;

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared execution helper that runs synchronous tool logic on a virtual thread with a
 * configurable timeout, recording per-tool latency metrics via Micrometer.
 *
 * <p>Spring AI 1.x invokes {@code @Tool} methods synchronously and expects an immediate
 * return value. Tools that call blocking data sources (JDBC, REST APIs, caches) simply
 * implement their logic normally — no reactive wrappers needed. This support class
 * provides timeout and metrics without any reactive dependency.
 *
 * <p>Inject this bean into any {@link AgentTool} class that needs timeout + metrics:
 *
 * <pre>{@code
 * @AgentTool
 * public class SearchProductsTool {
 *
 *     private final ProductRepository repo;
 *     private final ToolExecutionSupport support;
 *
 *     @Tool(name = "searchProducts", description = "Searches the product catalog")
 *     public SearchResult searchProducts(SearchRequest req) {
 *         return support.executeBlocking(
 *             "searchProducts",
 *             () -> repo.search(req.query()),
 *             Duration.ofSeconds(15)
 *         );
 *     }
 * }
 * }</pre>
 *
 * <p>Every execution is timed and recorded as a Micrometer metric named
 * {@code agent.tool.execution} with tags {@code tool} and {@code outcome}
 * ({@code success}, {@code timeout}, or {@code error}).
 *
 * <p>All requested timeouts are capped at 120 seconds. Passing {@code null}, zero, or a
 * negative duration falls back to the 30-second default.
 *
 * @see ToolExecutionSupport.ToolExecutionException
 */
@Component
@Slf4j
public class ToolExecutionSupport {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int SIDE_EFFECT_TIMEOUT_SECONDS = 10;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(MAX_TIMEOUT_SECONDS);

    private final MeterRegistry meterRegistry;

    /**
     * Creates an execution support instance backed by the given metrics registry.
     *
     * @param meterRegistry the Micrometer registry used to record per-tool timing metrics;
     *                      never {@code null}
     */
    public ToolExecutionSupport(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Runs the given {@code task} on a virtual thread and blocks the calling thread until
     * the result is available or the timeout elapses.
     *
     * <p>The effective timeout is {@code min(timeout, 120s)}. Passing {@code null}, zero, or
     * a negative duration substitutes the 30-second default.
     *
     * @param toolName the canonical tool name used as the {@code tool} metric tag and in log
     *                 messages; should match the value in {@code @Tool(name = "...")}
     * @param task     the tool logic to execute; evaluated on a virtual thread
     * @param timeout  the maximum time to wait; values above 120 seconds are silently capped
     * @param <T>      the result type
     * @return the value returned by the task; may be {@code null}
     * @throws ToolExecutionException if the task throws, times out, or is interrupted
     */
    public <T> T executeBlocking(final String toolName, final Callable<T> task, final Duration timeout) {
        Duration effectiveTimeout = capTimeout(timeout);
        Timer.Sample sample = Timer.start(meterRegistry);

        log.debug("Tool execution started: tool={}, timeout={}s", toolName, effectiveTimeout.toSeconds());

        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, runnable -> Thread.ofVirtual().start(runnable));

        try {
            T result = future.orTimeout(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS).get();
            sample.stop(timer(toolName, "success"));
            log.debug("Tool execution completed: tool={}", toolName);
            return result;

        } catch (ExecutionException ex) {
            sample.stop(timer(toolName, "error"));
            Throwable cause = ex.getCause();
            if (cause instanceof ToolExecutionException tee) {
                throw tee;
            }
            String msg = cause != null ? cause.getMessage() : "unknown error";
            log.error("Tool execution failed: tool={}, error={}", toolName, msg);
            throw new ToolExecutionException(toolName, msg, cause);

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            sample.stop(timer(toolName, "error"));
            throw new ToolExecutionException(toolName, "Tool execution interrupted", ex);
        }
    }

    /**
     * Runs the given {@code task} using the default 30-second timeout.
     * Equivalent to {@link #executeBlocking(String, Callable, Duration)} with
     * {@code Duration.ofSeconds(30)}.
     *
     * @param toolName the canonical tool name
     * @param task     the tool logic to execute
     * @param <T>      the result type
     * @return the value returned by the task; may be {@code null}
     * @throws ToolExecutionException if the task throws, times out, or is interrupted
     */
    public <T> T executeBlocking(final String toolName, final Callable<T> task) {
        return executeBlocking(toolName, task, DEFAULT_TIMEOUT);
    }

    /**
     * Executes a secondary, non-critical task and swallows any failure rather than
     * propagating it.
     *
     * <p>Use this method for operations whose failure must not abort the primary tool
     * response — for example, cache population or audit logging. On failure, a {@code WARN}
     * log entry is emitted and execution continues normally.
     *
     * @param toolName  the tool name used in log messages for traceability
     * @param operation a short human-readable description of the side effect (e.g.,
     *                  {@code "cache result"}, {@code "record audit"})
     * @param task      the side effect logic to execute
     * @param timeout   the maximum time to wait before giving up on the side effect
     */
    public void executeSideEffect(
            final String toolName, final String operation, final Callable<?> task, final Duration timeout) {
        try {
            executeBlocking(toolName, task, timeout);
            log.debug("Side effect completed: tool={}, operation={}", toolName, operation);
        } catch (Exception ex) {
            log.warn("Side effect failed (non-blocking): tool={}, operation={}, error={}",
                    toolName, operation, ex.getMessage());
        }
    }

    /**
     * Executes a secondary, non-critical task using the default 10-second side-effect timeout.
     * Equivalent to {@link #executeSideEffect(String, String, Callable, Duration)} with
     * {@code Duration.ofSeconds(10)}.
     *
     * @param toolName  the tool name used in log messages
     * @param operation a short description of the side effect
     * @param task      the side effect logic to execute
     */
    public void executeSideEffect(final String toolName, final String operation, final Callable<?> task) {
        executeSideEffect(toolName, operation, task, Duration.ofSeconds(SIDE_EFFECT_TIMEOUT_SECONDS));
    }

    private Duration capTimeout(final Duration requested) {
        if (requested == null || requested.isNegative() || requested.isZero()) {
            return DEFAULT_TIMEOUT;
        }
        return requested.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : requested;
    }

    private Timer timer(final String toolName, final String outcome) {
        return Timer.builder("agent.tool.execution")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .description("Tool execution duration")
                .register(meterRegistry);
    }

    /**
     * Signals that a tool execution invoked via {@link ToolExecutionSupport} has failed.
     *
     * <p>This exception is thrown by {@link #executeBlocking} when:
     * <ul>
     *   <li>the task throws an exception, or</li>
     *   <li>the configured timeout elapses before the task completes.</li>
     * </ul>
     *
     * <p>The {@link ToolExecutionException#toolName} field identifies which tool failed,
     * making it easier
     * to correlate the exception with the Micrometer metric tagged with
     * {@code outcome=timeout} or {@code outcome=error}.
     */
    @Getter
    public static class ToolExecutionException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * -- GETTER --
         * Returns the canonical name of the tool that failed.
         */
        private final String toolName;

        /**
         * Creates an exception for the named tool with the given message and root cause.
         *
         * @param toolName the canonical name of the tool that failed
         * @param message  a description of why the tool failed
         * @param cause    the underlying exception that triggered the failure
         */
        public ToolExecutionException(final String toolName, final String message, final Throwable cause) {
            super(message, cause);
            this.toolName = toolName;
        }
    }
}
