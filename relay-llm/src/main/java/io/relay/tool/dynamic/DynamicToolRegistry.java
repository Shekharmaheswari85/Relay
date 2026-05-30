/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.tool.dynamic;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * A thread-safe runtime registry that manages dynamic connector tools bound to active sessions.
 *
 * <p>Enables registering dynamically compiled tools (such as {@link DynamicRestTool}) at the start
 * of a session turn and handles secure routing and cleanup upon turn completion.
 */
@Component
@Slf4j
public class DynamicToolRegistry {

    private final Map<String, List<ToolCallback>> sessionTools = new ConcurrentHashMap<>();

    /**
     * Registers a list of dynamic tool callbacks for a given session identifier.
     *
     * @param sessionId the unique session identifier; must not be null
     * @param tools     the list of tool callbacks to register; must not be null
     */
    public void registerTools(final String sessionId, final List<ToolCallback> tools) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("Attempted to register tools for empty or null sessionId");
            return;
        }
        if (tools == null || tools.isEmpty()) {
            log.debug("No dynamic tools provided for registration in session: {}", sessionId);
            sessionTools.remove(sessionId);
            return;
        }
        log.info("Registering {} dynamic connector tools for session: {}", tools.size(), sessionId);
        sessionTools.put(sessionId, List.copyOf(tools));
    }

    /**
     * Retrieves the list of dynamically registered tools for the given session identifier.
     *
     * @param sessionId the session identifier; may be null
     * @return an unmodifiable list of registered tool callbacks, or an empty list if none are found
     */
    public List<ToolCallback> getTools(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        return sessionTools.getOrDefault(sessionId, Collections.emptyList());
    }

    /**
     * Removes and cleans up all dynamic tools registered for the given session identifier.
     * Always invoke this at the end of a session execution lifecycle to prevent memory leaks.
     *
     * @param sessionId the session identifier to clean up; may be null
     */
    public void clearTools(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<ToolCallback> removed = sessionTools.remove(sessionId);
        if (removed != null && !removed.isEmpty()) {
            log.info("Successfully cleaned up {} dynamic connector tools for session: {}", removed.size(), sessionId);
        }
    }
}
