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
package io.agentcore.aspect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Auto-configured MCP call interceptor that protects session-required tools using
 * annotation-driven detection — no additional configuration required.
 *
 * <p>This bean activates automatically when no other {@link BaseMcpCallInterceptor}
 * bean is defined in the application context. It inherits the full detection and
 * enforcement logic from {@link BaseMcpCallInterceptor}:
 * <ul>
 *   <li>Scans every bean annotated with {@code @AgentTool} at startup.</li>
 *   <li>Marks tools with {@code category = ToolCategory.MUTATION} or
 *       {@code requiresSession = true} as session-required.</li>
 *   <li>Wraps their {@code ToolCallback} implementations with a session-context guard
 *       that rejects direct MCP invocations with
 *       {@link io.agentcore.exception.McpDirectMutationBlockedException}.</li>
 * </ul>
 *
 * <h3>Replacing this bean</h3>
 * Define your own {@code @Component} that extends {@link BaseMcpCallInterceptor} to
 * replace this default. The {@link ConditionalOnMissingBean} condition ensures only one
 * interceptor is active at a time.
 *
 * @see BaseMcpCallInterceptor
 */
@Component
@ConditionalOnMissingBean(BaseMcpCallInterceptor.class)
public class DefaultMcpCallInterceptor extends BaseMcpCallInterceptor {

    /**
     * Creates the interceptor with access to the application context for
     * {@code @AgentTool} bean scanning.
     *
     * @param applicationContext the Spring application context used to discover
     *                           {@code @AgentTool}-annotated beans
     */
    public DefaultMcpCallInterceptor(final ApplicationContext applicationContext) {
        super(applicationContext);
    }
}
