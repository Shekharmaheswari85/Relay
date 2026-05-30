/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.llm;

import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resilient {@link ChatModel} implementation that delegates calls to a chain of fallback
 * {@link ChatModel} instances.
 *
 * <p>If a delegate call fails (e.g., due to an HTTP 429 rate limit or HTTP 503 overload),
 * {@code FailoverChatModel} intercepts the exception, logs a warning, and immediately retries the
 * request on the next provider in the configured chain. This provides seamless high-availability
 * and rate-limit mitigation across multiple completely distinct LLM gateways.
 */
@RequiredArgsConstructor
@Slf4j
public class FailoverChatModel implements ChatModel {

    private final List<ChatModel> delegates;

    @Override
    public ChatResponse call(Prompt prompt) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalStateException("No delegate ChatModels configured for failover");
        }
        
        Throwable lastException = null;
        for (int i = 0; i < delegates.size(); i++) {
            ChatModel delegate = delegates.get(i);
            try {
                log.debug("FailoverChatModel: Attempting call on delegate index {}/{}", i + 1, delegates.size());
                return delegate.call(prompt);
            } catch (Exception ex) {
                lastException = ex;
                log.warn("FailoverChatModel: Delegate index {} failed with exception: {}. Trying next delegate...", 
                        i + 1, ex.getMessage());
            }
        }
        
        throw new RuntimeException("FailoverChatModel: All delegate models failed. Last error: " + 
                (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalStateException("No delegate ChatModels configured for failover");
        }
        
        Throwable lastException = null;
        for (int i = 0; i < delegates.size(); i++) {
            ChatModel delegate = delegates.get(i);
            try {
                return delegate.stream(prompt);
            } catch (Exception ex) {
                lastException = ex;
                log.warn("FailoverChatModel stream: Delegate index {} failed to stream: {}. Trying next...", 
                        i + 1, ex.getMessage());
            }
        }
        
        throw new RuntimeException("FailoverChatModel stream: All delegates failed. Last error: " + 
                (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegates.isEmpty() ? null : delegates.get(0).getDefaultOptions();
    }
}
