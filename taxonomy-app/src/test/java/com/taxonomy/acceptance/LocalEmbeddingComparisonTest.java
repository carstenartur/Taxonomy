package com.taxonomy.acceptance;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.taxonomy.catalog.definition.BundledCatalogue;
import com.taxonomy.catalog.service.NodeEmbeddingText;
import com.taxonomy.catalog.service.TaxonomyService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.taxonomy.acceptance.LocalEmbeddingComparisonSupport.measure;
import static com.taxonomy.acceptance.LocalEmbeddingComparisonSupport.rank;
import static com.taxonomy.acceptance.LocalEmbeddingComparisonSupport.sha256;
import static com.taxonomy.acceptance.LocalEmbeddingComparisonSupport.verifyFiles;

/** Opt-in exact retrieval comparison with actual local ONNX graphs and an unchanged reference fixture. */
@EnabledIfSystemProperty(named = "taxonomy.embedding.comparison.enabled", matches = "true")
class LocalEmbeddingComparisonTest {
    private static final String MODELS_PROPERTY = "taxonomy.embedding.comparison.models";
    private static final String PINNED_ASSETS = "/local-embedding-comparison/model-assets.tsv";
    private static final Path REPORT = Path.of("target", "local-embedding-comparison", "report.json");
    private static final int MAX_TOKENS = 128;
    private static final int CPU_THREADS = 1;
    private static final int TOP_K = 10;
    private static final int WARM_REPETITIONS = 3;
    private static final int EXPECTED_DOCUMENTS = 2572;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Model> MODELS = List.of(
            new Model("multilingual-minilm-l12", "minilm", "model.onnx", 384, true, 128,
                    "Dynamic uint8 AVX2 production export"),
            new Model("jina-v2-base-de", "jina", "onnx/model_quantized.onnx", 768, false, 8192,
                    "First-party dynamic 8-bit ONNX, signed and unsigned initializers"),
            new Model("narratic-minilm-l6-eu", "narratic-onnx", "onnx/model_uint8.onnx", 384, true, 256,
                    "Community dynamic uint8 ONNX"));

    record Model(String id, String bundle, String graph, int dimensions, boolean tokenTypes,
                 int upstreamSequenceLimit, String quantization) {
        String graphPath() { return bundle + "/" + graph; }
        boolean owns(Asset asset) {
            return asset.path().startsWith(bundle + "/")
                    || (bundle.equals("narratic-onnx") && asset.path().startsWith("narratic-original/"));
        }
    }
    record Asset(String repository, String revision, String upstreamPath, String path, String sha256) { }
    record CaseResult(String id, String language, String kind, LocalEmbeddingComparisonSupport.Measurement measurement) { }
    record Metrics(int positiveQueries, long hits, double meanRecall, double meanReciprocalRank) { }
    record ModelResult(Model model, List<Asset> verifiedAssets, long graphBytes, int documentEncodes, int queryEncodes,
                       double sessionSetupMillis, double firstInferenceMillis, double documentPreparationMillis,
                       double warmQueryP50Millis, double warmQueryP95Millis, Integer cumulativeProcessPeakRssKiB,
                       Map<String, Metrics> positiveMetrics, Map<String, Metrics> paraphraseMetrics,
                       List<CaseResult> cases) { }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void comparesPinnedCpuModelsOnTheSameCatalogueAndQueries() throws Exception {
        Files.createDirectories(REPORT.getParent());
        var report = new LinkedHashMap<String, Object>();
        var results = new ArrayList<ModelResult>();
        report.put("schemaVersion", 1);
        report.put("status", "RUNNING");
        report.put("models", results);
        report.put("decisionRule", "Retain MiniLM unless a candidate preserves all baseline anchor hits, improves "
                + "paraphrase retrieval without a language regression, has acceptable resource cost and has broader independent judged evidence.");
        report.put("scope", "Exact node retrieval with globalReferenceText, using bundled bilingual workbook. "
                + "Positive references cover three office concepts. Broader independently judged evidence is unavailable in this run.");
        report.put("rssScope", "Cumulative high-water mark of the shared Surefire JVM, including earlier models and native memory.");
        report.put("topK", TOP_K);
        report.put("maxTokens", MAX_TOKENS);
        report.put("pooling", "attention-mask mean, L2 normalization");
        report.put("intraOpThreads", CPU_THREADS);
        report.put("interOpThreads", CPU_THREADS);
        report.put("warmRepetitions", WARM_REPETITIONS);
        report.put("runtimeDownloads", false);
        report.put("remoteInferenceCalls", 0);
        write(report);
        try {
            String configured = System.getProperty(MODELS_PROPERTY);
            if (configured == null || configured.isBlank()) {
                throw new IOException("Set -D" + MODELS_PROPERTY + " to the model bundles directory");
            }
            Path bundles = Path.of(configured).toAbsolutePath();
            byte[] manifest = resource(PINNED_ASSETS);
            var assets = assets(manifest);
            var pins = new TreeMap<String, String>();
            for (var asset : assets) {
                if (pins.putIfAbsent(asset.path(), asset.sha256()) != null) {
                    throw new IOException("Duplicate model asset pin: " + asset.path());
                }
            }
            verifyFiles(bundles, pins);
            byte[] workbook = resource("/" + BundledCatalogue.BILINGUAL_PATH);
            var documents = documents(workbook);
            var cases = OnnxMultilingualReferenceEvaluation.load();
            report.put("assetManifestSha256", hash(manifest));
            report.put("workbookSha256", hash(workbook));
            report.put("documentCount", documents.size());
            report.put("referenceSha256", hash(OnnxMultilingualReferenceEvaluation.resourceBytes()));
            report.put("originalAnchorSha256", hash(OnnxReferenceCases.resourceBytes()));
            report.put("caseCount", cases.size());
            var environment = OrtEnvironment.getEnvironment();
            report.put("platform", platform(environment));
            for (Model model : MODELS) {
                System.out.println("Comparing " + model.id() + " over " + documents.size() + " catalogue entries");
                results.add(compare(model, bundles, assets, documents, cases, environment));
                write(report);
            }
            verifyFiles(bundles, pins);
            report.put("candidateFindings", candidateFindings(results));
            report.put("recommendation", "RETAIN_PRODUCTION_DEFAULT");
            report.put("status", "MEASURED");
        } catch (Exception | LinkageError e) {
            report.put("status", "ERROR");
            report.put("failureType", e.getClass().getSimpleName());
            report.put("failureMessage", e.getMessage());
            throw e;
        } finally {
            write(report);
        }
    }

    private static ModelResult compare(Model model, Path bundles, List<Asset> assets, Map<String, String> documents,
            List<OnnxMultilingualReferenceEvaluation.Case> cases, OrtEnvironment environment) throws Exception {
        Path graph = bundles.resolve(model.graphPath());
        long start = System.nanoTime();
        try (var options = new OrtSession.SessionOptions();
             var tokenizer = LocalOnnxComparisonEncoder.tokenizer(bundles.resolve(model.bundle()), MAX_TOKENS)) {
            options.setIntraOpNumThreads(CPU_THREADS);
            options.setInterOpNumThreads(CPU_THREADS);
            options.addCPU(true);
            try (var session = environment.createSession(graph.toString(), options)) {
                var encoder = new LocalOnnxComparisonEncoder(environment, session, tokenizer,
                        model.dimensions(), MAX_TOKENS, model.tokenTypes());
                double setup = since(start);
                start = System.nanoTime();
                encoder.embed(cases.getFirst().query());
                double cold = since(start);
                var vectors = new LinkedHashMap<String, float[]>();
                start = System.nanoTime();
                for (var document : documents.entrySet()) vectors.put(document.getKey(), encoder.embed(document.getValue()));
                double documentPreparation = since(start);
                // Warm the exact scoring path once per query and retain the full relevance observations.
                var observations = new ArrayList<CaseResult>();
                for (var test : cases) {
                    var ranking = rank(encoder.embed(test.query()), vectors);
                    observations.add(new CaseResult(test.id(), test.language(), test.kind(), measure(test, ranking, TOP_K)));
                }
                var timings = new ArrayList<Double>();
                for (int iteration = 0; iteration < WARM_REPETITIONS; iteration++) {
                    for (int index = 0; index < cases.size(); index++) {
                        var test = cases.get(index);
                        start = System.nanoTime();
                        var ranking = rank(encoder.embed(test.query()), vectors);
                        timings.add(since(start));
                        if (!ranking.subList(0, TOP_K).equals(observations.get(index).measurement().predicted())) {
                            throw new IllegalStateException("Repeated query changed the top ten: " + test.id());
                        }
                    }
                }
                timings.sort(Double::compareTo);
                var paraphrases = observations.stream().filter(row -> row.kind().equals("PARAPHRASE")).toList();
                return new ModelResult(model, assets.stream().filter(model::owns).toList(), Files.size(graph),
                        vectors.size(), 1 + cases.size() + timings.size(), setup, cold, documentPreparation,
                        percentile(timings, .50), percentile(timings, .95), observedPeakRss(),
                        metrics(observations), metrics(paraphrases), observations);
            }
        }
    }

    private static Map<String, Metrics> metrics(List<CaseResult> cases) {
        var result = new LinkedHashMap<String, Metrics>();
        for (String language : List.of("all", "en", "de")) {
            var group = cases.stream().filter(row -> row.measurement().recall() != null)
                    .filter(row -> language.equals("all") || row.language().equals(language)).toList();
            result.put(language, new Metrics(group.size(),
                    group.stream().filter(row -> row.measurement().reciprocalRank() > 0).count(),
                    group.stream().mapToDouble(row -> row.measurement().recall()).average().orElseThrow(),
                    group.stream().mapToDouble(row -> row.measurement().reciprocalRank()).average().orElseThrow()));
        }
        return result;
    }

    private static List<Map<String, Object>> candidateFindings(List<ModelResult> results) {
        var baseline = results.getFirst();
        var baselineAnchors = baseline.cases().stream().filter(row -> row.kind().equals("ANCHOR"))
                .filter(row -> row.measurement().missing().isEmpty()).map(CaseResult::id).collect(Collectors.toSet());
        return results.stream().skip(1).map(candidate -> {
            var anchorRegressions = candidate.cases().stream().filter(row -> baselineAnchors.contains(row.id()))
                    .filter(row -> !row.measurement().missing().isEmpty()).map(CaseResult::id).toList();
            var paraphraseRegressions = List.of("en", "de").stream().filter(language -> {
                var original = baseline.paraphraseMetrics().get(language);
                var measured = candidate.paraphraseMetrics().get(language);
                return measured.hits() < original.hits() || measured.meanReciprocalRank() < original.meanReciprocalRank();
            }).toList();
            return Map.<String, Object>of("model", candidate.model().id(), "baselineAnchorRegressions", anchorRegressions,
                    "paraphraseLanguageRegressions", paraphraseRegressions, "paraphraseMrrImproved",
                    candidate.paraphraseMetrics().get("all").meanReciprocalRank()
                            > baseline.paraphraseMetrics().get("all").meanReciprocalRank());
        }).toList();
    }

    private static Map<String, String> documents(byte[] workbook) throws IOException {
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            var nodes = TaxonomyService.readWorkbookNodes(book).nodes();
            if (nodes.size() != EXPECTED_DOCUMENTS) {
                throw new IOException("Expected " + EXPECTED_DOCUMENTS + " catalogue entries, got " + nodes.size());
            }
            for (var anchor : OnnxReferenceCases.load()) {
                for (String code : anchor.required()) {
                    var node = nodes.get(code);
                    if (node == null || !anchor.expectedTitle().equals(node.getNameEn())) {
                        throw new IOException("Catalogue reference binding changed: " + code);
                    }
                }
            }
            var documents = new TreeMap<String, String>();
            nodes.forEach((code, node) -> documents.put(code, NodeEmbeddingText.globalReferenceText(node)));
            return documents;
        }
    }

    private static List<Asset> assets(byte[] manifest) {
        return new String(manifest, StandardCharsets.UTF_8).lines().filter(line -> !line.startsWith("#"))
                .map(line -> {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 5) throw new IllegalArgumentException("Malformed model asset manifest");
                    return new Asset(fields[0], fields[1], fields[2], fields[3], fields[4]);
                }).toList();
    }

    private static Map<String, Object> platform(OrtEnvironment environment) throws Exception {
        String tokenizerJar = Path.of(HuggingFaceTokenizer.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getFileName().toString();
        var platform = new LinkedHashMap<String, Object>();
        platform.put("java", System.getProperty("java.runtime.version"));
        platform.put("os", System.getProperty("os.name"));
        platform.put("osVersion", System.getProperty("os.version"));
        platform.put("arch", System.getProperty("os.arch"));
        platform.put("cpuModel", linuxField("/proc/cpuinfo", "model name"));
        platform.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        platform.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        platform.put("tokenizerLibrary", tokenizerJar);
        platform.put("onnxRuntime", environment.getVersion());
        platform.put("onnxProvider", "CPU");
        return platform;
    }

    private static Integer observedPeakRss() {
        String value = linuxField("/proc/self/status", "VmHWM");
        return value == null ? null : Integer.valueOf(value.split("\\s+")[0]);
    }

    private static String linuxField(String path, String key) {
        try (var lines = Files.lines(Path.of(path))) {
            return lines.filter(line -> line.contains(":"))
                    .map(line -> line.split(":", 2)).filter(parts -> parts[0].strip().equals(key))
                    .map(parts -> parts[1].strip()).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (var input = LocalEmbeddingComparisonTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing comparison resource: " + path);
            return input.readAllBytes();
        }
    }

    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static double since(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static double percentile(List<Double> sorted, double fraction) {
        return sorted.get((int) Math.ceil(sorted.size() * fraction) - 1);
    }
    private static void write(Map<String, Object> report) throws IOException {
        Files.writeString(REPORT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
