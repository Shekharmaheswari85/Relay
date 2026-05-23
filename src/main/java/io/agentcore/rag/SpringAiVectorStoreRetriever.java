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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link AgentRetriever} implementation backed by any Spring AI {@link VectorStore}.
 *
 * <p>Drop-in RAG retrieval for PgVector, Weaviate, Pinecone, Chroma, Qdrant, or any other
 * vector database supported by Spring AI — zero custom code required. Simply supply the
 * auto-configured {@link VectorStore} bean:
 *
 * <pre>{@code
 * @Bean
 * public AgentRetriever agentRetriever(VectorStore vectorStore) {
 *     return SpringAiVectorStoreRetriever.builder(vectorStore)
 *             .topK(5)
 *             .similarityThreshold(0.72)
 *             .build();
 * }
 *
 * @Bean
 * public RagAdvisor ragAdvisor(AgentRetriever retriever) {
 *     return RagAdvisor.builder(retriever).minScore(0.72).build();
 * }
 * }</pre>
 *
 * <h3>Score extraction</h3>
 * <p>Relevance scores are extracted from the document metadata using the following keys in
 * priority order: {@code score}, {@code distance}, {@code similarity}. If none are present the
 * score is left {@code null} and {@link RagAdvisor}'s {@code minScore} filter is skipped for
 * that document.
 *
 * <h3>Thread safety</h3>
 * <p>Instances are stateless and safe for concurrent use by multiple request threads.
 *
 * @see AgentRetriever
 * @see RagAdvisor
 */
@Slf4j
public class SpringAiVectorStoreRetriever implements AgentRetriever {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.0;

    private static final String[] SCORE_METADATA_KEYS = {"score", "distance", "similarity"};

    private final VectorStore vectorStore;
    private final int topK;
    private final double similarityThreshold;

    /**
     * Creates a retriever with default settings ({@code topK=5}, no score threshold).
     *
     * @param vectorStore the Spring AI vector store; never null
     */
    public SpringAiVectorStoreRetriever(final VectorStore vectorStore) {
        this(vectorStore, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * Creates a retriever with explicit settings.
     *
     * @param vectorStore          the Spring AI vector store; never null
     * @param topK                 maximum number of documents to return per query; must be &gt;= 1
     * @param similarityThreshold  minimum similarity score in [0.0, 1.0]; pass {@code 0.0} to disable
     */
    public SpringAiVectorStoreRetriever(
            final VectorStore vectorStore,
            final int topK,
            final double similarityThreshold) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "VectorStore must not be null");
        this.topK = Math.max(1, topK);
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * Returns a builder for fluent configuration.
     *
     * @param vectorStore the backing vector store; never null
     */
    public static Builder builder(final VectorStore vectorStore) {
        return new Builder(vectorStore);
    }

    @Override
    public List<RetrievedDocument> retrieve(final String query, final Map<String, Object> context) {
        SearchRequest request = SearchRequest.builder()
                .query(Objects.requireNonNull(query, "Query must not be null"))
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        try {
            List<Document> results = vectorStore.similaritySearch(request);
            if (results == null) {
                return List.of();
            }
            log.debug("VectorStore search returned {} docs for query (len={})",
                    results.size(), query.length());
            return results.stream()
                    .map(this::toRetrievedDocument)
                    .toList();
        } catch (Exception ex) {
            log.warn("VectorStore search failed for query (len={}): {}", query.length(), ex.getMessage());
            return List.of();
        }
    }

    private RetrievedDocument toRetrievedDocument(final Document doc) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());

        Double score = extractScore(meta);

        return RetrievedDocument.builder()
                .id(doc.getId())
                .content(doc.getText())
                .score(score)
                .metadata(meta)
                .build();
    }

    private Double extractScore(final Map<String, Object> meta) {
        for (String key : SCORE_METADATA_KEYS) {
            Object val = meta.remove(key);
            if (val instanceof Number n) {
                return n.doubleValue();
            }
        }
        return null;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link SpringAiVectorStoreRetriever}.
     */
    public static final class Builder {

        private final VectorStore vectorStore;
        private int topK = DEFAULT_TOP_K;
        private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

        private Builder(final VectorStore vectorStore) {
            this.vectorStore = Objects.requireNonNull(vectorStore, "VectorStore must not be null");
        }

        /**
         * Maximum number of documents returned per query (default: 5).
         */
        public Builder topK(final int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Minimum similarity score in [0.0, 1.0]; documents below this threshold are excluded.
         * Default is {@code 0.0} (include all results).
         */
        public Builder similarityThreshold(final double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        /** Builds the retriever. */
        public SpringAiVectorStoreRetriever build() {
            return new SpringAiVectorStoreRetriever(vectorStore, topK, similarityThreshold);
        }
    }
}
