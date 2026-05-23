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
package io.agentcore.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Stubbed {@link ChatModel} for unit and integration testing of agent pipelines.
 *
 * <p>Returns pre-configured responses in sequence, eliminating the need to call
 * a real LLM gateway in tests. Captures all calls for assertion.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MockChatModel mockModel = MockChatModel.withResponses(
 *         "I'll help you with that.",
 *         "Here is the result: ..."
 * );
 *
 * // Use in test context
 * @TestConfiguration
 * class TestConfig {
 *     @Bean @Primary
 *     public ChatModel chatModel() {
 *         return MockChatModel.withResponses("Test response");
 *     }
 * }
 *
 * // Assert after test
 * assertThat(mockModel.getCallCount()).isEqualTo(1);
 * assertThat(mockModel.getLastPrompt().getInstructions()).isNotEmpty();
 * }</pre>
 *
 * <p>When all pre-configured responses are consumed, subsequent calls return
 * the last response (or a default message if none were configured).
 */
public class MockChatModel implements ChatModel {

    private static final String DEFAULT_RESPONSE = "Mock response";

    private final Deque<String> responses;
    private final List<Prompt> capturedPrompts = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger(0);
    private String lastResponse = DEFAULT_RESPONSE;

    private MockChatModel(final List<String> responses) {
        this.responses = new ArrayDeque<>(responses);
        if (!responses.isEmpty()) {
            this.lastResponse = responses.get(responses.size() - 1);
        }
    }

    /**
     * Creates a mock model that returns the given responses in sequence.
     *
     * @param responses the responses to return, in order
     */
    public static MockChatModel withResponses(final String... responses) {
        return new MockChatModel(List.of(responses));
    }

    /**
     * Creates a mock model that always returns the same response.
     *
     * @param response the response to always return
     */
    public static MockChatModel always(final String response) {
        return new MockChatModel(List.of(response));
    }

    /**
     * Creates a mock model that returns a default placeholder response.
     */
    public static MockChatModel defaults() {
        return new MockChatModel(List.of(DEFAULT_RESPONSE));
    }

    @Override
    public ChatResponse call(final Prompt prompt) {
        callCount.incrementAndGet();
        capturedPrompts.add(prompt);

        String response = responses.isEmpty() ? lastResponse : responses.poll();
        AssistantMessage message = new AssistantMessage(Objects.requireNonNull(response, "Response must not be null"));
        Generation generation = new Generation(message);
        return new ChatResponse(List.of(generation));
    }

    // ─── Assertion helpers ────────────────────────────────────────────────────

    /**
     * Returns the total number of times {@link #call(Prompt)} was invoked.
     */
    public int getCallCount() {
        return callCount.get();
    }

    /**
     * Returns all prompts captured in the order they were received.
     */
    public List<Prompt> getCapturedPrompts() {
        return List.copyOf(capturedPrompts);
    }

    /**
     * Returns the most recent prompt passed to {@link #call(Prompt)}, or null if never called.
     */
    public Prompt getLastPrompt() {
        return capturedPrompts.isEmpty() ? null : capturedPrompts.get(capturedPrompts.size() - 1);
    }

    /**
     * Returns true if {@link #call(Prompt)} has been invoked at least once.
     */
    public boolean wasCalled() {
        return callCount.get() > 0;
    }

    /**
     * Resets the call count and captured prompts (useful between test cases in parameterized tests).
     */
    public void reset() {
        callCount.set(0);
        capturedPrompts.clear();
    }
}
