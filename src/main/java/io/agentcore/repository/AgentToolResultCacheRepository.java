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
package io.agentcore.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import io.agentcore.model.AgentToolResultCacheDO;

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
 * and is auto-detected by Spring Data's component scan when {@code @EnableAgentCore} is
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
