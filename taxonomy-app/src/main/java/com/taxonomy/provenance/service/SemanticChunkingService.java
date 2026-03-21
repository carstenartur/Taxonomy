package com.taxonomy.provenance.service;

import com.taxonomy.shared.service.LocalEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Embedding-based semantic chunking as a fallback for documents with few or no
 * recognisable headings.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Split text into individual sentences.</li>
 *   <li>Form overlapping windows of {@code windowSize} sentences.</li>
 *   <li>Embed each window with {@link LocalEmbeddingService}.</li>
 *   <li>Compute cosine distance between consecutive windows.</li>
 *   <li>Peaks above a threshold indicate topic changes → split points.</li>
 *   <li>Build chunks from the split points.</li>
 * </ol>
 *
 * <p>Requires the local embedding service to be available (ONNX model loaded).
 */
@Service
public class SemanticChunkingService {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunkingService.class);

    private static final int DEFAULT_WINDOW_SIZE = 5;
    private static final double DEFAULT_THRESHOLD = 0.3;

    private final LocalEmbeddingService embeddingService;

    public SemanticChunkingService(LocalEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * Splits raw text into semantically coherent chunks.
     *
     * @param rawText    the raw text to chunk
     * @param windowSize number of sentences per sliding window (default 5)
     * @return list of chunk strings, or a single-element list containing the
     *         full text if embedding is unavailable or too few sentences exist
     */
    public List<String> chunk(String rawText, int windowSize) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        List<String> sentences = splitIntoSentences(rawText);

        if (sentences.size() < windowSize + 1 || !embeddingService.isAvailable()) {
            log.debug("Semantic chunking skipped (sentences={}, embedding={})",
                    sentences.size(), embeddingService.isAvailable());
            return List.of(rawText.strip());
        }

        try {
            return doSemanticChunking(sentences, windowSize);
        } catch (Exception e) {
            log.warn("Semantic chunking failed, returning full text as single chunk", e);
            return List.of(rawText.strip());
        }
    }

    /**
     * Convenience overload using the default window size.
     */
    public List<String> chunk(String rawText) {
        return chunk(rawText, DEFAULT_WINDOW_SIZE);
    }

    private List<String> doSemanticChunking(List<String> sentences, int windowSize) throws Exception {
        // Build sliding-window embeddings
        List<float[]> embeddings = new ArrayList<>();
        for (int i = 0; i <= sentences.size() - windowSize; i++) {
            String window = String.join(" ", sentences.subList(i, i + windowSize));
            embeddings.add(embeddingService.embed(window));
        }

        // Compute cosine distances between consecutive windows
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < embeddings.size() - 1; i++) {
            distances.add(1.0 - cosineSimilarity(embeddings.get(i), embeddings.get(i + 1)));
        }

        // Find split points where distance exceeds threshold
        List<Integer> splitPoints = findSplitPoints(distances, DEFAULT_THRESHOLD);

        // Build chunks from split points
        return buildChunksFromSplitPoints(sentences, splitPoints, windowSize);
    }

    /**
     * Finds indices where the cosine distance peaks above the threshold.
     */
    List<Integer> findSplitPoints(List<Double> distances, double threshold) {
        List<Integer> splits = new ArrayList<>();
        for (int i = 0; i < distances.size(); i++) {
            if (distances.get(i) > threshold) {
                splits.add(i);
            }
        }
        return splits;
    }

    /**
     * Reassembles sentence groups into chunks using the identified split points.
     */
    private List<String> buildChunksFromSplitPoints(List<String> sentences,
                                                     List<Integer> splitPoints,
                                                     int windowSize) {
        List<String> chunks = new ArrayList<>();
        int offset = windowSize / 2;  // centre of the window
        int start = 0;

        for (int splitIdx : splitPoints) {
            int splitSentence = splitIdx + offset;
            if (splitSentence > start && splitSentence < sentences.size()) {
                chunks.add(joinSentences(sentences, start, splitSentence));
                start = splitSentence;
            }
        }
        // Remaining sentences
        if (start < sentences.size()) {
            chunks.add(joinSentences(sentences, start, sentences.size()));
        }

        return chunks;
    }

    private static String joinSentences(List<String> sentences, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(sentences.get(i).strip());
        }
        return sb.toString();
    }

    /**
     * Splits text into sentences using a German-locale {@link BreakIterator}.
     */
    List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        BreakIterator iter = BreakIterator.getSentenceInstance(Locale.GERMAN);
        iter.setText(text);
        int start = iter.first();
        for (int end = iter.next(); end != BreakIterator.DONE; end = iter.next()) {
            String sentence = text.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            start = end;
        }
        return sentences;
    }

    /**
     * Computes the cosine similarity between two vectors.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0 : dotProduct / denominator;
    }
}
