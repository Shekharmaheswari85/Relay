/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.prompt;

import java.util.Map;

import io.agentcore.model.BaseAgentSession;
import io.agentcore.session.SessionContextManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class that assembles structured LLM prompts from session state and
 * context data.
 *
 * <p>The prompt produced by {@link #buildCompactPrompt} is organised into up to six
 * labelled sections, always in the same order so that the model sees a consistent
 * input format regardless of which domain extends this class:
 * <ol>
 *   <li><b>Session constraints</b> — authoritative fields drawn from the session entity
 *       (common fields such as {@code currentStep} are appended by this base class;
 *       domain-specific fields are appended by {@link #appendSessionConstraints}).</li>
 *   <li><b>Approval-only instructions</b> — emitted only when
 *       {@code approvalOnlyFollowUp} is {@code true}; guides the model to reuse cached
 *       results rather than re-executing steps.</li>
 *   <li><b>Cached summaries</b> — domain-specific rolling summaries injected by
 *       {@link #appendCachedSummaries}.</li>
 *   <li><b>Working-state summary</b> — the latest finalised step summary stored under
 *       the {@value #CONTEXT_KEY_WORKING_STATE_SUMMARY} context key.</li>
 *   <li><b>Canonical session summary</b> — the LLM-generated rolling summary stored
 *       under the {@value #CONTEXT_KEY_LLM_SESSION_SUMMARY} context key.</li>
 *   <li><b>Latest user message</b> — the verbatim user input that triggered this turn.</li>
 * </ol>
 *
 * <h3>Extending this class</h3>
 * <p>Declare a Spring {@code @Component}, inject {@link io.agentcore.session.SessionContextManager}
 * via the Lombok-generated constructor, and implement the two abstract methods.
 *
 * <pre>{@code
 * @Component
 * public class MyPromptBuilder extends BasePromptBuilder<MySessionDO> {
 *
 *     public MyPromptBuilder(SessionContextManager sessionContextManager) {
 *         super(sessionContextManager);
 *     }
 *
 *     @Override
 *     protected void appendSessionConstraints(StringBuilder prompt, MySessionDO session,
 *             Map<String, Object> context) {
 *         prompt.append("- customerTier: ").append(session.getCustomerTier()).append('\n');
 *         prompt.append("- storeId: ").append(session.getStoreId()).append('\n');
 *     }
 *
 *     @Override
 *     protected void appendCachedSummaries(StringBuilder prompt, Map<String, Object> context) {
 *         String orderSummary = sessionContextManager.asString(context.get("orderSummary"), "");
 *         appendSummarySection(prompt, "Order history summary", orderSummary);
 *     }
 * }
 * }</pre>
 *
 * @param <S> the session entity type, must extend {@link io.agentcore.model.BaseAgentSession}
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BasePromptBuilder<S extends BaseAgentSession> {

    protected static final String CONTEXT_KEY_LLM_SESSION_SUMMARY = "llmSessionSummary";
    protected static final String CONTEXT_KEY_WORKING_STATE_SUMMARY = "workingStateSummary";

    protected final SessionContextManager sessionContextManager;

    /**
     * Assembles a complete, structured prompt string ready to be passed as the user
     * message in a Spring AI {@code ChatClient} call.
     *
     * <p>The method composes the six prompt sections described in the class Javadoc.
     * Sections whose data is absent (blank context values) are omitted so the model
     * is not confused by empty headings.
     *
     * @param session              the active session entity; never null
     * @param userMessage          the verbatim message from the end user for this turn;
     *                             never null
     * @param approvalOnlyFollowUp {@code true} when the user message is a simple
     *                             approval (e.g. "yes", "confirm") and the model should
     *                             continue from the last approved step without
     *                             re-executing prior tool calls
     * @return the fully assembled prompt string; never null or blank
     */
    public String buildCompactPrompt(
            final S session, final String userMessage, final boolean approvalOnlyFollowUp) {
        StringBuilder prompt = new StringBuilder();
        Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());

        // Section 1: Session constraints (authoritative)
        prompt.append("Session constraints (authoritative):\n");
        appendCommonSessionConstraints(prompt, session);
        appendSessionConstraints(prompt, session, context);
        prompt.append('\n');

        // Section 2: Approval-only follow-up instructions
        if (approvalOnlyFollowUp) {
            appendApprovalOnlyInstructions(prompt, session, context);
        }

        // Section 3: Cached summaries
        appendCachedSummaries(prompt, context);

        // Section 4: Working state summary
        String workingStateSummary = sessionContextManager.asString(context.get(CONTEXT_KEY_WORKING_STATE_SUMMARY), "");
        if (!workingStateSummary.isBlank()) {
            prompt.append("Latest finalized step summary (authoritative state):\n")
                    .append(workingStateSummary)
                    .append("\n\n");
        }

        // Section 5: Canonical session summary
        String canonicalSummary = sessionContextManager.asString(context.get(CONTEXT_KEY_LLM_SESSION_SUMMARY), "");
        if (!canonicalSummary.isBlank()) {
            prompt.append("Canonical session summary (use this as primary memory):\n")
                    .append(canonicalSummary)
                    .append("\n\n");
        }

        // Section 6: User message
        prompt.append("Latest user message:\n").append(userMessage);

        return prompt.toString();
    }

    /**
     * Appends domain-specific session constraint lines to the prompt's constraints section.
     *
     * <p>The base class has already written the common constraints ({@code currentStep},
     * {@code lastCheckpoint}) before calling this method. Implementations should append
     * only fields that are meaningful to the domain model, using the format:
     * {@code "- fieldName: value\n"}.
     *
     * @param prompt  the prompt builder already containing the common constraint lines
     * @param session the current session entity
     * @param context the parsed context map from {@code session.getContextJson()}
     */
    protected abstract void appendSessionConstraints(
            StringBuilder prompt, S session, Map<String, Object> context);

    /**
     * Appends domain-specific rolling summaries to the prompt.
     *
     * <p>Called after the approval-only instructions section. Implementations should
     * check each summary value for blankness before appending, as missing summaries
     * should produce no output. Use {@link #appendSummarySection} to keep the
     * formatting consistent across implementations.
     *
     * @param prompt  the prompt builder in its current state
     * @param context the parsed context map from {@code session.getContextJson()}
     */
    protected abstract void appendCachedSummaries(StringBuilder prompt, Map<String, Object> context);

    /**
     * Appends instructions guiding the model to handle an approval-only follow-up turn.
     *
     * <p>The default implementation emits a three-bullet instruction block that instructs
     * the model to reuse cached results, continue from the last approved step, and ask
     * only for the next required confirmation. Override to substitute or augment these
     * instructions with domain-specific guidance.
     *
     * @param prompt  the prompt builder in its current state
     * @param session the current session entity
     * @param context the parsed context map from {@code session.getContextJson()}
     */
    protected void appendApprovalOnlyInstructions(
            StringBuilder prompt, S session, Map<String, Object> context) {
        prompt.append("""
                Approval-only follow-up detected.
                - Reuse cached results from session memory.
                - Continue directly from the latest approved step.
                - Ask only for the next required confirmation.

                """);
    }

    /**
     * Appends the common session constraint lines shared by all domain implementations.
     *
     * <p>Writes {@code currentStep} unconditionally and {@code lastCheckpoint} only when
     * the session carries a non-null value. Subclasses call this implicitly through
     * {@link #buildCompactPrompt}; calling it directly is only needed in custom
     * prompt assembly flows.
     *
     * @param prompt  the prompt builder to append to; never null
     * @param session the current session entity; never null
     */
    protected void appendCommonSessionConstraints(final StringBuilder prompt, final S session) {
        prompt.append("- currentStep: ").append(session.getCurrentStep()).append('\n');
        if (session.getLastCheckpoint() != null) {
            prompt.append("- lastCheckpoint: ").append(session.getLastCheckpoint()).append('\n');
        }
    }

    /**
     * Emits the full prompt to the INFO log for debugging and observability.
     *
     * <p>The log entry includes the flow name, session ID, total character count, and
     * the verbatim prompt payload. Use this method at the call site immediately before
     * submitting the prompt to the LLM.
     *
     * @param flow      a short label identifying the calling flow, e.g. {@code "onboarding"}
     * @param prompt    the assembled prompt string; null is treated as empty
     * @param sessionId the session identifier for correlation; null is rendered as {@code "-"}
     */
    public void logPromptForDebug(final String flow, final String prompt, final String sessionId) {
        String safePrompt = prompt == null ? "" : prompt;
        String sessionPart = sessionId == null ? "-" : sessionId;
        log.info(
                "LLM prompt [{}] session={} chars={} truncated={} payload=\n{}",
                flow,
                sessionPart,
                safePrompt.length(),
                false,
                safePrompt);
    }

    /**
     * Appends a labelled summary block to the prompt if the value is non-blank.
     *
     * <p>The block is formatted as:
     * <pre>
     * {label}:
     * {value}
     *
     * </pre>
     * When {@code value} is {@code null} or blank, this method is a no-op so that
     * optional context slots produce no output.
     *
     * @param prompt the prompt builder to append to; never null
     * @param label  the section heading written before the value; never null
     * @param value  the summary text to include; may be null or blank
     */
    protected void appendSummarySection(
            final StringBuilder prompt, final String label, final String value) {
        if (value != null && !value.isBlank()) {
            prompt.append(label).append(":\n").append(value).append("\n\n");
        }
    }
}
