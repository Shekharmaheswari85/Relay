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
package io.agentcore.a2a;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Stateless parser for the Server-Sent Events (SSE) wire format.
 *
 * <p>Processes a line stream from an HTTP response body and dispatches each complete
 * SSE dispatch block to the caller-supplied {@link Consumer}. Follows the
 * <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">SSE Living Standard</a>:
 *
 * <ul>
 *   <li>An event block is terminated by a blank line.</li>
 *   <li>Multi-line {@code data:} payloads are concatenated with a newline ({@code \n}).</li>
 *   <li>The {@code event:} field defaults to {@code "message"} when not present in the block.</li>
 *   <li>Comment lines ({@code :}) and the {@code id:} / {@code retry:} fields are silently ignored.</li>
 * </ul>
 *
 * <p>The {@link #parse(Stream, Consumer)} method is synchronous — it consumes the entire
 * stream before returning. The calling thread (typically a JDK virtual thread) blocks for
 * the duration of the stream.
 *
 * @see SseEvent
 * @see AgentClient#sendMessage(String, String, Consumer)
 */
public final class SseEventParser {

    private SseEventParser() {
        throw new UnsupportedOperationException("Utility class — do not instantiate");
    }

    /**
     * Parses a line stream and fires {@code onEvent} once per complete SSE dispatch block.
     *
     * <p>A partial block remaining at stream end (i.e., no trailing blank line) is flushed
     * as a final event so that no data is silently dropped.
     *
     * @param lines   the line stream returned by
     *                {@link A2AHttpClient#sendForLines(java.net.http.HttpRequest)}
     * @param onEvent consumer called for each complete {@link SseEvent}; never {@code null}
     */
    public static void parse(final Stream<String> lines, final Consumer<SseEvent> onEvent) {
        final String[] currentEvent = {null};
        final StringBuilder currentData = new StringBuilder();

        lines.forEach(line -> {
            if (line.isBlank()) {
                flushEvent(currentEvent, currentData, onEvent);
            } else if (line.startsWith("event:")) {
                currentEvent[0] = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (!currentData.isEmpty()) {
                    currentData.append('\n');
                }
                currentData.append(line.substring("data:".length()).trim());
            }
        });

        flushEvent(currentEvent, currentData, onEvent);
    }

    private static void flushEvent(
            final String[] currentEvent,
            final StringBuilder currentData,
            final Consumer<SseEvent> onEvent) {
        if (!currentData.isEmpty() || currentEvent[0] != null) {
            onEvent.accept(new SseEvent(
                    currentEvent[0] != null ? currentEvent[0] : "message",
                    currentData.toString()));
            currentEvent[0] = null;
            currentData.setLength(0);
        }
    }
}
