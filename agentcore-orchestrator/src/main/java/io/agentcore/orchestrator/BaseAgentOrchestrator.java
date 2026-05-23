/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.AdvisorSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.agentcore.agent.AgentExecutionContext;
import io.agentcore.agent.BaseSubAgent;
import io.agentcore.llm.ChatClientRegistry;
import io.agentcore.llm.ModelTier;
import io.agentcore.model.BaseAgentSession;
import io.agentcore.observability.AgentObservabilityService;
import io.agentcore.repository.BaseAgentSessionRepository;
import io.agentcore.session.ActiveAgentHolder;
import io.agentcore.session.SessionContextHolder;
import io.agentcore.session.SessionContextManager;
import io.agentcore.stream.PipelineEmitter;
import io.agentcore.stream.ThinkingStreamParser;
import io.agentcore.stream.ToolProgressPublisher;

import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The primary orchestration engine for multi-agent workflows in the AgentCore framework.
 *
 * <p>{@code BaseAgentOrchestrator} is the most central class in the framework. It owns the
 * complete lifecycle of a single agent turn: it selects the correct {@link BaseSubAgent} for
 * the current workflow step, assembles the system and user prompts, executes the LLM streaming
 * call on a virtual thread, and emits a fully-formed SSE response stream back to the caller
 * via a {@link PipelineEmitter}.
 *
 * <h3>Routing lifecycle (per turn)</h3>
 * <ol>
 *   <li>Bind the session ID to the current thread via {@link SessionContextHolder}.</li>
 *   <li>Determine the current workflow step by calling {@link #parseCurrentStep}.</li>
 *   <li>Walk the registered {@link BaseSubAgent} list and pick the first whose
 *       {@code canHandle} returns {@code true}. If no agent matches, the first in the
 *       list is used as a default and a warning is logged.</li>
 *   <li>If the selected agent returns {@code true} from {@link BaseSubAgent#handlesExecution},
 *       the orchestrator emits an {@code agent_handoff} event and delegates entirely to
 *       {@link BaseSubAgent#execute(AgentExecutionContext)} (A2A / cross-process delegation
 *       path).</li>
 *   <li>Otherwise the orchestrator builds prompts via {@link #getSystemPrompt} and
 *       {@link #buildUserPrompt}, resolves the appropriate {@link ModelTier} via
 *       {@link #resolveModelTier}, and opens a Spring AI streaming call against the
 *       {@link ChatClientRegistry}-provided {@link ChatClient}.</li>
 *   <li>If the upstream model returns an error, the orchestrator transparently falls
 *       back to a non-streaming {@code .call()} invocation via {@link #handleLlmFallback}
 *       and re-chunks the full response for the UI.</li>
 *   <li>After the LLM stream completes, {@link #onResponseComplete} is called with the
 *       accumulated full response (use this to persist chat history). Follow-up question
 *       suggestions and a terminal {@code done} SSE event are then appended.</li>
 * </ol>
 *
 * <h3>SSE event types emitted per turn</h3>
 * <ul>
 *   <li>{@code stage} — progress milestones ({@code agent_execution} at 50%, {@code completed}
 *       at 100%).</li>
 *   <li>{@code thinking} — emitted when a {@link ThinkingStreamParser} is present and the model
 *       produces chain-of-thought content inside {@code <think>} tags.</li>
 *   <li>{@code message} — streamed text chunks of the assistant response, emitted at readable
 *       sentence and newline boundaries.</li>
 *   <li>{@code follow_up_questions} — JSON array of suggested follow-up prompts (only when
 *       {@link #suggestFollowUpQuestions} returns a non-empty list).</li>
 *   <li>{@code confirmation_required} — emitted when the assistant response contains
 *       confirmation-seeking language ({@code approve}, {@code confirm}, etc.).</li>
 *   <li>{@code agent_handoff} — emitted before delegating to a sub-agent that handles its own
 *       execution pipeline.</li>
 *   <li>{@code done} — terminal event signalling the stream is complete.</li>
 *   <li>{@code error} — emitted on stream failure.</li>
 * </ul>
 *
 * <h3>How to extend</h3>
 * <p>Subclass {@code BaseAgentOrchestrator} and annotate it as a Spring {@code @Component}.
 * The four abstract methods are the only mandatory overrides:
 *
 * <pre>{@code
 * @Component
 * public class OrderAgentOrchestrator
 *         extends BaseAgentOrchestrator<OrderSessionDO, OrderStep> {
 *
 *     @Override
 *     protected OrderStep parseCurrentStep(OrderSessionDO session) {
 *         return session.getCurrentStep() != null
 *                 ? OrderStep.valueOf(session.getCurrentStep())
 *                 : OrderStep.INTENT_CLASSIFICATION;
 *     }
 *
 *     @Override
 *     protected String getSystemPrompt(
 *             BaseSubAgent<OrderSessionDO, OrderStep> subAgent,
 *             OrderSessionDO session,
 *             Map<String, Object> context) {
 *         return ORDER_BASE_PROMPT + "\n\n" + subAgent.systemPrompt(session, context);
 *     }
 *
 *     @Override
 *     protected String buildUserPrompt(
 *             OrderSessionDO session, String message, Map<String, Object> context) {
 *         return orderPromptBuilder.build(session, message, context);
 *     }
 *
 *     @Override
 *     protected ModelTier resolveModelTier(OrderStep step) {
 *         return step.requiresReasoning() ? ModelTier.REASONING : ModelTier.UTILITY;
 *     }
 * }
 * }</pre>
 *
 * @param <S>    the session entity type; must extend {@link BaseAgentSession}
 * @param <STEP> the workflow step type, typically a domain-specific enum
 * @see BaseSubAgent
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseAgentOrchestrator<S extends BaseAgentSession, STEP> {

    private static final Pattern CONFIRMATION_REQUIRED_PATTERN = Pattern.compile(
            "(?i)\\b(approve|approval|confirm|confirmation|shall i proceed|do you want me to proceed|proceed\\?)\\b");

    protected final ChatClientRegistry chatClientRegistry;
    protected final SessionContextManager sessionContextManager;
    protected final ToolProgressPublisher toolProgressPublisher;
    protected final BaseAgentSessionRepository<S> sessionRepository;
    protected final List<? extends BaseSubAgent<S, STEP>> subAgents;
    protected final AgentObservabilityService observabilityService;
    protected final ThreadPoolTaskExecutor virtualThreadExecutor;

    /**
     * Optional thinking-stream parser. When present, raw LLM chunks are routed through it
     * so that {@code <think>…</think>} blocks are split into {@code thinking} SSE events and
     * the remaining text is delivered as normal {@code message} events.
     */
    private ThinkingStreamParser thinkingParser;

    /**
     * Setter for the optional {@link ThinkingStreamParser}. Injected automatically when a
     * bean of that type is present in the application context.
     *
     * @param thinkingParser the parser to use; may be {@code null}
     */
    @Autowired(required = false)
    public void setThinkingParser(final ThinkingStreamParser thinkingParser) {
        this.thinkingParser = thinkingParser;
    }

    // ─── Main entry point ─────────────────────────────────────────────────────

    /**
     * Routes the incoming turn to the correct sub-agent and drives the full LLM streaming
     * pipeline, writing all SSE events directly to {@code emitter}.
     *
     * <p>This is the single entry point used by the pipeline executor. The method sets the
     * session ID on {@link SessionContextHolder} for the duration of the call, registers the
     * emitter with {@link ToolProgressPublisher}, and unregisters it in a {@code finally}
     * block regardless of the outcome. All LLM streaming, chunking, follow-up question
     * generation, and event emission happen synchronously on the calling virtual thread.
     *
     * @param session   the current session entity carrying state and context JSON
     * @param sessionId the session identifier used for logging, metrics, and SSE routing
     * @param message   the raw user message for this turn
     * @param emitter   the {@link PipelineEmitter} that delivers SSE events to the HTTP client
     * @param context   additional key-value context forwarded from the pipeline executor
     */
    public void route(
            final S session,
            final String sessionId,
            final String message,
            final PipelineEmitter emitter,
            final Map<String, Object> context) {

        SessionContextHolder.set(sessionId);
        try {
            registerRouteStart(sessionId, emitter);
            STEP currentStep = parseCurrentStep(session);
            BaseSubAgent<S, STEP> subAgent = selectSubAgent(session, currentStep);
            initializeRoutingContext(session, sessionId, subAgent, currentStep);

            if (handleDelegatedExecutionIfNeeded(session, sessionId, message, emitter, subAgent)) {
                return;
            }

            Map<String, Object> sessionContext = resolveSessionContext(session, message);
            LlmExecutionOutcome llmOutcome = executeLlmRoute(
                    session, sessionId, message, emitter, currentStep, subAgent, sessionContext);

            if (llmOutcome.success()) {
                handleSuccessfulResponse(session, sessionId, emitter, sessionContext, llmOutcome.fullResponse());
            }

            sendCompletionEvents(emitter);

        } finally {
            toolProgressPublisher.unregister(sessionId);
            SessionContextHolder.clear();
        }
    }

    private void registerRouteStart(final String sessionId, final PipelineEmitter emitter) {
        toolProgressPublisher.register(sessionId, emitter);
        emitter.sendStage("agent_execution", "Agent executing", 50);
    }

    private void initializeRoutingContext(
            final S session,
            final String sessionId,
            final BaseSubAgent<S, STEP> subAgent,
            final STEP currentStep) {
        log.info("Routing turn to sub-agent={} step={} session={}", subAgent.name(), currentStep, sessionId);
        String previousSubAgent = session.getActiveSubAgent();
        ActiveAgentHolder.set(subAgent.name());
        observabilityService.setMdcContext(sessionId, subAgent.name());
        if (!Objects.equals(previousSubAgent, subAgent.name())) {
            observabilityService.recordAgentHandoff(previousSubAgent, subAgent.name());
            updateSessionSubAgent(session, subAgent.name());
        }
    }

    private boolean handleDelegatedExecutionIfNeeded(
            final S session,
            final String sessionId,
            final String message,
            final PipelineEmitter emitter,
            final BaseSubAgent<S, STEP> subAgent) {
        if (!subAgent.handlesExecution()) {
            return false;
        }
        log.info("Delegating execution to sub-agent={} session={}", subAgent.name(), sessionId);
        toolProgressPublisher.emitAgentHandoff(sessionId, subAgent.name(), "Delegating to " + subAgent.name());
        Map<String, Object> sessionContext = sessionContextManager.parseContextJson(session.getContextJson());
        subAgent.execute(new AgentExecutionContext<>(session, sessionId, message, emitter, sessionContext));
        return true;
    }

    private Map<String, Object> resolveSessionContext(final S session, final String message) {
        Map<String, Object> initialSessionContext = sessionContextManager.parseContextJson(session.getContextJson());
        if (!shouldSummarize(session, initialSessionContext, message)) {
            return initialSessionContext;
        }
        triggerSummarization(session, initialSessionContext, message);
        return sessionContextManager.parseContextJson(session.getContextJson());
    }

    private LlmExecutionOutcome executeLlmRoute(
            final S session,
            final String sessionId,
            final String message,
            final PipelineEmitter emitter,
            final STEP currentStep,
            final BaseSubAgent<S, STEP> subAgent,
            final Map<String, Object> sessionContext) {
        ModelTier modelTier = resolveModelTier(currentStep);
        ChatClient chatClient = chatClientRegistry.getClientForTier(modelTier);
        log.debug("Resolved model tier {} for step {} session={}", modelTier, currentStep, sessionId);

        String systemPrompt = getSystemPrompt(subAgent, session, sessionContext);
        String userPrompt = buildUserPrompt(session, message, sessionContext);
        toolProgressPublisher.emitThinking(sessionId, "Model call started...");

        Timer.Sample llmTimerSample = observabilityService.startLlmTimer();
        Span routeSpan = observabilityService.startRouteSpan(subAgent.name(), sessionId);
        StringBuilder accumulator = new StringBuilder();
        StringBuilder streamBuffer = new StringBuilder();

        try {
            streamLlmContent(chatClient, session, sessionId, message, emitter, sessionContext,
                    systemPrompt, userPrompt, accumulator, streamBuffer);
            observabilityService.stopLlmTimer(llmTimerSample, "spring-ai", modelTier.name(), "success");
            observabilityService.recordLlmCall("spring-ai", modelTier.name(), "success");
            return new LlmExecutionOutcome(true, accumulator.toString());
        } catch (Exception ex) {
            return handleStreamingFailure(session, sessionId, message, emitter, sessionContext, chatClient,
                    modelTier, llmTimerSample, accumulator, systemPrompt, userPrompt, ex);
        } finally {
            observabilityService.endSpan(routeSpan, null);
        }
    }

    private void streamLlmContent(
            final ChatClient chatClient,
            final S session,
            final String sessionId,
            final String message,
            final PipelineEmitter emitter,
            final Map<String, Object> sessionContext,
            final String systemPrompt,
            final String userPrompt,
            final StringBuilder accumulator,
            final StringBuilder streamBuffer) {
        Iterable<String> chunks = chatClient
                .prompt()
                .system(Objects.requireNonNull(systemPrompt, "System prompt must not be null"))
                .user(Objects.requireNonNull(userPrompt, "User prompt must not be null"))
                .advisors(advisor -> configureAdvisorParams(advisor, session, sessionId, message, sessionContext))
                .stream()
                .content()
                .toIterable();

        for (String chunk : chunks) {
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }
            accumulator.append(chunk);
            if (thinkingParser != null) {
                thinkingParser.process(chunk, emitter);
            } else {
                streamBuffer.append(chunk);
                flushReadyChunks(streamBuffer, emitter);
            }
        }
        flushRemainingStream(streamBuffer, emitter);
    }

    private void flushRemainingStream(final StringBuilder streamBuffer, final PipelineEmitter emitter) {
        if (thinkingParser != null) {
            thinkingParser.flush(emitter);
            return;
        }
        String trailing = streamBuffer.toString();
        streamBuffer.setLength(0);
        if (!trailing.isBlank()) {
            emitter.sendMessage(trailing);
        }
    }

    private LlmExecutionOutcome handleStreamingFailure(
            final S session,
            final String sessionId,
            final String message,
            final PipelineEmitter emitter,
            final Map<String, Object> sessionContext,
            final ChatClient chatClient,
            final ModelTier modelTier,
            final Timer.Sample llmTimerSample,
            final StringBuilder accumulator,
            final String systemPrompt,
            final String userPrompt,
            final Exception streamException) {
        log.warn("LLM stream failed for session={}, attempting non-streaming fallback: {}",
                sessionId, streamException.getMessage());
        observabilityService.stopLlmTimer(llmTimerSample, "spring-ai", modelTier.name(), "error");
        observabilityService.recordLlmCall("spring-ai", modelTier.name(), "error");
        try {
            String fallbackContent = handleLlmFallback(
                    chatClient, systemPrompt, userPrompt, session, sessionId, message, sessionContext);
            accumulator.setLength(0);
            accumulator.append(fallbackContent != null ? fallbackContent : "");
            if (fallbackContent != null && !fallbackContent.isBlank()) {
                emitter.sendMessage(fallbackContent);
            }
            return new LlmExecutionOutcome(true, accumulator.toString());
        } catch (Exception fallbackEx) {
            log.error("LLM fallback also failed for session={}: {}", sessionId, fallbackEx.getMessage());
            emitter.sendError("Agent response generation failed: " + fallbackEx.getMessage());
            handleStreamFailure(sessionId, fallbackEx.getMessage());
            return new LlmExecutionOutcome(false, "");
        }
    }

    private void handleSuccessfulResponse(
            final S session,
            final String sessionId,
            final PipelineEmitter emitter,
            final Map<String, Object> sessionContext,
            final String fullResponse) {
        onResponseComplete(session, fullResponse);
        log.debug("Stream complete: session={} chars={}", sessionId, fullResponse.length());

        List<String> followUps = suggestFollowUpQuestions(session, fullResponse, sessionContext);
        if (!followUps.isEmpty()) {
            emitter.sendFollowUpQuestions(followUps);
        }

        if (!fullResponse.isBlank() && CONFIRMATION_REQUIRED_PATTERN.matcher(fullResponse).find()) {
            emitter.sendConfirmationRequired("Assistant is requesting explicit user confirmation");
        }
    }

    private void sendCompletionEvents(final PipelineEmitter emitter) {
        emitter.sendStage("completed", "Completed", 100);
        emitter.sendDone();
    }

    private record LlmExecutionOutcome(boolean success, String fullResponse) {}

    // ─── Abstract methods ─────────────────────────────────────────────────────

    /**
     * Derives the current workflow step from the persisted session state.
     *
     * <p>Implementations read {@code session.getCurrentStep()} (or an equivalent field) and
     * convert it to the domain-specific {@code STEP} type. The returned value drives sub-agent
     * selection and model-tier resolution for the current turn.
     *
     * @param session the current session entity
     * @return the workflow step that describes where this session is in its lifecycle;
     *         must not be {@code null}
     */
    protected abstract STEP parseCurrentStep(S session);

    /**
     * Composes the LLM system message for the current turn.
     *
     * <p>Implementations typically combine a base system prompt shared across all steps
     * with the step-specific instructions returned by
     * {@link BaseSubAgent#systemPrompt(BaseAgentSession, Map)}.
     * The returned string is passed verbatim as the {@code system} role message; a {@code null}
     * value causes the LLM call to fail with a {@link NullPointerException}.
     *
     * @param subAgent the sub-agent selected for this turn
     * @param session  the current session entity
     * @param context  the parsed session context map (never {@code null}; may be empty)
     * @return the full system prompt string; must not be {@code null}
     */
    protected abstract String getSystemPrompt(BaseSubAgent<S, STEP> subAgent, S session, Map<String, Object> context);

    /**
     * Composes the LLM user turn message from the incoming message and session state.
     *
     * <p>Implementations typically enrich the raw message with relevant context fields
     * (e.g. retrieved facts, prior step outputs, active selections) and format the result
     * according to their prompt template. The returned string is passed verbatim as the
     * {@code user} role message; a {@code null} value causes the LLM call to fail.
     *
     * @param session        the current session entity
     * @param messageContent the raw user message for this turn
     * @param context        the parsed session context map (never {@code null}; may be empty)
     * @return the full user prompt string; must not be {@code null}
     */
    protected abstract String buildUserPrompt(S session, String messageContent, Map<String, Object> context);

    /**
     * Maps the current workflow step to the appropriate {@link ModelTier}.
     *
     * <p>Use {@link ModelTier#REASONING} for steps that require deep analysis, multi-step
     * tool use, or nuanced generation. Use {@link ModelTier#UTILITY} for simpler steps such
     * as intent classification or formatting. The resolved tier is passed to
     * {@link ChatClientRegistry#getClientForTier} to obtain the correct {@link ChatClient}.
     *
     * @param step the current workflow step
     * @return the model tier to use for this step; must not be {@code null}
     */
    protected abstract ModelTier resolveModelTier(STEP step);

    // ─── Overridable hooks ────────────────────────────────────────────────────

    /**
     * Configures advisor parameters on the Spring AI {@link AdvisorSpec} before the LLM call.
     *
     * <p>The default implementation binds {@code session_id} as a required parameter. Override
     * to add advisor-specific parameters consumed by registered
     * {@link org.springframework.ai.chat.client.advisor.api.Advisor} beans.
     *
     * @param advisorSpec    the mutable advisor configuration
     * @param session        the current session entity
     * @param sessionId      the session identifier; guaranteed non-null at the call site
     * @param messageContent the raw user message for this turn
     * @param context        the parsed session context map (never {@code null}; may be empty)
     */
    protected void configureAdvisorParams(
            final AdvisorSpec advisorSpec,
            final S session,
            final String sessionId,
            final String messageContent,
            final Map<String, Object> context) {
        advisorSpec.param("session_id", Objects.requireNonNull(sessionId, "Session ID must not be null"));
    }

    /**
     * Called once after the LLM stream has completed and the full response text is available.
     *
     * <p>Override this method to persist the assistant's reply to the chat history store.
     * The default implementation is a no-op. This hook runs on the virtual thread handling
     * the request, so blocking operations such as database writes are safe here.
     *
     * @param session      the current session entity
     * @param fullResponse the complete, concatenated assistant response text for this turn
     */
    protected void onResponseComplete(final S session, final String fullResponse) {
    }

    /**
     * Called when the LLM stream terminates with an unrecoverable error (after the fallback
     * also fails).
     *
     * <p>Override to mark the session as {@link io.agentcore.session.SessionStatus#FAILED},
     * trigger alerting, or perform cleanup. The default implementation logs the error at
     * {@code ERROR} level.
     *
     * @param sessionId   the session identifier
     * @param errorDetail a human-readable description of the failure
     */
    protected void handleStreamFailure(final String sessionId, final String errorDetail) {
        log.error("Stream failed for session {}: {}", sessionId, errorDetail);
    }

    /**
     * Returns follow-up question suggestions to display after the assistant's response.
     *
     * <p>When this method returns a non-empty list, the orchestrator emits a
     * {@code follow_up_questions} SSE event immediately after the final {@code message} chunk
     * and before the terminal {@code done} event. The default implementation returns an
     * empty list (no suggestions).
     *
     * @param session  the current session entity
     * @param response the complete assistant response text for this turn
     * @param context  the parsed session context map (never {@code null}; may be empty)
     * @return a list of suggested follow-up question strings; an empty list suppresses the event
     */
    protected List<String> suggestFollowUpQuestions(
            final S session, final String response, final Map<String, Object> context) {
        return List.of();
    }

    /**
     * Returns {@code true} if the session's chat history should be condensed before the LLM call.
     *
     * <p>Override this method together with {@link #triggerSummarization} to implement automatic
     * history compaction. The orchestrator calls this hook before building prompts; when it returns
     * {@code true}, {@link #triggerSummarization} is called and the session context is re-parsed
     * before continuing. The default implementation always returns {@code false}.
     *
     * @param session        the current session entity
     * @param context        the parsed session context map
     * @param messageContent the raw user message for this turn
     * @return {@code true} to trigger summarization before the LLM call
     */
    protected boolean shouldSummarize(
            final S session, final Map<String, Object> context, final String messageContent) {
        return false;
    }

    /**
     * Condenses the session's chat history and persists the summary to the session context.
     *
     * <p>Override this method to invoke a {@link io.agentcore.summary.SessionSummarizer}
     * implementation. After this method returns, the orchestrator re-parses
     * {@code session.getContextJson()} so that the freshly summarized history is available
     * to prompt builders for the current turn. The default implementation is a no-op.
     *
     * @param session        the current session entity
     * @param context        the parsed session context map prior to summarization
     * @param messageContent the raw user message for this turn
     */
    protected void triggerSummarization(
            final S session, final Map<String, Object> context, final String messageContent) {
    }

    // ─── LLM fallback ─────────────────────────────────────────────────────────

    /**
     * Executes a non-streaming {@code .call()} LLM request as a fallback when the primary
     * streaming call fails.
     *
     * <p>Override this method to customize the fallback behavior (e.g., use a different model
     * tier, apply different advisors, or add retry logic). The default implementation calls
     * the same chat client with the same prompts and advisors, but uses the blocking
     * {@code .call().content()} path instead of streaming.
     *
     * @param chatClient     the resolved chat client for the current model tier
     * @param systemPrompt   the system prompt for this turn
     * @param userPrompt     the user prompt for this turn
     * @param session        the current session entity
     * @param sessionId      the session identifier
     * @param messageContent the raw user message for this turn
     * @param context        the parsed session context map
     * @return the full response string; may be {@code null} when the model returns nothing
     */
    protected String handleLlmFallback(
            final ChatClient chatClient,
            final String systemPrompt,
            final String userPrompt,
            final S session,
            final String sessionId,
            final String messageContent,
            final Map<String, Object> context) {
        log.info("Executing non-streaming LLM fallback for session={}", sessionId);
        return chatClient
                .prompt()
                .system(Objects.requireNonNull(systemPrompt, "System prompt must not be null"))
                .user(Objects.requireNonNull(userPrompt, "User prompt must not be null"))
                .advisors(advisor -> configureAdvisorParams(advisor, session, sessionId, messageContent, context))
                .call()
                .content();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Walks the registered sub-agent list and returns the first agent whose
     * {@code canHandle} method returns {@code true} for the given session and step.
     * Falls back to the first registered agent when none matches, logging a warning.
     *
     * @param session     the current session entity
     * @param currentStep the current workflow step
     * @return the selected sub-agent; never {@code null}
     */
    private BaseSubAgent<S, STEP> selectSubAgent(final S session, final STEP currentStep) {
        for (var agent : subAgents) {
            if (agent.canHandle(session, currentStep)) {
                return (BaseSubAgent<S, STEP>) agent;
            }
        }
        log.warn(
                "No sub-agent matched step={} session={} — defaulting to {}",
                currentStep,
                session.getSessionId(),
                subAgents.getFirst().name());
        return (BaseSubAgent<S, STEP>) subAgents.getFirst();
    }

    /**
     * Persists the active sub-agent name on the session entity. Failures are logged at
     * {@code WARN} level and swallowed so that routing is never interrupted by a persistence
     * hiccup.
     *
     * @param session   the session entity to update
     * @param agentName the name of the newly active sub-agent
     */
    private void updateSessionSubAgent(final S session, final String agentName) {
        try {
            session.setActiveSubAgent(agentName);
            sessionRepository.save(session);
        } catch (Exception ex) {
            log.warn(
                    "Failed to persist activeSubAgent={} for session={}: {}",
                    agentName,
                    session.getSessionId(),
                    ex.getMessage());
        }
    }

    /**
     * Scans {@code buffer} for readable sentence boundaries and emits any complete chunks
     * as {@code message} SSE events via {@code emitter}.
     *
     * <p>Boundaries are detected at: newline characters, sentence-ending punctuation
     * ({@code .}, {@code !}, {@code ?}), and a 240-character overflow threshold. Chunks are
     * emitted and removed from the buffer as each boundary is found.
     *
     * @param buffer  the text buffer accumulating streamed tokens; mutated in place
     * @param emitter the pipeline emitter to write events to
     */
    private void flushReadyChunks(final StringBuilder buffer, final PipelineEmitter emitter) {
        int boundary = findReadableBoundary(buffer);
        while (boundary >= 0) {
            String chunk = buffer.substring(0, boundary + 1);
            if (!chunk.isBlank()) {
                emitter.sendMessage(chunk);
            }
            buffer.delete(0, boundary + 1);
            boundary = findReadableBoundary(buffer);
        }
    }

    /**
     * Returns the index of the next readable chunk boundary within {@code buffer}, or
     * {@code -1} when no boundary has been reached.
     *
     * <p>Boundary priority: newline characters first, then sentence-ending punctuation
     * (skipping likely abbreviations), then the 240-character overflow threshold.
     *
     * @param buffer the text buffer to scan
     * @return zero-based boundary index, or {@code -1} when no boundary is present
     */
    private int findReadableBoundary(final StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c == '\n') {
                return i;
            }
            if ((c == '.' || c == '!' || c == '?') && i < buffer.length() - 1) {
                return i;
            }
        }
        if (buffer.length() >= 240) {
            for (int i = buffer.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(buffer.charAt(i))) {
                    return i;
                }
            }
            return 239;
        }
        return -1;
    }
}
