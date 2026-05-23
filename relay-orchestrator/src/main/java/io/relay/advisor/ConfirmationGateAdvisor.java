/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.advisor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import io.relay.tool.AgentTool;
import io.relay.tool.ToolCategory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Advisor that gates mutation tool calls by checking the conversation
 * context for user confirmation. If a mutation tool is about to be called
 * and no confirmation is found, the advisor blocks the call and returns
 * a CONFIRMATION_REQUIRED response.
 * <p>
 * Mutation tools are auto-detected from {@code @AgentTool(category = MUTATION)}
 * annotations. Teams can extend and override {@link #getAdditionalMutationTools()}
 * to add custom mutation tools.
 * <p>
 * Highest precedence — runs first in the advisor chain.
 *
 * <h3>Context keys</h3>
 * <ul>
 *   <li>{@code pending_mutation} - boolean indicating a mutation is pending</li>
 *   <li>{@code pending_tool} - name of the pending mutation tool</li>
 *   <li>{@code user_confirmed} - boolean indicating user has confirmed</li>
 * </ul>
 */
@Component
@Slf4j
public class ConfirmationGateAdvisor implements CallAdvisor {

    public static final String CONFIRMATION_KEY = "user_confirmed";
    public static final String PENDING_MUTATION_KEY = "pending_mutation";
    public static final String PENDING_TOOL_KEY = "pending_tool";

    /**
     * Prefix injected by {@code POST /sessions/{sessionId}/confirm} when the user approves.
     * {@code ConfirmationGateAdvisor} detects this prefix and enriches the context with
     * {@code user_confirmed=true} before forwarding to the next advisor.
     */
    public static final String CONFIRM_PREFIX = "__AGENT_CONFIRM__:";

    /**
     * Prefix injected by {@code POST /sessions/{sessionId}/confirm} when the user rejects.
     * {@code ConfirmationGateAdvisor} detects this prefix and returns a rejection response
     * without calling the LLM.
     */
    public static final String REJECT_PREFIX = "__AGENT_REJECT__:";

    private static final String CONFIRMATION_REQUIRED_MSG =
            "CONFIRMATION_REQUIRED: This operation requires user confirmation before proceeding. "
                    + "Please review the plan above and confirm with 'yes' or 'approve' to continue.";

    private final Counter blockedCounter;
    private final ApplicationContext applicationContext;

    private Set<String> cachedMutationTools;

    public ConfirmationGateAdvisor(final MeterRegistry meterRegistry, final ApplicationContext applicationContext) {
        this.blockedCounter = Counter.builder("relay.gate.confirmation.blocked")
                .description("Number of mutation tool calls blocked pending user confirmation")
                .register(meterRegistry);
        this.applicationContext = applicationContext;
    }

    @Override
    public @NonNull String getName() {
        return "ConfirmationGateAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest chatClientRequest, final @NonNull CallAdvisorChain callAdvisorChain) {

        // ── Intercept confirmation / rejection signals from POST /sessions/{id}/confirm ──
        String userText = extractUserText(chatClientRequest);
        if (userText != null) {
            if (userText.startsWith(CONFIRM_PREFIX)) {
                String toolName = userText.substring(CONFIRM_PREFIX.length()).strip();
                if (!toolName.isBlank()) {
                    return Objects.requireNonNull(
                            processConfirmationSignal(chatClientRequest, toolName, true, callAdvisorChain),
                            "Advisor response must not be null");
                }
            } else if (userText.startsWith(REJECT_PREFIX)) {
                String toolName = userText.substring(REJECT_PREFIX.length()).strip();
                if (!toolName.isBlank()) {
                    return Objects.requireNonNull(
                            processConfirmationSignal(chatClientRequest, toolName, false, callAdvisorChain),
                            "Advisor response must not be null");
                }
            }
        }

        // ── Normal gate: block un-confirmed mutation tool calls ───────────────────────
        Map<String, Object> context = chatClientRequest.context();

        boolean pendingMutation = Boolean.TRUE.equals(context.get(PENDING_MUTATION_KEY));
        String pendingTool = extractPendingTool(context);

        if (pendingMutation && pendingTool != null && isMutationTool(pendingTool)) {
            Boolean confirmed = (Boolean) context.get(CONFIRMATION_KEY);
            if (!Boolean.TRUE.equals(confirmed)) {
                log.info("Confirmation gate blocked mutation tool: {}", pendingTool);
                blockedCounter.increment();
                return Objects.requireNonNull(buildBlockedResponse(chatClientRequest), "Blocked response must not be null");
            }
        }

        return Objects.requireNonNull(callAdvisorChain.nextCall(chatClientRequest), "Advisor response must not be null");
    }

    /**
     * Checks if the given tool is a mutation tool.
     *
     * @param toolName the tool name
     * @return true if it's a mutation tool requiring confirmation
     */
    public boolean isMutationTool(final String toolName) {
        return getMutationTools().contains(toolName);
    }

    /**
     * Returns all mutation tools (auto-detected + additional).
     */
    public Set<String> getMutationTools() {
        Set<String> autoDetected = getAutoDetectedMutationTools();
        Set<String> additional = getAdditionalMutationTools();

        if (additional.isEmpty()) {
            return autoDetected;
        }

        return Stream.concat(autoDetected.stream(), additional.stream())
                .collect(Collectors.toSet());
    }

    /**
     * Override to add custom mutation tools beyond auto-detected ones.
     */
    protected Set<String> getAdditionalMutationTools() {
        return Collections.emptySet();
    }

    /**
     * Override to customize the blocked response message.
     */
    protected String getBlockedMessage() {
        return CONFIRMATION_REQUIRED_MSG;
    }

    private String extractUserText(final ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(msg -> ((UserMessage) msg).getText())
                .findFirst()
                .orElse(null);
    }

    private @NonNull ChatClientResponse processConfirmationSignal(
            final ChatClientRequest request,
            final String toolName,
            final boolean confirmed,
            final CallAdvisorChain chain) {

        if (confirmed) {
            log.info("Confirmation gate: user approved mutation tool '{}'", toolName);
            Map<String, Object> enrichedContext = new HashMap<>(request.context());
            enrichedContext.put(CONFIRMATION_KEY, true);
            enrichedContext.put(PENDING_MUTATION_KEY, true);
            enrichedContext.put(PENDING_TOOL_KEY, toolName);
            ChatClientRequest approvedRequest = ChatClientRequest.builder()
                    .prompt(request.prompt())
                    .context(enrichedContext)
                    .build();
            return Objects.requireNonNull(chain.nextCall(approvedRequest), "Advisor response must not be null");
        }

        log.info("Confirmation gate: user rejected mutation tool '{}'", toolName);
        AssistantMessage rejection = new AssistantMessage(
                "REJECTED: The operation '" + toolName + "' was cancelled by the user.");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(rejection)));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }

    private Set<String> getAutoDetectedMutationTools() {
        if (cachedMutationTools != null) {
            return cachedMutationTools;
        }

        if (applicationContext == null) {
            cachedMutationTools = Collections.emptySet();
            return cachedMutationTools;
        }

        cachedMutationTools = applicationContext.getBeansWithAnnotation(AgentTool.class)
                .entrySet()
                .stream()
                .filter(entry -> {
                    AgentTool annotation = AnnotatedElementUtils.findMergedAnnotation(entry.getValue().getClass(), AgentTool.class);
                    return annotation != null && annotation.category() == ToolCategory.MUTATION;
                })
                .map(entry -> resolveToolName(entry.getKey(), entry.getValue()))
                .collect(Collectors.toSet());

        log.info("Auto-detected {} mutation tools for confirmation gate: {}", cachedMutationTools.size(), cachedMutationTools);
        return cachedMutationTools;
    }

    private String resolveToolName(final String beanName, final Object bean) {
        for (var method : bean.getClass().getMethods()) {
            var toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null) {
                String name = toolAnnotation.name();
                if (!name.isBlank()) {
                    return name;
                }
                return method.getName();
            }
        }
        return beanName;
    }

    private String extractPendingTool(final Map<String, Object> context) {
        Object rawPendingTool = context.get(PENDING_TOOL_KEY);
        if (rawPendingTool == null) {
            return null;
        }
        String pendingTool = rawPendingTool.toString().strip();
        return pendingTool.isBlank() ? null : pendingTool;
    }

    private @NonNull ChatClientResponse buildBlockedResponse(final ChatClientRequest request) {
        AssistantMessage assistantMessage =
                new AssistantMessage(Objects.requireNonNull(getBlockedMessage(), "Blocked message must not be null"));
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(request.context())
                .build();
    }
}
