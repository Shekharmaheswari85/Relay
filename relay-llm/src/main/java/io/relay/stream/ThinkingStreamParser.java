/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Stateful stream parser that splits LLM output into {@code thinking} and {@code message}
 * SSE events by detecting {@code <think>…</think>} tag boundaries in the raw token stream.
 *
 * <h3>How Claude-like thinking works</h3>
 * <p>Claude's API natively returns {@code thinking} blocks — internal chain-of-thought tokens
 * — before the final answer. This parser achieves the same effect for <em>any</em> model by
 * injecting a system prompt instruction that tells the model to wrap its reasoning in
 * {@code <think>} tags. The parser then separates those tokens from the answer tokens and
 * routes them to different SSE events in real time — exactly the experience shown in
 * Claude Code's "Thinking…" panel.
 *
 * <h3>System prompt to activate thinking</h3>
 * <p>Add this instruction to the model's system prompt (exposed as {@link #THINKING_INSTRUCTION}):
 * <pre>{@code
 * Before answering, enclose your step-by-step reasoning in <think>...</think> tags.
 * Think through: what the user is asking, what information you have, and the best response.
 * Your final answer goes after the closing </think> tag.
 * }</pre>
 *
 * <h3>How the parser works</h3>
 * <p>The parser maintains a simple three-state machine and a lookahead tag buffer to handle
 * {@code <think>} and {@code </think>} tags split across chunk boundaries:
 *
 * <pre>
 *  AWAITING ──── sees &lt;think&gt; ───► THINKING ──── sees &lt;/think&gt; ───► ANSWERING
 *      │                                  │                                    │
 *  sees non-tag text                  each chunk                           each chunk
 *      │                              emitted as                           emitted as
 *      ▼                             "thinking" SSE                       "message" SSE
 *  ANSWERING
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ThinkingStreamParser parser = new ThinkingStreamParser();
 * StringBuilder accumulator = new StringBuilder();
 *
 * for (String chunk : llmClient.stream(prompt).toIterable()) {
 *     accumulator.append(chunk);
 *     parser.process(chunk, emitter);
 * }
 * parser.flush(emitter);          // flush any buffered answer text
 * emitter.sendDone();
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are stateful and NOT thread-safe. Create one instance per agent turn.
 *
 * @see PipelineEmitter
 */
@Slf4j
public class ThinkingStreamParser {

    /**
     * System prompt instruction to prepend when enabling chain-of-thought thinking.
     * Add this to the beginning of your agent's system prompt.
     */
    public static final String THINKING_INSTRUCTION = """
            Before answering, enclose your step-by-step reasoning inside <think>...</think> tags. Think through: what the user is asking, what context you have, and the best way to respond. Your final answer to the user goes after the closing </think> tag.

            """;

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";
    private static final int MAX_TAG_LEN = CLOSE_TAG.length(); // longest tag = 8 chars

    private enum State { AWAITING, THINKING, ANSWERING }

    private State state = State.AWAITING;

    /**
     * Lookahead buffer — accumulates characters when we suspect we might be inside
     * a {@code <think>} or {@code </think>} tag that is split across chunks.
     */
    private final StringBuilder tagBuffer = new StringBuilder(MAX_TAG_LEN + 1);

    /**
     * Sentence-boundary buffer for message (answer) content — accumulates text until
     * a natural emit boundary (punctuation, newline, or max length) is reached.
     */
    private final StringBuilder answerBuffer = new StringBuilder(256);

    private static final int MAX_ANSWER_CHUNK = 240;

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Processes one raw text chunk from the LLM token stream and emits the appropriate
     * SSE events to {@code emitter}.
     *
     * <p>This method handles tag detection across chunk boundaries. Call it for every
     * chunk produced by the LLM stream, then call {@link #flush(PipelineEmitter)} once
     * the stream ends.
     *
     * @param chunk   raw token chunk from the LLM; may be empty but never null
     * @param emitter the pipeline emitter to write events to
     */
    public void process(final String chunk, final PipelineEmitter emitter) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            processChar(c, emitter);
        }
    }

    /**
     * Flushes any buffered answer text to a final {@code message} SSE event.
     * Call once after the last chunk has been processed.
     *
     * @param emitter the pipeline emitter to write to
     */
    public void flush(final PipelineEmitter emitter) {
        // Flush any partial tag buffer as answer text (tag never closed)
        if (!tagBuffer.isEmpty()) {
            String pending = tagBuffer.toString();
            tagBuffer.setLength(0);
            feedToAnswer(pending, emitter);
        }
        // Flush remaining answer buffer
        if (!answerBuffer.isEmpty()) {
            emitter.sendMessage(answerBuffer.toString());
            answerBuffer.setLength(0);
        }
    }

    /**
     * Resets the parser to its initial state. Call between agent turns when reusing an instance.
     */
    public void reset() {
        state = State.AWAITING;
        tagBuffer.setLength(0);
        answerBuffer.setLength(0);
    }

    /**
     * Returns {@code true} if the parser has seen a {@code <think>} block, indicating the
     * model produced chain-of-thought output.
     */
    public boolean hasThinkingContent() {
        return state == State.THINKING || state == State.ANSWERING;
    }

    // ─── State machine ────────────────────────────────────────────────────────

    private void processChar(final char c, final PipelineEmitter emitter) {
        switch (state) {
            case AWAITING -> processAwaiting(c, emitter);
            case THINKING -> processThinking(c, emitter);
            case ANSWERING -> processAnswering(c, emitter);
        }
    }

    private void processAwaiting(final char c, final PipelineEmitter emitter) {
        tagBuffer.append(c);

        if (OPEN_TAG.startsWith(tagBuffer.toString())) {
            // Could be the opening tag — keep accumulating
            if (tagBuffer.toString().equals(OPEN_TAG)) {
                // Full <think> tag detected
                tagBuffer.setLength(0);
                state = State.THINKING;
                log.debug("ThinkingStreamParser: entering THINKING state");
            }
            // else keep accumulating — partial tag match
        } else {
            // Not a tag — flush buffer as answer text
            String pending = tagBuffer.toString();
            tagBuffer.setLength(0);
            state = State.ANSWERING;
            feedToAnswer(pending, emitter);
        }
    }

    private void processThinking(final char c, final PipelineEmitter emitter) {
        tagBuffer.append(c);

        if (CLOSE_TAG.startsWith(tagBuffer.toString())) {
            if (tagBuffer.toString().equals(CLOSE_TAG)) {
                // </think> complete
                tagBuffer.setLength(0);
                state = State.ANSWERING;
                log.debug("ThinkingStreamParser: entering ANSWERING state");
            }
            // else keep accumulating partial close-tag
        } else {
            // Not the close tag — flush buffer as thinking content
            String pending = tagBuffer.toString();
            tagBuffer.setLength(0);
            emitThinkingChunk(pending, emitter);
        }
    }

    private void processAnswering(final char c, final PipelineEmitter emitter) {
        answerBuffer.append(c);

        // Emit on natural boundaries or max length
        if (isAnswerBoundary(c) || answerBuffer.length() >= MAX_ANSWER_CHUNK) {
            emitter.sendMessage(answerBuffer.toString());
            answerBuffer.setLength(0);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void feedToAnswer(final String text, final PipelineEmitter emitter) {
        for (int i = 0; i < text.length(); i++) {
            processAnswering(text.charAt(i), emitter);
        }
    }

    private void emitThinkingChunk(final String content, final PipelineEmitter emitter) {
        if (content.isEmpty()) return;
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        emitter.sendThinking("{\"phase\":\"reasoning\",\"content\":\"" + escaped + "\"}");
    }

    private boolean isAnswerBoundary(final char c) {
        return c == '\n' || c == '.' || c == '!' || c == '?' || c == ';';
    }
}
