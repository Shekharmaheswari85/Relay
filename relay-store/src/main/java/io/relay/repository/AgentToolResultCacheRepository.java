/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import io.relay.model.AgentToolResultCacheDO;

/**
 * Spring Data repository for {@link AgentToolResultCacheDO} — the framework's built-in
 * tool result cache entity.
 *
 * <p>Extends {@link BaseToolResultCacheRepository} typed to {@link AgentToolResultCacheDO},
 * which provides the standard query contract ({@code findBySessionIdAndCacheKey},
 * {@code deleteBySessionId}, {@code countBySessionId}) without any additional declarations.
 *
 * <p>Agent modules that store tool results using the shared {@code agent_tool_result_cache}
 * table should inject this repository directly. Modules that require domain-specific cache
 * columns should extend {@link BaseToolResultCacheRepository} with their own entity type.
 *
 * <p>This repository is registered as a Spring bean via the {@code @Repository} annotation
 * and is auto-detected by Spring Data's component scan when {@code @EnableRelay} is
 * present on the application configuration class.
 */
@Repository
public interface AgentToolResultCacheRepository
        extends BaseToolResultCacheRepository<AgentToolResultCacheDO> {

    /**
     * Permanently removes cache entries for all sessions in the given list.
     *
     * <p>Used by the bulk-delete endpoint and the expiry scheduler to clean up multiple
     * sessions in a single database round-trip. An empty input list results in no deletions.
     *
     * @param sessionIds the collection of session identifiers whose cache rows should be erased
     * @return the total number of rows deleted across all given sessions
     */
    long deleteBySessionIdIn(List<String> sessionIds);
}
