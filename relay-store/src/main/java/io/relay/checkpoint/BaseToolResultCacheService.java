/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.checkpoint;

import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.relay.model.BaseAgentSession;
import io.relay.model.BaseToolResultCache;
import io.relay.session.SessionContextManager;
import io.relay.store.AgentSessionStore;
import io.relay.store.ToolResultCacheStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base service for idempotent tool execution caching.
 * <p>
 * Tool results are stored in a dedicated cache table via a {@link io.relay.store.ToolResultCacheStore}.
 * This enables:
 * <ul>
 *   <li>Idempotent tool execution (same input = cached result)</li>
 *   <li>Session resume without re-executing expensive operations</li>
 *   <li>LLM retry without duplicate side effects</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Service
 * public class OnboardingToolResultCacheService
 *         extends BaseToolResultCacheService<AgentSessionDO, AgentToolResultCacheDO> {
 *
 *     @Override
 *     protected AgentToolResultCacheDO createCacheEntity(String sessionId, String cacheKey) {
 *         return AgentToolResultCacheDO.builder()
 *                 .sessionId(sessionId)
 *                 .cacheKey(cacheKey)
 *                 .build();
 *     }
 *
 *     @Override
 *     protected String resolveToolSummaryContextKey(String toolName) {
 *         if ("validateOnboardingReadiness".equals(toolName)) {
 *             return "lastReadinessSummary";
 *
 *         }
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * <p>Stateless; safe for concurrent use.
 *
 * @param <S> the session entity type extending BaseAgentSession
 * @param <C> the cache entity type extending BaseToolResultCache
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseToolResultCacheService<S extends BaseAgentSession, C extends BaseToolResultCache> {

    public static final String CONTEXT_KEY_TOOL_EXECUTION_CACHE = "tool_execution_cache";

    protected final SessionContextManager sessionContextManager;
    protected final ObjectMapper objectMapper;

    /**
     * Returns the session store used to look up and persist session state.
     */
    protected abstract AgentSessionStore<S> getSessionStore();

    /**
     * Returns the tool result cache store used to read and write cached tool results.
     */
    protected abstract ToolResultCacheStore<C> getToolResultCacheStore();

    /**
     * Creates a new cache entity instance.
     * Subclasses must implement to create their concrete entity type.
     *
     * @param sessionId the session identifier
     * @param cacheKey  the composite cache key
     * @return new cache entity
     */
    protected abstract C createCacheEntity(String sessionId, String cacheKey);

    /**
     * Maps tool names to context keys for summary caching.
     * Return null for tools that don't have a dedicated summary key.
     *
     * @param toolName the tool name
     * @return the context key for this tool's summary, or null
     */
    protected String resolveToolSummaryContextKey(final String toolName) {
        // Default: no special summary keys, subclasses can override
        return null;
    }

    /**
     * Checks the idempotent tool execution cache.
     *
     * @param sessionId the session identifier
     * @param toolName  the tool name
     * @param inputHash deterministic hash of tool input parameters
     * @return cached result if present
     */
    public Optional<String> checkToolCache(final String sessionId, final String toolName, final String inputHash) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String key = buildCacheKey(sessionId, toolName, inputHash);
        return getToolResultCacheStore()
                .findBySessionIdAndCacheKey(sessionId, key)
                .map(BaseToolResultCache::getResultJson);
    }

    /**
     * Stores a tool execution result. Upserts — if an entry already exists it is updated.
     *
     * @param sessionId the session identifier
     * @param toolName  the tool name
     * @param inputHash deterministic hash of tool input parameters
     * @param result    the tool result to cache
     */
    public void cacheToolResult(
            final String sessionId,
            final String toolName,
            final String inputHash,
            final String result) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String key = buildCacheKey(sessionId, toolName, inputHash);
        C entry = getToolResultCacheStore()
                .findBySessionIdAndCacheKey(sessionId, key)
                .orElseGet(() -> createCacheEntity(sessionId, key));
        entry.setResultJson(result);
        getToolResultCacheStore().save(entry);
        log.debug("Cached tool result in DB: session={}, tool={}", sessionId, toolName);
    }

    /**
     * Evicts all cached tool results for a session.
     * Also removes the legacy {@code tool_execution_cache} key from contextJson.
     * Should be called on session delete and FAILED/COMPLETED transitions.
     *
     * @param sessionId the session identifier
     */
    public void evictSessionToolCache(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        long deleted = getToolResultCacheStore().deleteBySessionId(sessionId);
        log.debug("Evicted {} tool cache entries for session {}", deleted, sessionId);

        // Backward-compat: remove legacy contextJson cache key for pre-migration sessions
        getSessionStore().findBySessionId(sessionId).ifPresent(session -> {
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            if (context.remove(CONTEXT_KEY_TOOL_EXECUTION_CACHE) != null) {
                try {
                    session.setContextJson(objectMapper.writeValueAsString(context));
                    getSessionStore().save(session);
                    log.debug("Cleaned legacy tool_execution_cache key from contextJson for session {}", sessionId);
                } catch (JacksonException e) {
                    log.error("Failed to clean legacy cache key for session {}: {}", sessionId, e.getMessage());
                }
            }
        });
    }

    /**
     * Caches tool-specific summaries into session context.
     * Uses {@link #resolveToolSummaryContextKey} to determine the context key.
     *
     * @param sessionId the session identifier
     * @param toolName  the tool name
     * @param summary   the summary to cache
     */
    public void cacheToolSummary(final String sessionId, final String toolName, final String summary) {
        if (sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }
        String contextKey = resolveToolSummaryContextKey(toolName);
        if (contextKey == null) {
            return;
        }
        getSessionStore().findBySessionId(sessionId).ifPresent(session -> {
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            context.put(contextKey, summary);
            try {
                session.setContextJson(objectMapper.writeValueAsString(context));
                getSessionStore().save(session);
                log.debug("Cached {} summary for session {}", toolName, sessionId);
            } catch (JacksonException ex) {
                log.warn("Failed caching {} summary for session {}: {}", toolName, sessionId, ex.getMessage());
            }
        });
    }

    /**
     * Gets the count of cached tool results for a session.
     *
     * @param sessionId the session identifier
     * @return number of cached entries
     */
    public long getCacheEntryCount(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        return getToolResultCacheStore().countBySessionId(sessionId);
    }

    /**
     * Computes a deterministic hash for tool input parameters.
     * Can be used by tools to generate the inputHash parameter.
     *
     * @param toolName the tool name
     * @param params   the input parameters (will be JSON-serialized)
     * @return SHA-256 hash of the parameters
     */
    public String computeInputHash(final String toolName, final Object params) {
        try {
            String json = objectMapper.writeValueAsString(params);
            return Integer.toHexString((toolName + ":" + json).hashCode());
        } catch (JacksonException e) {
            log.warn("Failed to compute input hash for {}: {}", toolName, e.getMessage());
            return "unknown";
        }
    }

    protected String buildCacheKey(final String sessionId, final String toolName, final String inputHash) {
        return sessionId + "::" + toolName + "::" + inputHash;
    }
}
