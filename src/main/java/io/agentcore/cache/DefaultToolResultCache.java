/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.cache;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link ToolResultCache} that delegates to {@link AgentCache}.
 * <p>
 * Handles key formatting and provides tool-specific caching semantics on top
 * of the generic cache abstraction.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultToolResultCache implements ToolResultCache {

    private static final String KEY_PREFIX = "tool:";

    private final AgentCache cache;

    @Override
    public Optional<String> getToolResult(final String sessionId, final String toolName, final String inputHash) {
        String key = buildFullKey(sessionId, toolName, inputHash);
        Optional<String> result = cache.get(key, String.class);
        if (result.isPresent()) {
            log.debug("Tool cache hit: session={}, tool={}", sessionId, toolName);
        } else {
            log.debug("Tool cache miss: session={}, tool={}", sessionId, toolName);
        }
        return result;
    }

    @Override
    public void putToolResult(
            final String sessionId, final String toolName, final String inputHash, final String resultJson) {
        String key = buildFullKey(sessionId, toolName, inputHash);
        cache.put(key, resultJson);
        log.debug("Tool result cached: session={}, tool={}, keyLength={}", sessionId, toolName, key.length());
    }

    @Override
    public int evictSession(final String sessionId) {
        String pattern = KEY_PREFIX + sessionId + ":*";
        int count = cache.evictByPattern(pattern);
        log.info("Evicted {} tool cache entries for session {}", count, sessionId);
        return count;
    }

    private String buildFullKey(final String sessionId, final String toolName, final String inputHash) {
        return KEY_PREFIX + buildCacheKey(sessionId, toolName, inputHash);
    }
}
