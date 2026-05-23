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

import io.agentcore.llm.ModelTier;

/**
 * Resolves the {@link ModelTier} that should be used when invoking a specific tool.
 *
 * <p>Different tools have different reasoning demands. A simple product-lookup tool can run
 * cheaply on a {@link ModelTier#UTILITY} model, while a tool that analyzes complex data or
 * orchestrates multistep mutations benefits from a {@link ModelTier#REASONING} model.
 * Implementing this interface lets teams encode that policy in one place so the agent's
 * routing logic picks the right model automatically.
 *
 * <p>The library ships {@link DefaultToolTierRegistry} as the default implementation, which
 * derives tiers automatically from the {@link AgentTool#category()} annotation. Override it
 * by supplying your own {@code @Component} that implements this interface — Spring's
 * {@code @ConditionalOnMissingBean} will cause the default to back off.
 *
 * <h3>Custom implementation example</h3>
 * <pre>{@code
 * @Component
 * public class AcmeTierRegistry implements ToolTierRegistry {
 *
 *     private static final Map<String, ModelTier> OVERRIDES = Map.of(
 *         "analyzeChurnRisk", ModelTier.REASONING,
 *         "formatAddress",    ModelTier.UTILITY
 *     );
 *
 *     @Override
 *     public ModelTier getTier(String toolName) {
 *         return OVERRIDES.getOrDefault(toolName, getDefaultTier());
 *     }
 * }
 * }</pre>
 *
 * @see DefaultToolTierRegistry
 * @see ModelTier
 */
public interface ToolTierRegistry {

    /**
     * Returns the {@link ModelTier} assigned to the named tool.
     *
     * <p>The caller uses this tier to select which LLM endpoint or configuration to invoke.
     * If the tool name is not explicitly registered, implementations should fall back to
     * {@link #getDefaultTier()}.
     *
     * @param toolName the canonical tool name as declared by {@code @Tool(name = "...")}
     *                 or, when no name is set, the method name
     * @return the model tier for this tool; never {@code null}
     */
    ModelTier getTier(String toolName);

    /**
     * Returns the fallback {@link ModelTier} for tools that are not explicitly mapped.
     *
     * <p>The default implementation returns {@link ModelTier#UTILITY}, keeping costs low
     * for unmapped tools. Override this method if your agent's baseline should be
     * {@link ModelTier#REASONING}.
     *
     * @return the tier to use when a tool has no explicit mapping; never {@code null}
     */
    default ModelTier getDefaultTier() {
        return ModelTier.UTILITY;
    }
}
