/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning;

/**
 * Enumeration of LLM reasoning strategies supported by the Relay framework.
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
 *       <td>{@link io.relay.advisor.ThinkingAdvisor} (telemetry)</td>
 *       <td>Multi-step problems requiring explicit reasoning traces</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #TREE_OF_THOUGHTS}</td>
 *       <td>{@link TreeOfThoughtsExplorer}</td>
 *       <td>Exploration problems with multiple viable paths (+58% on symbolic tasks)</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #REACT}</td>
 *       <td>Tool calling + {@link io.relay.stream.ToolProgressPublisher}</td>
 *       <td>Tasks requiring interleaved reasoning and external tool use</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #SELF_REFLECTION}</td>
 *       <td>{@link io.relay.advisor.ReflectionAdvisor}</td>
 *       <td>Quality-critical responses; +43% analytical reasoning gain over baseline</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #REFINEMENT_LOOP}</td>
 *       <td>{@link io.relay.advisor.ReflectionAdvisor}</td>
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
 *       <td>{@link io.relay.summary.BaseLlmSessionSummarizer} (single-pass)</td>
 *       <td>Infinite-context tasks requiring hierarchical compression</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * @see io.relay.advisor.ReflectionAdvisor
 * @see TreeOfThoughtsExplorer
 * @see SelfConsistencyRunner
 * @see LeastToMostSolver
 * @see DecomposedPromptRunner
 */
public enum ReasoningStrategy {

    /**
     * Chain-of-Thought — the model is prompted to "think step by step" before answering.
     *
     * <p>Supported via two paradigms in the Relay framework:
     * <ol>
     *   <li><b>Model-based / Streaming</b> — Telemetry is surfaced in the advisor chain by
     *       {@link io.relay.advisor.ThinkingAdvisor} and streamed via {@link io.relay.stream.ThinkingStreamParser}
     *       which extracts {@code <think>} tags in real-time.</li>
     *   <li><b>Programmatic / Linear Pipeline</b> — Orchestrated by {@link ChainOfThoughtPipeline}
     *       running sequential discrete {@link ReasoningStep}s carrying shared memory context.</li>
     * </ol>
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
     * {@link io.relay.stream.ToolProgressPublisher}.
     */
    REACT,

    /**
     * Self-Reflection — generates a draft response, critiques it using a second LLM call,
     * and revises based on the critique.  Shown to improve analytical reasoning by ~43%
     * (33% → 76% in benchmark evaluations).
     *
     * <p>Implemented by {@link io.relay.advisor.ReflectionAdvisor}.
     */
    SELF_REFLECTION,

    /**
     * Refinement Loop — iteratively generates, critiques, and refines until a numeric quality
     * score meets a configurable threshold or a maximum iteration count is reached.
     *
     * <p>Implemented by {@link io.relay.advisor.ReflectionAdvisor} (same advisor as
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
     * {@link io.relay.summary.BaseLlmSessionSummarizer}. Full recursive sub-agent
     * decomposition can be built on top of {@link DecomposedPromptRunner}.
     */
    RECURSIVE_LM
}
