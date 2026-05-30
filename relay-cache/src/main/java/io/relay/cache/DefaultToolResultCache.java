/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.cache;

import java.time.Duration;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link ToolResultCache} that delegates to {@link AgentCache}.
 * <p>
 * Handles key formatting and provides tool-specific caching semantics on top
 * of the generic cache abstraction.
 *
 * <p>When a {@code toolTtl} is supplied, every {@link #putToolResult} call uses that
 * duration instead of the global cache TTL configured in {@code relay.cache.ttl}.
 * This allows tool results to expire sooner or later than other cached data.
 *
 * <p>Configure via {@code relay.cache.tool-ttl}:
 * <pre>{@code
 * relay:
 *   cache:
 *     ttl: 30m            # global default
 *     tool-ttl: 5m        # tool results expire faster
 * }</pre>
 */
@Slf4j
public class DefaultToolResultCache implements ToolResultCache {

    private static final String KEY_PREFIX = "tool:";

    private final AgentCache cache;

    /**
     * Dedicated TTL for tool result entries; {@code null} falls back to the global cache TTL.
     */
    @Nullable
    private final Duration toolTtl;

    /**
     * Creates a cache that uses the global default TTL for all tool result entries.
     *
     * @param cache the backing agent cache
     */
    public DefaultToolResultCache(final AgentCache cache) {
        this(cache, null);
    }

    /**
     * Creates a cache with an explicit TTL override for tool result entries.
     *
     * @param cache   the backing agent cache
     * @param toolTtl the TTL to apply to tool results; {@code null} uses the global cache TTL
     */
    public DefaultToolResultCache(final AgentCache cache, @Nullable final Duration toolTtl) {
        this.cache = cache;
        this.toolTtl = toolTtl;
    }

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
        if (toolTtl != null) {
            cache.put(key, resultJson, toolTtl);
            log.debug("Tool result cached with custom TTL {}: session={}, tool={}", toolTtl, sessionId, toolName);
        } else {
            cache.put(key, resultJson);
            log.debug("Tool result cached: session={}, tool={}, keyLength={}", sessionId, toolName, key.length());
        }
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
