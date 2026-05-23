/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.thread;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Provides static access to the application's Project Loom virtual-thread
 * {@link org.springframework.core.task.TaskExecutor} for fire-and-forget
 * asynchronous work that does not fit naturally into a reactive pipeline.
 *
 * <p>Spring wires the {@code virtualThreadExecutor} bean into this component
 * at startup and stores it in an {@link java.util.concurrent.atomic.AtomicReference}.
 * Call sites throughout the agent pipeline obtain it via the static
 * {@link #executeAsync} or {@link #getTaskExecutor} accessors without requiring
 * Spring injection.
 *
 * <h3>Submitting a fire-and-forget task</h3>
 * <pre>{@code
 * VirtualThreadTaskExecutorUtil.executeAsync(
 *     () -> auditRepository.save(buildAuditRecord(toolName, sessionId)));
 * }</pre>
 *
 * <h3>Obtaining the executor for use with a Spring component</h3>
 * <pre>{@code
 * @Bean
 * AsyncTaskExecutor agentAsyncExecutor() {
 *     return (AsyncTaskExecutor) VirtualThreadTaskExecutorUtil.getTaskExecutor();
 * }
 * }</pre>
 *
 * <p>Virtual threads park efficiently when blocked, so this executor is well suited
 * for I/O-bound background work (audit logging, cache writes, metrics flushing)
 * without the risk of exhausting a fixed platform-thread pool.
 *
 * <p>When the executor has not yet been initialized — for example in unit tests
 * that do not start the full Spring context — {@link #executeAsync} falls back to
 * running the task synchronously on the calling thread.
 *
 * <p>This class is thread-safe. {@link #setTaskExecutor} and {@link #reset} exist
 * solely for test isolation and must not be called in production code.
 */
@Component("agentCoreVirtualThreadTaskExecutorUtil")
public final class VirtualThreadTaskExecutorUtil {

    private static final AtomicReference<TaskExecutor> TASK_EXECUTOR = new AtomicReference<>();

    /**
     * Receives the {@code virtualThreadExecutor} bean from the application
     * context and stores it for static access.
     *
     * @param virtualThreadTaskExecutor the {@link org.springframework.core.task.TaskExecutor}
     *                                  backed by a virtual-thread factory; must not
     *                                  be {@code null}
     */
    VirtualThreadTaskExecutorUtil(
            @Qualifier("virtualThreadExecutor") final TaskExecutor virtualThreadTaskExecutor) {
        TASK_EXECUTOR.set(virtualThreadTaskExecutor);
    }

    /**
     * Submits {@code task} to the virtual-thread executor for asynchronous execution.
     *
     * <p>This method returns immediately; the task runs on a new virtual thread. Use
     * it for non-critical background work (audit log writes, cache population, metrics
     * flushing) where the calling thread must not block.
     *
     * <p>When the executor has not been initialized (e.g., in a unit test without a
     * Spring context), the task runs synchronously on the calling thread so that test
     * assertions can observe its side effects without concurrency concerns.
     *
     * @param task the work to perform asynchronously; must not be {@code null}
     */
    public static void executeAsync(final Runnable task) {
        TaskExecutor executor = TASK_EXECUTOR.get();
        if (executor != null) {
            executor.execute(task);
        } else {
            // For test scenarios without Spring context, execute synchronously
            task.run();
        }
    }

    /**
     * Returns the application's virtual-thread {@link org.springframework.core.task.TaskExecutor}.
     *
     * <p>Falls back to an inline synchronous executor ({@code Runnable::run}) when
     * called before the Spring context has started, keeping unit tests simple and
     * deterministic.
     *
     * @return the configured {@link org.springframework.core.task.TaskExecutor};
     *         never {@code null}
     */
    public static TaskExecutor getTaskExecutor() {
        return Objects.requireNonNullElseGet(TASK_EXECUTOR.get(), () -> Runnable::run);
    }

    /**
     * Replaces the stored executor with {@code executor}.
     *
     * <p>Intended for test setup only. Supply a synchronous inline executor
     * ({@code Runnable::run}) to make task execution deterministic in unit tests.
     *
     * @param executor the replacement executor; {@code null} causes
     *                 {@link #getTaskExecutor()} to fall back to an inline
     *                 synchronous executor
     */
    public static void setTaskExecutor(final TaskExecutor executor) {
        TASK_EXECUTOR.set(executor);
    }

    /**
     * Clears the stored executor reference, restoring the fallback behavior of
     * {@link #getTaskExecutor()} and {@link #executeAsync}.
     *
     * <p>Call this in {@code @AfterEach} test teardown to prevent executor state
     * from leaking between tests.
     */
    public static void reset() {
        TASK_EXECUTOR.set(null);
    }
}
