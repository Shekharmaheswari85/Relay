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
package io.agentcore.config;

import org.springframework.util.MultiValueMap;

/**
 * SPI for injecting gateway-specific HTTP headers into every LLM API request.
 *
 * <p>Different LLM gateways require different audit, routing, or identity headers —
 * for example, the Walmart LLM Gateway requires {@code WM_LLM_GW.USER_TYPE},
 * {@code WM_LLM_GW.USER_NAME}, and optionally {@code WM_LLM_GW.USER_AGENT} and
 * {@code WM_LLM_GW.USER_IP}.  This interface lets teams inject those headers without
 * modifying core auto-configuration.
 *
 * <h3>How it is called</h3>
 * <p>{@link ChatClientAutoConfiguration} discovers all {@code LlmGatewayHeadersContributor}
 * beans in the application context and calls {@link #contribute} once per request on each
 * one, passing a mutable headers map and the audit configuration from
 * {@code agent.llm.audit.*}.  Contributors can add any number of headers.
 *
 * <p>When no bean of this type is registered, no extra headers are added — which is
 * appropriate when connecting directly to OpenAI or a gateway that does not require audit
 * headers.
 *
 * <h3>Walmart LLM Gateway example</h3>
 * <pre>{@code
 * @Component
 * public class WalmartGatewayHeadersContributor implements LlmGatewayHeadersContributor {
 *
 *     @Override
 *     public void contribute(MultiValueMap<String, String> headers,
 *                            AgentLlmProperties.AuditConfig audit) {
 *         headers.set("WM_LLM_GW.USER_TYPE", audit.getUserType());
 *         headers.set("WM_LLM_GW.USER_NAME", audit.getUserName());
 *         if (audit.getUserAgent() != null && !audit.getUserAgent().isBlank()) {
 *             headers.set("WM_LLM_GW.USER_AGENT", audit.getUserAgent());
 *         }
 *         if (audit.getUserIp() != null && !audit.getUserIp().isBlank()) {
 *             headers.set("WM_LLM_GW.USER_IP", audit.getUserIp());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Implementor contract</h3>
 * <ul>
 *   <li>Implementations must be stateless or thread-safe.</li>
 *   <li>Implementations must not replace or remove headers already present in the map
 *       unless that is intentional (e.g., overriding a default).</li>
 *   <li>Implementations must not throw checked exceptions; wrap failures in
 *       {@link RuntimeException} if an error must propagate.</li>
 * </ul>
 *
 * @see ChatClientAutoConfiguration
 * @see AgentLlmProperties.AuditConfig
 */
@FunctionalInterface
public interface LlmGatewayHeadersContributor {

    /**
     * Populates the mutable HTTP headers map with gateway-specific headers.
     *
     * <p>Callers pass the same {@code headers} map to all registered contributors in
     * sequence.  Each contributor should add only its own headers and leave existing
     * entries intact.
     *
     * @param headers the mutable multi-value map to populate; never null
     * @param audit   the audit identity configuration read from {@code agent.llm.audit.*};
     *                never null
     */
    void contribute(MultiValueMap<String, String> headers, AgentLlmProperties.AuditConfig audit);
}
