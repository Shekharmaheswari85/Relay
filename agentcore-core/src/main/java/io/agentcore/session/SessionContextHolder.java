/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.session;

/**
 * Propagates the current session ID via a plain {@code ThreadLocal}. With virtual threads,
 * one thread handles one request end-to-end, so no Reactor context propagation is needed.
 */
public final class SessionContextHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private SessionContextHolder() {
    }

    /**
     * Returns the session ID bound to the current thread.
     *
     * @return the session ID, or {@code null} if no session has been set on this thread
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * Binds {@code sessionId} to the current thread.
     *
     * @param sessionId the session ID to bind; {@code null} clears the binding
     */
    public static void set(final String sessionId) {
        HOLDER.set(sessionId);
    }

    /**
     * Removes the session ID binding from the current thread, preventing leaks
     * when threads are reused from a pool.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
