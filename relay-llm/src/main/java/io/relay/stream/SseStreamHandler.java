/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import lombok.extern.slf4j.Slf4j;

/**
 * Provides the full pipeline for turning a raw LLM token stream into well-formed
 * text chunks suitable for delivery to browser clients via SSE.
 *
 * <p>The handler covers three concerns:
 * <ol>
 *   <li><strong>Chunking</strong> — buffers incoming tokens and emits them at
 *       readable boundaries (newline, sentence-ending punctuation, or a 240-character
 *       overflow limit) so the client receives coherent text fragments rather than
 *       individual tokens.</li>
 *   <li><strong>Sanitization</strong> — strips raw SSE protocol artefacts
 *       ({@code data:}, {@code event:}, {@code :keep-alive}) that the upstream
 *       LLM client may inadvertently include, and masks session-management phrases
 *       that the model should never surface to end users.</li>
 *   <li><strong>Error detail extraction</strong> — builds human-readable error
 *       descriptions from throwables, including HTTP response bodies for
 *       {@link HttpStatusCodeException}s (A2A scenarios).</li>
 * </ol>
 *
 * <h3>Typical usage inside an agent orchestrator</h3>
 * <pre>{@code
 * @Autowired SseStreamHandler sseHandler;
 *
 * llmClient.streamResponse().forEach(chunk ->
 *     sseHandler.appendReadableStreamChunk(
 *         accumulator, streamBuffer, chunk, emitter::sendMessage));
 * }</pre>
 *
 * <p>This class is thread-safe; all mutable state it holds is limited to an
 * {@link java.util.concurrent.atomic.AtomicInteger} counter used for debug logging.
 */
@Component
@Slf4j
public class SseStreamHandler {

    private static final int STREAM_EMIT_BUFFER_THRESHOLD = 240;
    private static final int ABBREVIATION_MIN_DOT_INDEX = 2;
    private static final int ABBREVIATION_DOUBLE_DOT_OFFSET = 2;
    private static final int ABBREVIATION_PREVIOUS_CHAR_OFFSET = 3;
    private static final int MARKDOWN_TABLE_MIN_PIPE_COUNT = 2;
    private static final int MALFORMED_SSE_SHORT_FRAGMENT_LENGTH = 2;
    private static final int MALFORMED_SSE_LOG_EVERY_N = 25;
    private static final int TRUNCATION_LENGTH = 80;

    private static final Pattern CONFIRMATION_REQUIRED_PATTERN = Pattern.compile(
            "(?i)\\b(approve|approval|confirm|confirmation|shall i proceed|do you want me to proceed|proceed\\?)\\b");
    private static final Pattern SESSION_NOT_FOUND_PATTERN = Pattern.compile("Session not found:\\s*[^\\s`]+");
    private static final Pattern SESSION_ID_ASSIGNMENT_PATTERN =
            Pattern.compile("(?i)\\bsessionId\\s*[:=]\\s*([\\w\\-]+|<[^>]+>)\\b");
    private static final Pattern SESSION_ID_REQUEST_PATTERN =
            Pattern.compile("(?i)\\bprovide\\s+(a\\s+)?session\\s*id\\b|\\buse\\s+new\\s+sessionId\\b");

    private final AtomicInteger droppedMalformedSseChunks = new AtomicInteger();

    // ─── Stream chunking ─────────────────────────────────────────────────────

    /**
     * Appends one token or text fragment from the LLM to the in-progress buffer,
     * invoking {@code messageConsumer} with one or more text chunks whenever a
     * readable boundary is found.
     *
     * <p>Readable boundaries are detected in this priority order:
     * <ol>
     *   <li>A {@code '\n'} character.</li>
     *   <li>A sentence-ending {@code '.'}, {@code '!'}, or {@code '?'} that is
     *       not part of an abbreviation or a Markdown table row.</li>
     *   <li>A word boundary closest to the 240-character overflow threshold when
     *       no punctuation boundary has been found yet.</li>
     * </ol>
     *
     * <p>Empty or {@code null} pieces are silently ignored. Normalized chunks that
     * are blank after sanitization are not passed to the consumer.
     *
     * @param accumulator     the {@link StringBuilder} that accumulates the full
     *                        response text for post-processing (e.g., follow-up
     *                        question extraction); never {@code null}
     * @param streamBuffer    the {@link StringBuilder} holding unflushed content
     *                        awaiting the next boundary; never {@code null}
     * @param piece           the new text fragment from the LLM; {@code null} or empty
     *                        is a no-op
     * @param messageConsumer callback invoked with each emitted text chunk; never
     *                        {@code null}
     */
    public void appendReadableStreamChunk(
            final StringBuilder accumulator,
            final StringBuilder streamBuffer,
            final String piece,
            final Consumer<String> messageConsumer) {
        if (piece == null || piece.isEmpty()) {
            return;
        }
        accumulator.append(piece);
        streamBuffer.append(piece);
        int boundary = findReadableBoundary(streamBuffer);
        while (boundary >= 0) {
            String chunk = streamBuffer.substring(0, boundary + 1);
            String normalizedChunk = normalizeSseMessageChunk(chunk);
            if (!normalizedChunk.isBlank()) {
                messageConsumer.accept(normalizedChunk);
            }
            streamBuffer.delete(0, boundary + 1);
            boundary = findReadableBoundary(streamBuffer);
        }
    }

    /**
     * Scans {@code buffer} and returns the index of the next character that
     * constitutes a readable chunk boundary.
     *
     * <p>Boundary detection skips sentence-ending dots that appear to be part of
     * an abbreviation (e.g., {@code "U.S.A"}) and dots that appear inside Markdown
     * table rows (lines containing two or more {@code '|'} characters).
     *
     * <p>When no punctuation boundary is found and the buffer exceeds the 240-character
     * threshold, the method returns the last whitespace index before the threshold,
     * or the threshold index itself if no whitespace is present.
     *
     * @param buffer the text buffer to scan; must not be {@code null}
     * @return the zero-based index of the boundary character, or {@code -1} if no
     *         boundary has been reached yet
     */
    public int findReadableBoundary(final StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < buffer.length(); i++) {
            char c = buffer.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (c == '.' || c == '!' || c == '?') {
                if (i == buffer.length() - 1) {
                    return -1;
                }
                boolean shouldSkipBoundary =
                        isLikelyMarkdownTableLine(buffer, i) || (c == '.' && isLikelyAbbreviationBoundary(buffer, i));
                if (!shouldSkipBoundary) {
                    return i;
                }
            }
        }
        if (buffer.length() >= STREAM_EMIT_BUFFER_THRESHOLD) {
            for (int i = buffer.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(buffer.charAt(i))) {
                    return i;
                }
            }
            return STREAM_EMIT_BUFFER_THRESHOLD - 1;
        }
        return -1;
    }

    private boolean isLikelyMarkdownTableLine(final StringBuilder buffer, final int cursorIndex) {
        int lineStart = cursorIndex;
        while (lineStart > 0 && buffer.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int lineEnd = cursorIndex;
        while (lineEnd < buffer.length() && buffer.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        if (lineEnd <= lineStart) {
            return false;
        }
        String line = buffer.substring(lineStart, lineEnd);
        long pipeCount = line.chars().filter(ch -> ch == '|').count();
        return pipeCount >= MARKDOWN_TABLE_MIN_PIPE_COUNT;
    }

    private boolean isLikelyAbbreviationBoundary(final StringBuilder buffer, final int dotIndex) {
        boolean singleLetterPair = dotIndex > 0
                && dotIndex + 1 < buffer.length()
                && Character.isLetter(buffer.charAt(dotIndex - 1))
                && Character.isLetter(buffer.charAt(dotIndex + 1));
        if (singleLetterPair) {
            return true;
        }
        return dotIndex > ABBREVIATION_MIN_DOT_INDEX
                && buffer.charAt(dotIndex - ABBREVIATION_DOUBLE_DOT_OFFSET) == '.'
                && Character.isLetter(buffer.charAt(dotIndex - ABBREVIATION_PREVIOUS_CHAR_OFFSET))
                && Character.isLetter(buffer.charAt(dotIndex - 1));
    }

    /**
     * Drains all remaining content from {@code buffer} and clears it.
     *
     * <p>Call this after the LLM stream completes to emit the final trailing
     * fragment that did not reach a readable boundary on its own.
     *
     * @param buffer the stream buffer to drain; must not be {@code null}
     * @return the remaining content as a string; empty string when the buffer
     *         was already empty
     */
    public String flushStreamBuffer(final StringBuilder buffer) {
        if (buffer.isEmpty()) {
            return "";
        }
        String trailing = buffer.toString();
        buffer.setLength(0);
        return trailing;
    }

    // ─── Chunk normalization & sanitization ──────────────────────────────────

    /**
     * Normalizes a raw SSE chunk value, dropping it when it is empty, is a raw
     * SSE protocol line ({@code data:}, {@code event:}, {@code :keep-alive}), or
     * is a malformed protocol artefact.
     *
     * <p>Non-dropped chunks are passed through {@link #sanitizeAssistantResponseForUi}
     * before being returned. Chunks that become blank after sanitization (e.g.,
     * lone {@code **} or backtick-only fragments) are also dropped.
     *
     * @param value the raw text fragment from the LLM stream; may be {@code null}
     * @return the sanitized, UI-safe content; empty string when the chunk is
     *         dropped
     */
    public String normalizeSseMessageChunk(final String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = normalized.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("data:") || trimmed.startsWith("event:") || trimmed.startsWith(":keep-alive")) {
            recordDroppedMalformedSseChunk(trimmed);
            return "";
        }
        if (isMalformedSseFragment(trimmed)) {
            recordDroppedMalformedSseChunk(trimmed);
            return "";
        }
        String sanitized = sanitizeAssistantResponseForUi(normalized);
        String sanitizedTrimmed = sanitized.trim();
        if ("**".equals(sanitizedTrimmed) || "__".equals(sanitizedTrimmed)) {
            return "";
        }
        if (!sanitizedTrimmed.isEmpty() && sanitizedTrimmed.replace("`", "").isEmpty()) {
            return "";
        }
        if (sanitizedTrimmed.matches("^```[A-Za-z0-9_-]*$")) {
            return "";
        }
        return sanitized;
    }

    private void recordDroppedMalformedSseChunk(final String sample) {
        int current = droppedMalformedSseChunks.incrementAndGet();
        if (log.isDebugEnabled() && current % MALFORMED_SSE_LOG_EVERY_N == 0) {
            String truncated = sample != null && sample.length() > TRUNCATION_LENGTH
                    ? sample.substring(0, TRUNCATION_LENGTH) + "…"
                    : sample;
            log.debug("Dropped malformed SSE chunks count={} latestSample='{}'", current, truncated);
        }
    }

    /**
     * Returns {@code true} when {@code value} appears to be a raw SSE protocol
     * artefact rather than assistant content that should be displayed.
     *
     * <p>Drops: {@code null}, blank strings, single-character punctuation
     * ({@code )}, {@code (}, {@code ,}, {@code ;}), and fragments starting with
     * {@code data:} or {@code event:}.
     *
     * @param value the text fragment to classify; may be {@code null}
     * @return {@code true} when the fragment is a protocol artefact and must be
     *         discarded
     */
    public boolean isMalformedSseFragment(final String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (value.length() <= MALFORMED_SSE_SHORT_FRAGMENT_LENGTH) {
            return ")".equals(value) || "(".equals(value) || ",".equals(value) || ";".equals(value);
        }
        return value.startsWith("data:") || value.startsWith("event:");
    }

    /**
     * Masks session-management phrases that the LLM should never surface to end
     * users.
     *
     * <p>Replaces three categories of text:
     * <ol>
     *   <li>"Session not found: &lt;id&gt;" phrases with a generic internal-error
     *       message.</li>
     *   <li>Explicit {@code sessionId} assignments (e.g., {@code sessionId=abc123})
     *       with {@code sessionId=<managed-by-backend>}.</li>
     *   <li>Prompts asking the user to provide a session ID with
     *       {@code "session is managed by backend"}.</li>
     * </ol>
     *
     * @param text the raw assistant response text; may be {@code null}
     * @return the sanitized text; empty string when {@code text} is {@code null};
     *         original text unchanged when no patterns match
     */
    public String sanitizeAssistantResponseForUi(final String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String sanitized =
                SESSION_NOT_FOUND_PATTERN.matcher(text).replaceAll("Session binding issue detected internally.");
        sanitized = SESSION_ID_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("sessionId=<managed-by-backend>");
        sanitized = SESSION_ID_REQUEST_PATTERN.matcher(sanitized).replaceAll("session is managed by backend");
        return sanitized;
    }

    /**
     * Splits a complete assistant response string into UI-friendly chunks using
     * the same boundary-detection logic as the streaming path.
     *
     * <p>Use this when a full response string is available up-front and must be
     * replayed over an SSE stream (e.g., when serving a cached or reconstructed
     * response).
     *
     * @param response the full assistant response text; {@code null} or blank
     *                 returns an empty list
     * @return an ordered list of normalized, non-blank text chunks; never
     *         {@code null}
     */
    public List<String> chunkAssistantForUi(final String response) {
        List<String> chunks = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return chunks;
        }
        StringBuilder buffer = new StringBuilder(response);
        int boundary = findReadableBoundary(buffer);
        while (boundary >= 0) {
            String chunk = normalizeSseMessageChunk(buffer.substring(0, boundary + 1));
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            buffer.delete(0, boundary + 1);
            boundary = findReadableBoundary(buffer);
        }
        String trailing = normalizeSseMessageChunk(buffer.toString());
        if (!trailing.isBlank()) {
            chunks.add(trailing);
        }
        return chunks;
    }

    // ─── Confirmation detection ──────────────────────────────────────────────

    /**
     * Returns {@code true} when the completed assistant response contains language
     * that requires explicit user confirmation before proceeding.
     *
     * <p>The detector matches phrases such as "approve", "confirm", "shall I proceed",
     * and "do you want me to proceed" (case-insensitive). Callers should emit a
     * {@code confirmation_required} event when this method returns {@code true}.
     *
     * @param assistantResponse the full assistant response text; {@code null} or
     *                          blank returns {@code false}
     * @return {@code true} when the response contains confirmation-request language
     */
    public boolean requiresConfirmation(final String assistantResponse) {
        return assistantResponse != null && !assistantResponse.isBlank()
                && CONFIRMATION_REQUIRED_PATTERN.matcher(assistantResponse).find();
    }

    // ─── Error detail extraction ─────────────────────────────────────────────

    /**
     * Builds a detailed error string from a throwable, including the HTTP response
     * body for {@link HttpStatusCodeException}s (e.g. from {@code RestClient} A2A calls)
     * and the root-cause class and message for wrapped exceptions.
     *
     * @param throwable the exception to describe; must not be {@code null}
     * @return a human-readable error string including root-cause information where
     *         available
     */
    public String extractErrorDetail(final Throwable throwable) {
        if (throwable instanceof HttpStatusCodeException httpEx) {
            String body = httpEx.getResponseBodyAsString();
            if (!body.isBlank()) {
                return httpEx.getMessage() + " | body=" + body;
            }
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            Throwable cause = root.getCause();
            if (Objects.equals(cause, root)) {
                break;
            }
            root = cause;
        }
        String topMessage = throwable.getMessage();
        String rootMessage = root.getMessage();
        if (rootMessage != null && !rootMessage.isBlank() && !Objects.equals(rootMessage, topMessage)) {
            return topMessage + " | rootCause=" + root.getClass().getSimpleName() + ": " + rootMessage;
        }
        return topMessage;
    }
}
