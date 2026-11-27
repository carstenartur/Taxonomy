package com.taxonomy.acceptance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class LocalEmbeddingComparisonSupport {
    private LocalEmbeddingComparisonSupport() { }

    record Measurement(List<String> predicted, Map<String, Integer> targetRanks, List<String> missing,
                       Double recall, Double reciprocalRank, List<String> unexpectedTargets) { }
    private record Hit(String code, double cosine) { }

    /** Both vectors were unit-normalized during pooling, so their dot product is cosine. */
    static List<String> rank(float[] query, Map<String, float[]> vectors) {
        return vectors.entrySet().stream()
                .map(entry -> new Hit(entry.getKey(), dot(query, entry.getValue())))
                .sorted(Comparator.comparingDouble(Hit::cosine).reversed().thenComparing(Hit::code))
                .map(Hit::code).toList();
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Embedding dimensions disagree");
        double score = 0;
        for (int i = 0; i < a.length; i++) score += (double) a[i] * b[i];
        return score;
    }

    static float[] pool(float[][] states, long[] mask, int dimensions) {
        if (states.length != mask.length) throw new IllegalArgumentException("Hidden state and mask lengths disagree");
        double[] sums = new double[dimensions];
        int tokens = 0;
        for (int token = 0; token < mask.length; token++) {
            if (states[token].length != dimensions) throw new IllegalArgumentException("Unexpected hidden state width");
            if (mask[token] == 0) continue;
            if (mask[token] != 1) throw new IllegalArgumentException("Non-binary attention mask");
            tokens++;
            for (int i = 0; i < dimensions; i++) sums[i] += states[token][i];
        }
        if (tokens == 0) throw new IllegalArgumentException("No tokens to pool");
        double normSquared = 0;
        for (int i = 0; i < dimensions; i++) {
            sums[i] /= tokens;
            normSquared += sums[i] * sums[i];
        }
        if (!(normSquared > 0) || !Double.isFinite(normSquared)) {
            throw new IllegalArgumentException("Nonfinite or zero embedding norm");
        }
        double norm = Math.sqrt(normSquared);
        float[] normalized = new float[dimensions];
        for (int i = 0; i < dimensions; i++) normalized[i] = (float) (sums[i] / norm);
        return normalized;
    }

    static Map<String, String> verifyFiles(Path root, Map<String, String> expected) throws IOException {
        var verified = new TreeMap<String, String>();
        for (var entry : expected.entrySet()) {
            Path asset = root.resolve(entry.getKey());
            if (!Files.isRegularFile(asset)) throw new IOException("Missing model asset: " + asset);
            var digest = sha256();
            try (var input = Files.newInputStream(asset)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!entry.getValue().equals(actual)) throw new IOException("SHA-256 mismatch for model asset: " + asset);
            verified.put(entry.getKey(), actual);
        }
        return verified;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support SHA-256", e);
        }
    }

    static Measurement measure(OnnxMultilingualReferenceEvaluation.Case test, List<String> ranking, int topK) {
        var ranks = new TreeMap<String, Integer>();
        var observed = test.required().isEmpty() ? test.excluded() : test.required();
        for (String target : observed) {
            int rank = ranking.indexOf(target) + 1;
            if (rank == 0) throw new IllegalArgumentException("Reference code absent from complete ranking: " + target);
            ranks.put(target, rank);
        }
        if (test.required().isEmpty()) {
            List<String> predicted = List.copyOf(ranking.subList(0, Math.min(topK, ranking.size())));
            return new Measurement(predicted, ranks, List.of(), null, null,
                    predicted.stream().filter(test.excluded()::contains).toList());
        }
        var positive = OnnxReferenceCases.measure(test.required(), ranking, topK);
        double reciprocalRank = positive.firstRelevantRank() == null ? 0 : 1.0 / positive.firstRelevantRank();
        return new Measurement(positive.predicted(), ranks, positive.missing(), positive.recall(), reciprocalRank, List.of());
    }
}
