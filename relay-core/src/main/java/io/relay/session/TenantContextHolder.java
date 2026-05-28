/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.session;

/**
 * Propagates the current tenant ID via a plain {@code ThreadLocal}. With virtual threads,
 * one thread handles one request end-to-end, so no Reactor context propagation is needed.
 *
 * @see SessionContextHolder
 */
public final class TenantContextHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    /**
     * Returns the tenant ID bound to the current thread.
     *
     * @return the tenant ID, or {@code null} if no tenant has been set on this thread
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * Binds {@code tenantId} to the current thread.
     *
     * @param tenantId the tenant ID to bind; {@code null} clears the binding
     */
    public static void set(final String tenantId) {
        HOLDER.set(tenantId);
    }

    /**
     * Removes the tenant ID binding from the current thread, preventing leaks
     * when threads are reused from a pool.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
