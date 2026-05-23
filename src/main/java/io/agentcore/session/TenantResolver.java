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

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extension point for extracting the tenant identifier from an incoming HTTP
 * request in multi-tenant agent deployments.
 *
 * <p>Implement and register a single Spring bean of this type to have the
 * framework call {@link #resolve} on every session-creation request. The
 * returned value is stored on the session as {@code tenantId} and carried
 * through the agent pipeline for routing, data isolation, and audit.
 *
 * <p>When no bean is registered the framework defaults to {@link #SINGLE_TENANT},
 * which is appropriate for single-tenant environments that do not require
 * isolation between callers.
 *
 * <h3>Resolving from a request header</h3>
 * <pre>{@code
 * @Component
 * public class HeaderTenantResolver implements TenantResolver {
 *
 *     @Override
 *     public String resolve(HttpServletRequest request) {
 *         String tenantId = request.getHeader("X-Tenant-Id");
 *         return tenantId != null ? tenantId : SINGLE_TENANT;
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe; {@link #resolve} may be invoked
 * concurrently from multiple virtual threads.
 *
 * @see io.agentcore.model.BaseAgentSession
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * Sentinel tenant identifier for single-tenant deployments.
     *
     * <p>Return this constant from {@link #resolve} when the request carries no
     * tenant discriminator, or when multi-tenancy is not required. The framework
     * also uses this value as the fallback when no {@code TenantResolver} bean is
     * present in the application context.
     */
    String SINGLE_TENANT = "default";

    /**
     * Extracts the tenant identifier from the incoming HTTP request.
     *
     * <p>This method is called synchronously on the virtual thread handling the request.
     * Implementations may perform blocking operations such as header reads, JWT parsing,
     * or lightweight database lookups.
     *
     * @param request the current HTTP servlet request; never {@code null}
     * @return the resolved tenant identifier; never {@code null} — return
     *         {@link #SINGLE_TENANT} when the tenant cannot be determined from
     *         the request
     */
    String resolve(HttpServletRequest request);
}
