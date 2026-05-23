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
package io.agentcore.reasoning;

/**
 * Enumeration of LLM reasoning strategies supported by the agentcore framework.
 *
 * <p>Each constant maps to a well-known prompting paradigm and its corresponding
 * implementation in this package. The table below summarises coverage:
 *
 * <table border="1">
 *   <thead>
 *     <tr><th>Strategy</th><th>Implementation</th><th>When to use</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@link #CHAIN_OF_THOUGHT}</td>
 *       <td>{@link io.agentcore.advisor.ThinkingAdvisor} (telemetry)</td>
 *       <td>Multi-step problems requiring explicit reasoning traces</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #TREE_OF_THOUGHTS}</td>
 *       <td>{@link TreeOfThoughtsExplorer}</td>
 *       <td>Exploration problems with multiple viable paths (+58% on symbolic tasks)</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #REACT}</td>
 *       <td>Tool calling + {@link io.agentcore.stream.ToolProgressPublisher}</td>
 *       <td>Tasks requiring interleaved reasoning and external tool use</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #SELF_REFLECTION}</td>
 *       <td>{@link io.agentcore.advisor.ReflectionAdvisor}</td>
 *       <td>Quality-critical responses; +43% analytical reasoning gain over baseline</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #REFINEMENT_LOOP}</td>
 *       <td>{@link io.agentcore.advisor.ReflectionAdvisor}</td>
 *       <td>Iterative generation → critique → refine until a score threshold is met</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #SELF_CONSISTENCY}</td>
 *       <td>{@link SelfConsistencyRunner}</td>
 *       <td>High-variance tasks; majority vote over k independent samples</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #LEAST_TO_MOST}</td>
 *       <td>{@link LeastToMostSolver}</td>
 *       <td>Complex problems decomposed into ordered sub-problems solved sequentially</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #DECOMPOSED}</td>
 *       <td>{@link DecomposedPromptRunner}</td>
 *       <td>Independent parallel sub-tasks whose results are synthesised into one answer</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #RECURSIVE_LM}</td>
 *       <td>{@link io.agentcore.summary.BaseLlmSessionSummarizer} (single-pass)</td>
 *       <td>Infinite-context tasks requiring hierarchical compression</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * @see io.agentcore.advisor.ReflectionAdvisor
 * @see TreeOfThoughtsExplorer
 * @see SelfConsistencyRunner
 * @see LeastToMostSolver
 * @see DecomposedPromptRunner
 */
public enum ReasoningStrategy {

    /**
     * Chain-of-Thought — the model is prompted to "think step by step" before answering.
     *
     * <p>Telemetry is surfaced in the advisor chain by {@link io.agentcore.advisor.ThinkingAdvisor}.
     * For explicit CoT prompting, inject step-by-step instructions into the system prompt via
     * {@link io.agentcore.config.AgentSystemPromptProvider}.
     */
    CHAIN_OF_THOUGHT,

    /**
     * Tree-of-Thoughts — branches multiple candidate reasoning paths in parallel, scores each,
     * and returns the highest-scoring final answer.
     *
     * <p>Implemented by {@link TreeOfThoughtsExplorer}.
     */
    TREE_OF_THOUGHTS,

    /**
     * ReAct (Reason + Act) — interleaves reasoning steps with tool calls and observation
     * feedback, grounding the agent's reasoning in real-world data.
     *
     * <p>Supported natively through the Spring AI tool-calling pipeline and
     * {@link io.agentcore.stream.ToolProgressPublisher}.
     */
    REACT,

    /**
     * Self-Reflection — generates a draft response, critiques it using a second LLM call,
     * and revises based on the critique.  Shown to improve analytical reasoning by ~43%
     * (33% → 76% in benchmark evaluations).
     *
     * <p>Implemented by {@link io.agentcore.advisor.ReflectionAdvisor}.
     */
    SELF_REFLECTION,

    /**
     * Refinement Loop — iteratively generates, critiques, and refines until a numeric quality
     * score meets a configurable threshold or a maximum iteration count is reached.
     *
     * <p>Implemented by {@link io.agentcore.advisor.ReflectionAdvisor} (same advisor as
     * {@link #SELF_REFLECTION}; the two strategies share an implementation).
     */
    REFINEMENT_LOOP,

    /**
     * Self-Consistency — generates {@code k} independent responses to the same prompt and
     * returns the majority-vote answer, reducing variance on high-stakes tasks.
     *
     * <p>Implemented by {@link SelfConsistencyRunner}.
     */
    SELF_CONSISTENCY,

    /**
     * Least-to-Most — asks the model to decompose a problem into ordered sub-problems and
     * then solves each sequentially, carrying established answers forward into each step.
     *
     * <p>Implemented by {@link LeastToMostSolver}.
     */
    LEAST_TO_MOST,

    /**
     * Decomposed Prompting — splits a complex task into independent parallel sub-tasks,
     * executes all sub-tasks concurrently on virtual threads, then synthesises the partial
     * answers into a single final response.
     *
     * <p>Implemented by {@link DecomposedPromptRunner}.
     */
    DECOMPOSED,

    /**
     * Recursive LM — applies hierarchical summarisation to handle contexts that exceed the
     * model's effective window, enabling truly long-document reasoning.
     *
     * <p>Single-pass compression is implemented by
     * {@link io.agentcore.summary.BaseLlmSessionSummarizer}. Full recursive sub-agent
     * decomposition can be built on top of {@link DecomposedPromptRunner}.
     */
    RECURSIVE_LM
}
