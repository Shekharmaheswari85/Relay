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
package io.agentcore.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Abstraction for tool deduplication cache.
 * <p>
 * Enables pluggable cache backends (local, Redis, Hazelcast, etc.) for tool
 * result caching and deduplication. The default implementation is in-memory
 * ({@link LocalToolDedupCache}), which is sufficient for most use cases due to:
 * <ul>
 *   <li>SSE stream stickiness (HTTP/2 connection affinity)</li>
 *   <li>Short dedup TTLs (2 minutes default)</li>
 * </ul>
 *
 * <h3>Implementation contract</h3>
 * <ul>
 *   <li>Keys are formatted as: {@code sessionId|toolName|inputHash}</li>
 *   <li>TTL is tool-specific (configurable per tool)</li>
 *   <li>Implementations must be thread-safe</li>
 *   <li>Returns should be cheap — avoid deserializing large payloads on read</li>
 * </ul>
 *
 * <h3>Distributed implementations</h3>
 * <p>When cross-pod dedup is needed, implement this interface:
 * <pre>{@code
 * @Service
 * @ConditionalOnProperty(name = "agent.tool.cache.distributed", havingValue = "true")
 * public class RedisToolDedupCache implements ToolDedupCache {
 *     private final RedisTemplate<String, byte[]> template;
 *     // ...
 * }
 * }</pre>
 */
public interface ToolDedupCache {

    /**
     * Checks if a tool invocation with the given key exists in the cache.
     *
     * @param key the dedup key (sessionId|toolName|inputHash)
     * @return cached result bytes if present and not expired; empty otherwise
     */
    Optional<byte[]> get(String key);

    /**
     * Stores a tool result in the cache with the specified TTL.
     *
     * @param key   the dedup key
     * @param value serialized result (e.g., Jackson-serialized object)
     * @param ttl   time-to-live for this entry
     */
    void put(String key, byte[] value, Duration ttl);

    /**
     * Removes all entries for a session (called on session delete/complete).
     *
     * @param sessionId the session identifier
     */
    void evictSession(String sessionId);

    /**
     * Returns true if this is a local-only (non-distributed) implementation.
     * Used for logging and observability.
     */
    default boolean isLocal() {
        return true;
    }
}
