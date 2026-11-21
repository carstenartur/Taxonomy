package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.SnapshotEvidence;
import com.taxonomy.dsl.model.ArchitectureElement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.identity.StableIdentityHash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.taxonomy.composition.architecture.ArchitectureFocusSuggestionService.Evidence;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Ranks bounded hypotheses on concrete graph members using authored or retained evidence. */
final class ArchitectureFocusCandidates {
    static final int MAX_QUOTE = 512;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+");
    private static final Set<String> LEGACY_ORIGINS = Arrays.stream(NodeOrigin.values())
            .map(origin -> origin.name().toLowerCase(Locale.ROOT).replace('_', ' ')).collect(Collectors.toUnmodifiableSet());
    private static final Pattern LEGACY_PROPAGATION = Pattern.compile("(?:, LLM score [0-9.]+)?(?:, [0-9]+ hop\\(s\\))?");
    private static final Pattern LEGACY_IMPACT = Pattern.compile(", score [0-9.]+, impact [0-9.,]+(?:, depth [0-9]+)?");
    private static final Set<String> GENERIC = Set.of(
            "about", "after", "also", "before", "both", "each", "from", "have", "into", "only", "other", "same",
            "shall", "should", "that", "their", "these", "this", "through", "used", "uses", "using", "when", "where", "with",
            "auch", "beide", "dabei", "daher", "diese", "dieser", "dieses", "eine", "einer", "einem", "einen", "hier",
            "kann", "können", "muss", "müssen", "nach", "oder", "sich", "sind", "soll", "sollen", "über", "unter", "wenn", "wird", "werden",
            "analysis", "application", "architecture", "capability", "component", "context", "description", "element", "fixture",
            "function", "general", "included", "information", "model", "module", "platform", "process", "propagation", "reference", "requirement", "retained", "sample",
            "score", "selected", "service", "services", "system", "systems", "technical", "technology", "workflow",
            "allgemein", "analyse", "anforderung", "anwendung", "architektur", "beispiel", "beschreibung", "dienst", "dienste", "fähigkeit",
            "funktion", "komponente", "kontext", "modell", "modul", "plattform", "prozess", "referenz", "systeme", "technisch");

    private ArchitectureFocusCandidates() { }

    record Triple(String sourceId, String targetId, String relationType, List<Evidence> evidence,
                  List<String> sharedTerms, Scope scope) {
        String key() { return keyFor(sourceId, relationType, targetId); }

        String rationale(String language) {
            boolean german = "de".equals(language);
            if (scope != null) return scope.rationale(language);
            String terms = String.join(", ", sharedTerms);
            return german ? "Ungeprüfter Kandidat mit gemeinsamen Elementbegriffen („" + terms
                    + "“). Typ, Richtung und fachliche Eignung prüfen."
                    : "Unassessed candidate with shared element wording (" + terms + "). Review its type, direction and actual purpose.";
        }
    }

    record Scope(Necessity necessity, Direction direction, String assessedCondition, String candidateCondition,
                 String alternativeGroup, int otherScopes, int rejectedScopes) {
        String rationale(String language) {
            boolean german = "de".equals(language);
            StringBuilder text = new StringBuilder(german ? "Ungeprüfter Kandidat aus einem gespeicherten Anforderungskontext ("
                    : "Unassessed candidate from one retained requirement scope (").append(necessity).append(").");
            append(text, german ? " Bedingung des untersuchten Beitrags: " : " Assessed contribution condition: ", assessedCondition);
            append(text, german ? " Bedingung des Gegenbeitrags: " : " Counterpart condition: ", candidateCondition);
            append(text, german ? " Alternativengruppe: " : " Alternative group: ", alternativeGroup);
            if (otherScopes > 0) text.append(german ? " Weitere Kontexte bleiben im Analysebericht erhalten."
                    : " Additional scopes remain in the retained analysis report.");
            if (rejectedScopes > 0) text.append(german ? " Für diese Beziehung sind außerdem Ablehnungen gespeichert."
                    : " This relation also has retained rejected assessments.");
            return text.toString();
        }
    }

    private record Peer(ArchitectureElement element, boolean scoped, int overlap, List<String> sharedTerms, List<Evidence> evidence) { }
    private record Word(String field, String text, int end) {
        Evidence excerpt(String elementId) {
            int start = Math.max(0, end - MAX_QUOTE);
            int stop = Math.min(text.length(), start + MAX_QUOTE);
            if (Character.isLowSurrogate(text.charAt(start))) start++;
            if (Character.isHighSurrogate(text.charAt(stop - 1))) stop--;
            return new Evidence(elementId, field, text.substring(start, stop));
        }
    }
    private record ScopedSearch(Map<String, List<Edge>> edges, Set<String> coveredPeers, Map<String, List<Trace>> rejected,
                                Set<String> navigation, boolean originalVerified) { }

    static Set<String> displayDescriptions(CanonicalArchitectureModel model) {
        var members = new HashSet<String>();
        model.getViews().stream().filter(view -> view.getExtensions().containsKey("x-analysis-snapshot-id"))
                .forEach(view -> members.addAll(view.getIncludes()));
        return model.getElements().stream().filter(element -> hasDisplayDescription(element, members.contains(element.getId())))
                .map(ArchitectureElement::getId).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean hasDisplayDescription(ArchitectureElement element, boolean adoptedMember) {
        String description = element.getDescription();
        if (blank(description)) return false;
        String captured = element.getExtensions().get(SnapshotEvidence.GENERATED_DESCRIPTION_HASH);
        if (captured != null) return validUnicode(description) && captured.equals(StableIdentityHash.sha256(description));
        if (!adoptedMember) return false;
        return description.startsWith("Requirement-scoped contribution: ")
                || description.startsWith("Requirement-scoped contribution proposals: ")
                || description.startsWith("SOURCE_ONLY: quoted requirement contribution proposal; ")
                || description.startsWith("leaf-enrichment: top-scoring in ")
                || description.equals("provisional relation endpoint")
                || legacyScoringReason(element.getId(), description);
    }

    private static boolean legacyScoringReason(String id, String description) {
        int start = description.startsWith(id + ": ") ? id.length()
                : description.startsWith(id + " (") ? description.indexOf("): ", id.length()) + 1 : 0;
        if (start < id.length()) return false;
        String explanation = description.substring(start + 2);
        int comma = explanation.indexOf(',');
        String origin = comma < 0 ? explanation : explanation.substring(0, comma);
        String scores = comma < 0 ? "" : explanation.substring(comma);
        boolean generatedOrigin = LEGACY_ORIGINS.contains(origin) || "included via propagation".equals(origin);
        return generatedOrigin && LEGACY_PROPAGATION.matcher(scores).matches()
                || (generatedOrigin || origin.isEmpty()) && LEGACY_IMPACT.matcher(scores).matches();
    }

    static List<Triple> find(CanonicalArchitectureModel model, CatalogueDefinition catalogue, Set<String> excluded,
                             Set<String> displayDescriptions, ArchitectureElement focus, int limit,
                             RelationSearchReport report, String original) {
        var scoped = scopedSearch(report, original, focus.getId());
        if (navigation(focus, catalogue, scoped)) return List.of();
        var existing = new HashSet<String>();
        model.getRelations().forEach(r -> existing.add(keyFor(r.getSourceId(), r.getRelationType(), r.getTargetId())));
        var types = catalogue.relationDomains().keySet().stream().filter(t -> !"CONTAINS".equals(t) && !excluded.contains(t))
                .sorted(Comparator.comparing((String t) -> "RELATED_TO".equals(t)).thenComparing(t -> t)).toList();
        var focusTerms = terms(focus, displayDescriptions);
        var peers = new ArrayList<Peer>();
        for (var peer : model.getElements()) {
            if (focus.getId().equals(peer.getId()) || navigation(peer, catalogue, scoped)) continue;
            var group = scoped.coveredPeers().contains(peer.getId())
                    ? new Peer(peer, true, 0, List.of(), List.of())
                    : lexicalPeer(focus, peer, focusTerms, terms(peer, displayDescriptions));
            if (group != null) peers.add(group);
        }
        peers.sort(Comparator.comparing(Peer::scoped).reversed()
                .thenComparing(Comparator.comparingInt(Peer::overlap).reversed()).thenComparing(peer -> peer.element().getId()));
        var eligible = new ArrayList<List<Triple>>();
        for (var peer : peers) {
            var triples = peer.scoped() ? scopedTriples(focus, peer.element(), scoped, catalogue, existing, types, original)
                    : lexicalTriples(focus, peer, catalogue, existing, types);
            if (!triples.isEmpty()) eligible.add(triples);
            if (eligible.size() == limit) break;
        }

        // Reserve one slot per counterpart before spending any on additional relation types.
        var result = new ArrayList<Triple>();
        for (int round = 0; result.size() < limit; round++) {
            int before = result.size();
            for (var triples : eligible) {
                if (triples.size() > round) result.add(triples.get(round));
                if (result.size() == limit) break;
            }
            if (result.size() == before) break;
        }
        return List.copyOf(result);
    }

    private static boolean navigation(ArchitectureElement element, CatalogueDefinition catalogue, ScopedSearch scoped) {
        return catalogue.rootCodes().contains(element.getId()) || scoped.navigation().contains(element.getId());
    }

    private static Peer lexicalPeer(ArchitectureElement focus, ArchitectureElement peer, Map<String, Word> focusTerms,
                                    Map<String, Word> peerTerms) {
        var overlap = focusTerms.keySet().stream().filter(peerTerms::containsKey).sorted().toList();
        if (overlap.isEmpty()) return null;
        var shared = overlap.subList(0, Math.min(3, overlap.size()));
        String term = shared.getFirst();
        return new Peer(peer, false, overlap.size(), shared,
                List.of(focusTerms.get(term).excerpt(focus.getId()), peerTerms.get(term).excerpt(peer.getId())));
    }

    private static List<Triple> lexicalTriples(ArchitectureElement focus, Peer candidate, CatalogueDefinition catalogue,
                                               Set<String> existing, List<String> types) {
        var peer = candidate.element();
        var triples = new ArrayList<Triple>();
        for (String type : types) {
            if (eligible(focus, peer, type, existing, catalogue))
                triples.add(new Triple(focus.getId(), peer.getId(), type, candidate.evidence(), candidate.sharedTerms(), null));
            if (eligible(peer, focus, type, existing, catalogue))
                triples.add(new Triple(peer.getId(), focus.getId(), type, candidate.evidence().reversed(), candidate.sharedTerms(), null));
        }
        return triples;
    }

    private static ScopedSearch scopedSearch(RelationSearchReport report, String original, String focus) {
        var byPeer = new HashMap<String, List<Edge>>();
        var covered = new HashSet<String>();
        var rejected = new HashMap<String, List<Trace>>();
        var navigation = new HashSet<String>();
        if (report != null) {
            report.sources().stream().map(SourceAssessment::node).filter(node -> node != null && node.container())
                    .forEach(node -> navigation.add(node.id()));
            for (Edge edge : report.result().edges()) {
                if (edge.contribution() == null || edge.target() == null || edge.direction() == null) continue;
                if (edge.contribution().source().container()) navigation.add(edge.contribution().source().id());
                if (edge.target().container()) navigation.add(edge.target().id());
                String peer = counterpart(focus, edge.sourceId(), edge.targetId());
                if (peer == null) continue;
                covered.add(peer);
                byPeer.computeIfAbsent(peer, ignored -> new ArrayList<>()).add(edge);
            }
            for (Trace trace : report.result().trace()) {
                if ((trace.phase() != Phase.VERIFY && trace.outcome() != Outcome.REJECT)
                        || trace.direction() == null || trace.type() == null) continue;
                String peer = counterpart(focus, trace.sourceId(), trace.targetId());
                if (peer == null) continue;
                covered.add(peer);
                if (trace.outcome() == Outcome.REJECT) {
                    boolean outgoing = trace.direction() == Direction.OUTGOING;
                    String key = keyFor(outgoing ? trace.sourceId() : trace.targetId(), trace.type(),
                            outgoing ? trace.targetId() : trace.sourceId());
                    rejected.computeIfAbsent(key, ignored -> new ArrayList<>()).add(trace);
                }
            }
            for (Unfinished work : report.result().unfinished()) {
                for (String target : work.targets()) {
                    String peer = counterpart(focus, work.sourceId(), target);
                    if (peer != null) covered.add(peer);
                }
            }
        }
        boolean verified = report != null && original != null && !original.isBlank() && validUnicode(original)
                && StableIdentityHash.sha256(original).equals(report.originalSha256());
        return new ScopedSearch(byPeer, covered, rejected, navigation, verified);
    }

    private static List<Triple> scopedTriples(ArchitectureElement focus, ArchitectureElement peer, ScopedSearch search,
                                              CatalogueDefinition catalogue, Set<String> existing, List<String> types, String original) {
        if (!search.originalVerified()) return List.of();
        var witnesses = new LinkedHashMap<String, List<Edge>>();
        for (Edge edge : search.edges().getOrDefault(peer.getId(), List.of())) {
            var decision = edge.evidence();
            ArchitectureElement from = focus.getId().equals(edge.sourceId()) ? focus : peer;
            ArchitectureElement to = from == focus ? peer : focus;
            String fromRoot = catalogue.rootFor(from.getType());
            String toRoot = catalogue.rootFor(to.getType());
            boolean outgoing = edge.direction() == Direction.OUTGOING;
            if (decision == null || !types.contains(edge.type()) || !eligible(from, to, edge.type(), existing, catalogue)
                    || !validWitness(edge, outgoing ? fromRoot : toRoot, outgoing ? toRoot : fromRoot, original)) continue;
            String key = keyFor(from.getId(), edge.type(), to.getId());
            var rejected = search.rejected().getOrDefault(key, List.of());
            if (!rejected.isEmpty()) {
                String contributionKey = ArchitectureFocusSuggestionService.retainedContributionKey(edge);
                if (rejected.stream().anyMatch(trace -> contributionKey.equals(trace.contributionKey()))) continue;
            }
            witnesses.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edge);
        }
        var triples = new ArrayList<Triple>();
        witnesses.forEach((key, scopes) -> {
            // A scoped alternative or optional witness must not disappear behind an unqualified relation.
            var edge = scopes.stream().min(Comparator.comparingInt(ArchitectureFocusCandidates::scopeOrder)
                    .thenComparing(e -> e.contribution().quote()).thenComparing(e -> e.evidence().quote())).orElseThrow();
            var assessed = edge.contribution();
            var decision = edge.evidence();
            var scope = new Scope(decision.necessity(), edge.direction(), assessed.condition(), decision.condition(),
                    decision.alternativeGroup(), scopes.size() - 1, search.rejected().getOrDefault(key, List.of()).size());
            triples.add(new Triple(edge.sourceId(), edge.targetId(), edge.type(),
                    List.of(new Evidence(assessed.source().id(), "requirement", assessed.quote()),
                            new Evidence(edge.target().id(), "requirement", decision.quote())), List.of(), scope));
        });
        triples.sort(Comparator.comparing(Triple::key));
        return triples;
    }

    private static boolean validWitness(Edge edge, String assessedRoot, String targetRoot, String original) {
        var assessed = edge.contribution();
        var decision = edge.evidence();
        return !assessed.source().container() && !edge.target().container() && assessed.source().root().equals(assessedRoot)
                && edge.target().root().equals(targetRoot) && edge.target().id().equals(decision.targetId())
                && decision.outcome() == Outcome.VERIFIED && decision.necessity() != null && blank(decision.question())
                && decision.contribution() != null && !decision.contribution().isBlank()
                && exactQuote(assessed.quote(), original) && exactQuote(decision.quote(), original)
                && boundedQualifier(assessed.condition()) && boundedQualifier(decision.condition()) && boundedQualifier(decision.alternativeGroup())
                && (decision.necessity() != Necessity.OPTIONAL || !blank(decision.condition()))
                && (decision.necessity() == Necessity.ALTERNATIVE ? !blank(decision.alternativeGroup()) : blank(decision.alternativeGroup()));
    }

    private static int scopeOrder(Edge edge) {
        return switch (edge.evidence().necessity()) {
            case ALTERNATIVE -> 0;
            case OPTIONAL -> 1;
            case REQUIRED -> blank(edge.contribution().condition()) && blank(edge.evidence().condition()) ? 3 : 2;
        };
    }

    private static boolean exactQuote(String quote, String original) {
        return !blank(quote) && quote.length() <= MAX_QUOTE && validUnicode(quote) && original.contains(quote);
    }

    private static boolean boundedQualifier(String value) {
        return value == null || value.length() <= MAX_QUOTE && validUnicode(value)
                && value.chars().noneMatch(c -> Character.isISOControl(c) || c == 0xfffe || c == 0xffff);
    }

    static boolean validUnicode(String value) {
        return value.codePoints().noneMatch(code -> code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE);
    }

    private static void append(StringBuilder result, String label, String value) {
        if (!blank(value)) result.append(label).append(value).append('.');
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String counterpart(String focus, String source, String target) {
        return focus.equals(source) ? target : focus.equals(target) ? source : null;
    }

    private static boolean eligible(ArchitectureElement from, ArchitectureElement to, String type, Set<String> existing,
                                    CatalogueDefinition catalogue) {
        return catalogue.allows(catalogue.rootFor(from.getType()), type, catalogue.rootFor(to.getType()))
                && !existing.contains(keyFor(from.getId(), type, to.getId()));
    }

    private static Map<String, Word> terms(ArchitectureElement element, Set<String> displayDescriptions) {
        var terms = new HashMap<String, Word>();
        if (!displayDescriptions.contains(element.getId())) addTerms(terms, "description", element.getDescription());
        addTerms(terms, "title", element.getTitle());
        return terms;
    }

    private static void addTerms(Map<String, Word> terms, String field, String value) {
        if (blank(value) || !validUnicode(value)) return;
        var matcher = WORD.matcher(value);
        while (matcher.find() && matcher.start() < 4_000) {
            if (matcher.end() - matcher.start() < 4 || matcher.end() - matcher.start() > 40
                    || !Character.isLetter(value.codePointAt(matcher.start()))) continue;
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (GENERIC.contains(term) || term.codePoints().anyMatch(Character::isDigit)) continue;
            terms.putIfAbsent(term, new Word(field, value, matcher.end()));
        }
    }

    private static String keyFor(String source, String type, String target) { return source + " " + type + " " + target; }
}
