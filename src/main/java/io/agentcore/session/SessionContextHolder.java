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
