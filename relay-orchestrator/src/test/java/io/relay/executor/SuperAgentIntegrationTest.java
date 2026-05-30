/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import io.relay.agent.AgentExecutionContext;
import io.relay.agent.BaseSubAgent;
import io.relay.dto.AgentMetadataDTO;
import io.relay.dto.BaseAuditTrailResponse;
import io.relay.dto.BaseBulkDeleteResponse;
import io.relay.dto.BaseCreateSessionRequest;
import io.relay.dto.BaseCreateSessionResponse;
import io.relay.dto.BaseDeleteSessionResponse;
import io.relay.dto.BaseResumeSessionResponse;
import io.relay.dto.BaseSessionStatusResponse;
import io.relay.llm.ChatClientRegistry;
import io.relay.llm.ModelTier;
import io.relay.observability.AgentObservabilityService;
import io.relay.orchestrator.BaseAgentOrchestrator;
import io.relay.repository.BaseAgentSessionRepository;
import io.relay.stream.PipelineEmitter;
import io.relay.stream.ToolProgressPublisher;
import io.relay.session.SessionContextManager;

import io.relay.llm.LlmProvider;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "relay.cache.type=inmemory",
        "agent.virtual-threads.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.ai.openai.api-key=test-key"
})
public class SuperAgentIntegrationTest {

    @Autowired
    private TestAgentRuntimeService runtimeService;

    @Autowired
    private TestAgentSessionRepository sessionRepository;

    @Autowired
    @Qualifier("virtualThreadExecutor")
    private ThreadPoolTaskExecutor virtualThreadExecutor;

    @BeforeEach
    public void setUp() {
        sessionRepository.deleteAll();
    }

    @Test
    public void testSuperAgentVirtualThreadHandoffAndStreaming() throws Exception {
        // 1. Create a new test session
        BaseCreateSessionRequest createReq = new BaseCreateSessionRequest();
        createReq.setAgentId("super-agent");
        createReq.setCreatedBy("test-user");
        createReq.setTenantId("test-tenant");

        BaseCreateSessionResponse createRes = runtimeService.createSession(createReq);
        String sessionId = createRes.getSessionId();
        assertThat(sessionId).isNotEmpty();

        // Verify session persistence in DB
        Optional<TestAgentSession> dbSession = sessionRepository.findBySessionId(sessionId);
        assertThat(dbSession).isPresent();
        assertThat(dbSession.get().getCurrentStep()).isEqualTo("INIT");
        assertThat(dbSession.get().getStatus()).isEqualTo("ACTIVE");

        // 2. Set up SseEventCaptor with a CountDownLatch to synchronize on streaming
        // completion
        CountDownLatch latch = new CountDownLatch(1);
        SseEventCaptor captor = new SseEventCaptor(sessionId, latch);

        // 3. Send message using virtual threads
        runtimeService.sendMessage(sessionId, "Route me to Billing agent", captor);

        // 4. Wait for stream to complete
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // 5. Assert the SSE events streamed
        List<SseEventCaptor.SseEvent> events = captor.getEvents();
        assertThat(events).isNotEmpty();

        // Assert stage event
        boolean hasExecutionStage = events.stream()
                .anyMatch(e -> "stage".equals(e.eventType()) && e.data().contains("agent_execution"));
        assertThat(hasExecutionStage).isTrue();

        // Assert parent prompt execution
        String messageText = captor.getMessageText();
        assertThat(messageText).contains("Parent LLM output");

        // 6. Change session step to BILLING to trigger delegation handoff and A2A mock
        // streaming
        TestAgentSession session = sessionRepository.findBySessionId(sessionId).orElseThrow();
        session.setCurrentStep("BILLING");
        sessionRepository.save(session);

        CountDownLatch handoffLatch = new CountDownLatch(1);
        SseEventCaptor handoffCaptor = new SseEventCaptor(sessionId, handoffLatch);

        // Send next message to trigger custom A2A execution on Billing sub-agent
        runtimeService.sendMessage(sessionId, "Process my invoice", handoffCaptor);

        boolean handoffCompleted = handoffLatch.await(10, TimeUnit.SECONDS);
        assertThat(handoffCompleted).isTrue();

        List<SseEventCaptor.SseEvent> handoffEvents = handoffCaptor.getEvents();

        // Verify agent handoff event was emitted
        boolean hasHandoffEvent = handoffEvents.stream()
                .anyMatch(e -> "agent_handoff".equals(e.eventType()));
        assertThat(hasHandoffEvent).isTrue();

        // Verify delegated streaming text
        String handoffMsg = handoffCaptor.getMessageText();
        assertThat(handoffMsg).contains("Billing Delegated chunk 1");
        assertThat(handoffMsg).contains("Billing Delegated chunk 2");

        // Verify that the thread handling the delegate routing was a Project Loom
        // virtual thread
        List<SseEventCaptor.SseEvent> thinkingEvents = handoffEvents.stream()
                .filter(e -> "thinking".equals(e.eventType()))
                .toList();
        assertThat(thinkingEvents).isNotEmpty();
        assertThat(thinkingEvents.get(0).data()).contains("Thread is virtual: true");
    }

    // ─── Spring Boot Test Configuration & Beans ─────────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = "io.relay")
    static class TestConfig {

        @Bean
        @Primary
        public ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    AssistantMessage message = new AssistantMessage("Parent LLM output recommending Billing division.");
                    return new ChatResponse(List.of(new Generation(message)));
                }
            };
        }

        @Bean
        @Primary
        public ChatClientRegistry chatClientRegistry(ChatModel chatModel) {
            ChatClient client = ChatClient.builder(chatModel).build();
            return new ChatClientRegistry(
                    Map.of(LlmProvider.OPENAI, client),
                    client,
                    client,
                    LlmProvider.OPENAI);
        }

        @Bean
        public ToolProgressPublisher toolProgressPublisher() {
            return new ToolProgressPublisher();
        }

        @Bean
        public SessionContextManager sessionContextManager(ObjectMapper objectMapper) {
            return new SessionContextManager(objectMapper);
        }

        @Bean
        public AgentObservabilityService agentObservabilityService(MeterRegistry meterRegistry) {
            return new AgentObservabilityService(meterRegistry);
        }

        @Bean("virtualThreadExecutor")
        public ThreadPoolTaskExecutor virtualThreadExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setThreadFactory(Thread.ofVirtual().factory());
            executor.setThreadNamePrefix("agent-vt-");
            executor.initialize();
            return executor;
        }

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    // ─── Custom SuperAgent Executor & Orchestrator ──────────────────────────

    @Component
    public static class SuperAgentExecutor implements
            AgentExecutor<BaseCreateSessionRequest, BaseCreateSessionResponse, BaseSessionStatusResponse, BaseDeleteSessionResponse, BaseBulkDeleteResponse, BaseResumeSessionResponse, BaseAuditTrailResponse> {

        @Autowired
        private TestAgentSessionRepository sessionRepository;

        @Autowired
        private TestAgentOrchestrator orchestrator;

        @Autowired
        @Qualifier("virtualThreadExecutor")
        private ThreadPoolTaskExecutor virtualThreadExecutor;

        @Override
        public String agentId() {
            return "super-agent";
        }

        @Override
        public AgentMetadataDTO metadata() {
            return AgentMetadataDTO.builder()
                    .agentId("super-agent")
                    .name("SuperAgent")
                    .description("Virtual-thread parent orchestrator agent")
                    .build();
        }

        @Override
        public BaseCreateSessionResponse createSession(BaseCreateSessionRequest request) {
            String sessionId = "sess-" + System.currentTimeMillis();
            TestAgentSession session = TestAgentSession.builder()
                    .sessionId(sessionId)
                    .agentId(agentId())
                    .currentStep("INIT")
                    .status("ACTIVE")
                    .createdBy(request.getCreatedBy())
                    .tenantId(request.getTenantId())
                    .build();
            sessionRepository.save(session);

            BaseCreateSessionResponse res = new BaseCreateSessionResponse();
            res.setSessionId(sessionId);
            return res;
        }

        @Override
        public void sendMessage(String sessionId, String content, PipelineEmitter emitter) {
            TestAgentSession session = sessionRepository.findBySessionId(sessionId).orElseThrow();

            // Execute the turn asynchronously on virtual threads
            virtualThreadExecutor.execute(() -> {
                try {
                    orchestrator.route(session, sessionId, content, emitter, new HashMap<>());
                } catch (Exception e) {
                    emitter.sendError(e.getMessage());
                    emitter.complete();
                }
            });
        }

        @Override
        public List<BaseSessionStatusResponse> listSessions(String status) {
            return List.of();
        }

        @Override
        public BaseDeleteSessionResponse deleteSession(String sessionId) {
            sessionRepository.deleteBySessionId(sessionId);
            return new BaseDeleteSessionResponse();
        }

        @Override
        public BaseBulkDeleteResponse bulkDeleteSessions(List<String> sessionIds) {
            return new BaseBulkDeleteResponse();
        }

        @Override
        public BaseSessionStatusResponse getSession(String sessionId) {
            return new BaseSessionStatusResponse();
        }

        @Override
        public BaseResumeSessionResponse resumeSession(String sessionId) {
            return new BaseResumeSessionResponse();
        }

        @Override
        public BaseAuditTrailResponse getAuditTrail(String sessionId, String eventType) {
            return new BaseAuditTrailResponse();
        }
    }

    @Component
    public static class TestAgentOrchestrator extends BaseAgentOrchestrator<TestAgentSession, String> {

        public TestAgentOrchestrator(
                ChatClientRegistry chatClientRegistry,
                SessionContextManager sessionContextManager,
                ToolProgressPublisher toolProgressPublisher,
                BaseAgentSessionRepository<TestAgentSession> sessionRepository,
                List<? extends BaseSubAgent<TestAgentSession, String>> subAgents,
                AgentObservabilityService observabilityService,
                @Qualifier("virtualThreadExecutor") ThreadPoolTaskExecutor virtualThreadExecutor) {
            super(chatClientRegistry, sessionContextManager, toolProgressPublisher, sessionRepository, subAgents,
                    observabilityService, virtualThreadExecutor);
        }

        @Override
        protected String parseCurrentStep(TestAgentSession session) {
            return session.getCurrentStep();
        }

        @Override
        protected String getSystemPrompt(BaseSubAgent<TestAgentSession, String> subAgent, TestAgentSession session,
                Map<String, Object> context) {
            return "System Base Prompt";
        }

        @Override
        protected String buildUserPrompt(TestAgentSession session, String messageContent, Map<String, Object> context) {
            return messageContent;
        }

        @Override
        protected ModelTier resolveModelTier(String step) {
            return ModelTier.UTILITY;
        }
    }

    @Service
    public static class TestAgentRuntimeService extends BaseAgentRuntimeService<TestAgentSession> {

        public TestAgentRuntimeService(
                AgentRegistry registry,
                BaseAgentSessionRepository<TestAgentSession> repository,
                ObjectMapper objectMapper) {
            super(registry, repository, objectMapper);
        }

        @Override
        protected String getDefaultAgentId() {
            return "super-agent";
        }
    }

    // ─── Sub-Agents representing the multi-agent delegation pipeline ────────

    @Component
    public static class ParentSuperAgent implements BaseSubAgent<TestAgentSession, String> {

        @Override
        public String name() {
            return "parent-super-agent";
        }

        @Override
        public boolean canHandle(TestAgentSession session, String currentStep) {
            return "INIT".equals(currentStep);
        }
    }

    @Component
    public static class BillingAgent implements BaseSubAgent<TestAgentSession, String> {

        @Override
        public String name() {
            return "billing-agent";
        }

        @Override
        public boolean canHandle(TestAgentSession session, String currentStep) {
            return "BILLING".equals(currentStep);
        }

        @Override
        public boolean handlesExecution() {
            return true; // Decides to handle execution internally (mocking A2A HTTP client)
        }

        @Override
        public void execute(AgentExecutionContext<TestAgentSession> ctx) {
            // Demonstrate execution happens on Loom Virtual Threads
            boolean isVirtual = Thread.currentThread().isVirtual();
            ctx.emitter().sendThinking("Thread is virtual: " + isVirtual);

            // Mock A2A stream execution
            ctx.emitter().sendStage("billing_execution", "Billing processing", 80);
            ctx.emitter().sendMessage("Billing Delegated chunk 1. ");
            ctx.emitter().sendMessage("Billing Delegated chunk 2.");
            ctx.emitter().sendDone();
        }
    }

    // ─── SSE Event Captor for testing ────────────────────────────────────────

    public static class SseEventCaptor extends PipelineEmitter {

        public record SseEvent(String eventType, String data) {
        }

        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        public SseEventCaptor(String sessionId, CountDownLatch latch) {
            super(new SseEmitter(0L), sessionId);
            this.latch = latch;
        }

        @Override
        public void sendThinking(String json) {
            events.add(new SseEvent("thinking", json));
        }

        @Override
        public void sendMessage(String chunk) {
            if (chunk != null && !chunk.isEmpty()) {
                events.add(new SseEvent("message", chunk));
            }
        }

        @Override
        public void sendToolProgress(String json) {
            events.add(new SseEvent("tool_progress", json));
        }

        @Override
        public void sendStage(String stage, String label, int progress) {
            String json = String.format("{\"stage\":\"%s\",\"label\":\"%s\",\"progress\":%d}", stage, label, progress);
            events.add(new SseEvent("stage", json));
        }

        @Override
        public void sendAgentHandoff(String toAgent, String reason) {
            events.add(
                    new SseEvent("agent_handoff", "{\"toAgent\":\"" + toAgent + "\",\"reason\":\"" + reason + "\"}"));
        }

        @Override
        public void sendDone() {
            events.add(new SseEvent("done", "{}"));
            latch.countDown();
        }

        @Override
        public void sendError(String detail) {
            events.add(new SseEvent("error", "{\"error\":\"" + detail + "\"}"));
            latch.countDown();
        }

        public List<SseEvent> getEvents() {
            return List.copyOf(events);
        }

        public String getMessageText() {
            return events.stream()
                    .filter(e -> "message".equals(e.eventType()))
                    .map(SseEvent::data)
                    .collect(Collectors.joining());
        }
    }
}
