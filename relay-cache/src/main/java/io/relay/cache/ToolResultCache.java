/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.cache;

import java.util.Optional;

/**
 * Specialized cache interface for tool execution results.
 * <p>
 * Provides session-scoped caching for idempotent tool operations,
 * preventing redundant expensive calls (e.g., BigQuery, external APIs).
 */
public interface ToolResultCache {

    /**
     * Get cached tool result.
     *
     * @param sessionId the session ID
     * @param toolName  the tool name
     * @param inputHash hash of the tool input for cache key
     * @return cached result JSON, or empty if not found
     */
    Optional<String> getToolResult(String sessionId, String toolName, String inputHash);

    /**
     * Cache a tool result.
     *
     * @param sessionId  the session ID
     * @param toolName   the tool name
     * @param inputHash  hash of the tool input for cache key
     * @param resultJson the result to cache (JSON string)
     */
    void putToolResult(String sessionId, String toolName, String inputHash, String resultJson);

    /**
     * Evict all cached results for a session.
     * <p>
     * Called when session completes, fails, or is deleted.
     *
     * @param sessionId the session ID to evict
     * @return number of entries evicted
     */
    int evictSession(String sessionId);

    /**
     * Build a cache key from components.
     *
     * @param sessionId the session ID
     * @param toolName  the tool name
     * @param inputHash hash of the tool input
     * @return the cache key
     */
    default String buildCacheKey(String sessionId, String toolName, String inputHash) {
        return sessionId + "::" + toolName + "::" + inputHash;
    }
}
