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
package io.agentcore.tool;

import java.util.List;
import java.util.Map;

/**
 * {@link BaseToolDecider} implementation that returns every available tool unchanged,
 * performing no filtering.
 *
 * <p>This is the library's default decider: the LLM always receives the full set of
 * registered tools for every turn. It is appropriate when the tool set is small (fewer than
 * roughly a dozen tools) or during initial development before usage patterns are known.
 *
 * <p>As the tool set grows, a context-aware implementation of {@link BaseToolDecider}
 * should replace this class to reduce token cost and improve routing accuracy. To replace
 * it, define a Spring bean that implements {@link BaseToolDecider} — the framework's
 * default registration of {@code PassThroughToolDecider} is conditional on no other bean
 * being present.
 *
 * <pre>{@code
 * @Bean
 * @ConditionalOnMissingBean(BaseToolDecider.class)
 * public BaseToolDecider toolDecider() {
 *     return new PassThroughToolDecider();
 * }
 * }</pre>
 *
 * @see BaseToolDecider
 */
public class PassThroughToolDecider implements BaseToolDecider {

    /**
     * Returns {@code availableTools} without modification.
     *
     * @param message        the user's message for the current turn (ignored)
     * @param availableTools the full set of registered tool names
     * @param context        the current session context map (ignored)
     * @return the same {@code availableTools} list, unfiltered
     */
    @Override
    public List<String> selectTools(
            final String message,
            final List<String> availableTools,
            final Map<String, Object> context) {
        return availableTools;
    }
}
