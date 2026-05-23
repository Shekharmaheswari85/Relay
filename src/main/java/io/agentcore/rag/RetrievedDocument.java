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
package io.agentcore.rag;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import lombok.Builder;
import lombok.Getter;

/**
 * Immutable value object that carries a single document retrieved from a backing store
 * during a RAG lookup.
 *
 * <p>Each document has a mandatory identifier and content string, an optional relevance
 * score, and an open-ended metadata map for source attribution, URLs, section headings,
 * or any other properties the retriever wishes to propagate.
 *
 * <p>{@link RagAdvisor} filters documents by their {@link #score} against a
 * configurable threshold and formats survivors for injection into the LLM system prompt
 * via {@link #toPromptFragment()}.
 *
 * <h3>Creating a document — minimal</h3>
 * <pre>{@code
 * RetrievedDocument doc = RetrievedDocument.of("doc-1", "The restart policy determines...", 0.92);
 * }</pre>
 *
 * <h3>Creating a document — with metadata</h3>
 * <pre>{@code
 * RetrievedDocument doc = RetrievedDocument.builder()
 *         .id("doc-42")
 *         .content("Kubernetes restart policy...")
 *         .score(0.88)
 *         .metadata(Map.of("title", "Restart Policy", "source", "k8s-docs",
 *                          "url", "https://kubernetes.io/docs/..."))
 *         .build();
 * }</pre>
 *
 * @see AgentRetriever
 * @see RagAdvisor
 */
@Getter
@Builder
public class RetrievedDocument {

    /** Stable identifier for this document — for example a vector ID or database primary key. */
    private final String id;

    /** The raw text to inject verbatim into the LLM prompt as retrieved context. */
    private final String content;

    /**
     * Relevance score in the range [0.0, 1.0], where higher values indicate stronger
     * semantic similarity to the query. {@code null} when the retriever does not produce
     * scores; {@link RagAdvisor} treats a {@code null} score as always passing the
     * minimum-score threshold.
     */
    private final Double score;

    /**
     * Open-ended metadata map for source attribution and display. Common keys include
     * {@code "title"}, {@code "source"}, {@code "url"}, {@code "author"}, and
     * {@code "section"}. Never {@code null}; defaults to an empty map.
     */
    @Builder.Default
    private final Map<String, Object> metadata = new HashMap<>();

    public Map<String, Object> getMetadata() {
        return Map.copyOf(metadata);
    }

    /**
     * Creates a document with an identifier, content, and relevance score.
     *
     * @param id      the document identifier; may be any non-null string
     * @param content the document text to inject into the LLM prompt; may be {@code null}
     *                if the content will be populated later via the builder
     * @param score   the relevance score in [0.0, 1.0]; {@code null} means no score
     * @return a new {@link RetrievedDocument} with an empty metadata map
     */
    public static RetrievedDocument of(final String id, final String content, final Double score) {
        return RetrievedDocument.builder()
                .id(id)
                .content(content)
                .score(score)
                .build();
    }

    /**
     * Creates a document with an identifier and content but no relevance score.
     *
     * @param id      the document identifier
     * @param content the document text to inject into the LLM prompt; may be {@code null}
     * @return a new {@link RetrievedDocument} with {@code score = null} and empty metadata
     */
    public static RetrievedDocument of(final String id, final String content) {
        return of(id, content, null);
    }

    /**
     * Formats this document as a Markdown-style block suitable for injection into an
     * LLM system prompt.
     *
     * <p>If the metadata map contains a {@code "title"} key, the value is rendered as a
     * level-2 Markdown heading ({@code ## title}) followed by the content. If no title is
     * present but a {@code "source"} key exists, it is used as the heading instead. When
     * neither key is present the content is returned without a heading.
     *
     * <p>{@link RagAdvisor} calls this method on each document it injects; override in a
     * subclass if a different prompt format is required.
     *
     * @return a non-null string ready to be embedded in the LLM system prompt; may be
     *         empty if both {@code content} and metadata headings are {@code null}
     */
    public String toPromptFragment() {
        StringBuilder sb = new StringBuilder();
        Object source = metadata.get("source");
        Object title = metadata.get("title");
        if (title != null) {
            sb.append("## ").append(title).append("\n");
        } else if (source != null) {
            sb.append("## Source: ").append(source).append("\n");
        }
        sb.append(Objects.requireNonNullElse(content, ""));
        return sb.toString();
    }
}
