/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.session;

/**
 * Propagates the active sub-agent name via a plain {@code ThreadLocal}. With virtual threads,
 * one thread handles one request end-to-end, so no Reactor context propagation is needed.
 *
 * @see SessionContextHolder
 */
public final class ActiveAgentHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ActiveAgentHolder() {
    }

    /**
     * Returns the name of the agent currently executing on this thread.
     *
     * @return the agent name, or {@code null} if no agent context has been set on this thread
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * Binds {@code agentName} to the current thread.
     *
     * @param agentName the agent name to bind; {@code null} clears the binding
     */
    public static void set(final String agentName) {
        HOLDER.set(agentName);
    }

    /**
     * Removes the agent name binding from the current thread, preventing leaks
     * when threads are reused from a pool.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
