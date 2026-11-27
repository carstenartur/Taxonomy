package com.taxonomy.catalog.snapshot;

import com.taxonomy.dto.TaxonomyNodeDto;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.KStemFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Read-only, requirement-independent index of one captured catalogue revision. */
public final class CataloguePreviewIndex {
    public record Match(String code, String root, String parentCode, String nameEn, String nameDe, double score,
                        String analysisRole, boolean architectureCandidate, Integer sourceOrder) {}
    /** Undirected vocabulary overlap between two directly matched nodes, with catalogue title terms as evidence. */
    public record Association(String sourceCode, String targetCode, List<String> sharedTerms) {}
    public record Result(List<Match> matches, List<Match> ancestors, List<Association> associations) {}

    private static final int MATCHES_PER_ROOT = 12;
    private static final int MAX_ASSOCIATIONS = 24;
    private static final Comparator<Match> RANK = Comparator.comparingDouble(Match::score).reversed()
            .thenComparing(Match::code);
    private final Set<String> roots;
    private final Map<String, RootCatalogueSnapshot.Node> nodes = new HashMap<>();
    private final Set<String> navigationOnly;
    private final LanguageIndex english = new LanguageIndex();
    private final LanguageIndex german = new LanguageIndex();

    public CataloguePreviewIndex(List<RootCatalogueSnapshot> snapshots) {
        if (snapshots.isEmpty()) throw new IllegalArgumentException("Catalogue snapshots are required");
        roots = snapshots.stream().map(RootCatalogueSnapshot::rootCode).collect(Collectors.toUnmodifiableSet());
        new FrozenCatalogueView(snapshots.getFirst().source(), roots, snapshots);
        try (var en = new PreviewEnglishAnalyzer(); var de = new GermanAnalyzer()) {
            for (var snapshot : snapshots) {
                for (var node : snapshot.nodes()) {
                    nodes.put(node.code(), node);
                    english.add(node.code(), node.nameEn(), node.descriptionEn(), en);
                    german.add(node.code(), node.nameDe(), node.descriptionDe(), de);
                }
            }
        }
        navigationOnly = navigationOnly(nodes);
        english.indexTopics(nodes);
        german.indexTopics(nodes);
    }

    /**
     * Scores express weighted query-term coverage, not model probabilities. A term in a description
     * contributes 65% of its title weight. Rarer terms carry more weight, including unmatched terms
     * in the denominator. The better independently evaluated language wins. Path-only nodes have zero weight.
     */
    public Result query(String text, Set<String> selectedRoots) {
        Map<String, Double> scores = new HashMap<>();
        var terms = QueryTerms.from(text);
        english.score(terms.english(), scores);
        german.score(terms.german(), scores);
        return project(scores, selectedRoots, terms);
    }

    Result project(Map<String, Double> scores, Set<String> selectedRoots, String text) {
        return project(scores, selectedRoots, QueryTerms.from(text));
    }

    private Result project(Map<String, Double> scores, Set<String> selectedRoots, QueryTerms terms) {
        if (!roots.containsAll(selectedRoots)) throw new IllegalArgumentException("Unknown preview taxonomy root");
        Map<String, List<Match>> byRoot = new TreeMap<>();
        scores.forEach((code, score) -> {
            var node = nodes.get(code);
            if (selectedRoots.isEmpty() || selectedRoots.contains(node.taxonomyRoot())) {
                byRoot.computeIfAbsent(node.taxonomyRoot(), ignored -> new ArrayList<>()).add(match(node, score));
            }
        });
        var matches = byRoot.values().stream().flatMap(group -> group.stream().sorted(RANK).limit(MATCHES_PER_ROOT))
                .sorted(RANK).toList();
        var matched = matches.stream().map(Match::code).collect(Collectors.toSet());
        Set<String> ancestors = new HashSet<>();
        for (var hit : matches) {
            String parent = hit.parentCode();
            while (parent != null && ancestors.add(parent)) parent = nodes.get(parent).parentCode();
        }
        ancestors.removeAll(matched);
        var path = ancestors.stream().map(nodes::get).sorted(Comparator.comparingInt(RootCatalogueSnapshot.Node::level)
                .thenComparing(RootCatalogueSnapshot.Node::code)).map(node -> match(node, 0)).toList();
        return new Result(matches, path, associations(matches, matched, terms));
    }

    private List<Association> associations(List<Match> hits, Set<String> matched, QueryTerms terms) {
        Map<Pair, Set<String>> pairs = new HashMap<>();
        for (var hit : hits) {
            english.collect(hit, matched, terms.english(), nodes, pairs);
            german.collect(hit, matched, terms.german(), nodes, pairs);
        }
        // Prefer more shared terms, then the weaker endpoint's own requirement fit, then stable IDs.
        Map<String, Double> score = hits.stream().collect(Collectors.toMap(Match::code, Match::score));
        return pairs.entrySet().stream()
                .map(entry -> new Association(entry.getKey().first(), entry.getKey().second(), List.copyOf(entry.getValue())))
                .sorted(Comparator.<Association>comparingInt(pair -> pair.sharedTerms().size()).reversed()
                        .thenComparing(Comparator.comparingDouble((Association pair) ->
                                Math.min(score.get(pair.sourceCode()), score.get(pair.targetCode()))).reversed())
                        .thenComparing(Association::sourceCode).thenComparing(Association::targetCode))
                .limit(MAX_ASSOCIATIONS).toList();
    }

    private record Pair(String first, String second) {}
    private record Topic(String stem, Map<String, String> titleForms) {}
    /** Dictionary-based English stemming preserves distinctions such as "emergency" versus "emergent". */
    private static final class PreviewEnglishAnalyzer extends Analyzer {
        @Override
        protected TokenStreamComponents createComponents(String field) {
            var source = new StandardTokenizer();
            var words = new LowerCaseFilter(new EnglishPossessiveFilter(source));
            return new TokenStreamComponents(source, new KStemFilter(new StopFilter(words, EnglishAnalyzer.getDefaultStopSet())));
        }
    }

    private record QueryTerms(Set<String> english, Set<String> german) {
        static QueryTerms from(String text) {
            try (var en = new PreviewEnglishAnalyzer(); var de = new GermanAnalyzer()) {
                return new QueryTerms(LanguageIndex.terms(en, text).keySet(), LanguageIndex.terms(de, text).keySet());
            }
        }
    }

    // Assess wrapper identity once on the entire admitted hierarchy, before per-root top-k filtering.
    private static Set<String> navigationOnly(Map<String, RootCatalogueSnapshot.Node> recorded) {
        var tree = new HashMap<String, TaxonomyNodeDto>();
        for (var node : recorded.values()) {
            var dto = new TaxonomyNodeDto();
            dto.setCode(node.code());
            dto.setParentCode(node.parentCode());
            dto.setTaxonomyRoot(node.taxonomyRoot());
            dto.setLevel(node.level());
            dto.setAnalysisRole(node.analysisRole());
            tree.put(node.code(), dto);
        }
        for (var node : tree.values()) {
            var parent = tree.get(node.getParentCode());
            if (parent != null) parent.getChildren().add(node);
        }
        return tree.values().stream().filter(node -> node.architectureNavigationOnly(tree.get(node.getParentCode())))
                .map(TaxonomyNodeDto::getCode).collect(Collectors.toUnmodifiableSet());
    }

    private Match match(RootCatalogueSnapshot.Node node, double score) {
        return new Match(node.code(), node.taxonomyRoot(), node.parentCode(), node.nameEn(), node.nameDe(), score,
                node.analysisRole(), !navigationOnly.contains(node.code()), node.sortOrder());
    }

    private static final class LanguageIndex {
        private final Map<String, Map<String, Boolean>> postings = new HashMap<>();
        private final Map<String, Map<String, String>> titleForms = new HashMap<>();
        private final Map<String, List<Topic>> topics = new HashMap<>();
        private int documents;

        void add(String code, String name, String description, Analyzer analyzer) {
            var title = terms(analyzer, name);
            var body = terms(analyzer, description);
            if (title.isEmpty() && body.isEmpty()) return;
            documents++;
            for (String term : body.keySet()) postings.computeIfAbsent(term, key -> new HashMap<>()).put(code, false);
            title.forEach((term, form) -> {
                postings.computeIfAbsent(term, key -> new HashMap<>()).put(code, true);
                titleForms.computeIfAbsent(term, ignored -> new HashMap<>()).put(code, form);
            });
        }

        /** Store cross-root topic groups once; common headings would produce uninformative dense networks. */
        void indexTopics(Map<String, RootCatalogueSnapshot.Node> nodes) {
            // Never expand a title shared by more than 24 nodes; use a stricter relative cap for small catalogues.
            int maxMembers = Math.min(24, Math.max(2, documents / 10));
            titleForms.forEach((stem, forms) -> {
                if (forms.size() < 2 || forms.size() > maxMembers) return;
                if (forms.keySet().stream().map(code -> nodes.get(code).taxonomyRoot()).distinct().limit(2).count() < 2) return;
                var topic = new Topic(stem, Map.copyOf(forms));
                for (String code : forms.keySet()) topics.computeIfAbsent(code, ignored -> new ArrayList<>()).add(topic);
            });
            titleForms.clear();
        }

        void score(Set<String> terms, Map<String, Double> scores) {
            if (documents == 0) return;
            Map<String, Double> numerator = new HashMap<>();
            double denominator = 0;
            for (String term : terms) {
                var entries = postings.getOrDefault(term, Map.of());
                double weight = 1 + Math.log((documents + 1.0) / (entries.size() + 1.0));
                denominator += weight;
                entries.forEach((code, title) -> numerator.merge(code, title ? weight : 0.65 * weight, Double::sum));
            }
            double total = denominator;
            // Round only for display in callers. Preserve strict positive scores and deterministic ordering.
            numerator.forEach((code, value) -> scores.merge(code, Math.min(100, 100 * value / total), Math::max));
        }

        void collect(Match hit, Set<String> matched, Set<String> queryTerms, Map<String, RootCatalogueSnapshot.Node> nodes,
                     Map<Pair, Set<String>> pairs) {
            for (var topic : topics.getOrDefault(hit.code(), List.of())) {
                // Two on-topic endpoints can share a different, incidental subject. Require text evidence for the link itself.
                if (!queryTerms.contains(topic.stem())) continue;
                for (String peer : topic.titleForms().keySet()) {
                    if (hit.code().compareTo(peer) >= 0 || !matched.contains(peer)
                            || hit.root().equals(nodes.get(peer).taxonomyRoot())) continue;
                    String evidence = shorterForm(topic.titleForms().get(hit.code()), topic.titleForms().get(peer));
                    pairs.computeIfAbsent(new Pair(hit.code(), peer), ignored -> new TreeSet<>()).add(evidence);
                }
            }
        }

        private static String shorterForm(String a, String b) {
            if (a.length() != b.length()) return a.length() < b.length() ? a : b;
            return a.compareTo(b) < 0 ? a : b;
        }

        private static Map<String, String> terms(Analyzer analyzer, String text) {
            if (text == null || text.isBlank()) return Map.of();
            Map<String, String> terms = new LinkedHashMap<>();
            try (var stream = analyzer.tokenStream("text", text)) {
                var term = stream.addAttribute(CharTermAttribute.class);
                var offsets = stream.addAttribute(OffsetAttribute.class);
                stream.reset();
                while (stream.incrementToken()) {
                    var form = text.substring(offsets.startOffset(), offsets.endOffset()).toLowerCase(Locale.ROOT);
                    terms.putIfAbsent(term.toString(), form);
                }
                stream.end();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return terms;
        }
    }
}
