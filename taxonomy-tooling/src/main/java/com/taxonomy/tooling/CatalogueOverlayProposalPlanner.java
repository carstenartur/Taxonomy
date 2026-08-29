package com.taxonomy.tooling;

import com.taxonomy.tooling.CatalogueOverlayProposalModel.CandidateScore;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.FanOut;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.OverlayModel;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Patch;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.Proposal;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SemanticChange;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceCatalogue;
import com.taxonomy.tooling.CatalogueOverlayProposalModel.SourceNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Deterministic candidate ranking and proposal planning. */
final class CatalogueOverlayProposalPlanner {

    private static final int MINIMUM_SECONDARY_SCORE = 120;
    private static final Set<String> GENERIC_TOKENS = Set.of(
            "a", "about", "above", "after", "again", "against", "all", "also",
            "am", "an", "and", "any", "are", "as", "at", "be", "because",
            "been", "before", "being", "below", "between", "both", "but", "by",
            "can", "cannot", "contain", "constitute", "context", "could", "did",
            "do", "does", "doing", "down", "during", "each", "fact", "few",
            "for", "from", "further", "generic", "had", "has", "have", "having",
            "he", "her", "here", "hers", "herself", "him", "himself", "his",
            "how", "i", "if", "in", "include", "information", "into", "is", "it",
            "its", "itself", "joint", "just", "may", "me", "more", "most",
            "must", "my", "myself", "necessary", "no", "nor", "not", "now",
            "object", "of", "off", "on", "once", "only", "or", "other", "our", "ours",
            "ourselves", "out", "over", "own", "particular", "product", "products",
            "provide", "required", "same", "service", "services", "she", "should",
            "so", "some", "specific", "such", "support", "than", "that", "the",
            "their", "theirs", "them", "themselves", "then", "there", "these",
            "they", "this", "those", "through", "to", "too", "under", "until",
            "up", "very", "was", "we", "were", "what", "when", "where", "which",
            "while", "who", "whom", "why", "will", "with", "would", "you",
            "your", "yours", "yourself", "yourselves");
    private static final Set<String> BROAD_DOMAIN_TOKENS = Set.of(
            "command", "commander", "force", "military", "mission", "operation",
            "operational", "system", "unit");
    private static final Set<String> FORM_TOKENS = Set.of(
            "alert", "analysis", "assessment", "brief", "catalogue", "control",
            "despatch", "estimate", "list", "matrix", "message", "order", "plan",
            "record", "report", "request", "schedule", "status", "warning");

    private CatalogueOverlayProposalPlanner() {
    }

    static List<Proposal> buildProposals(
            OverlayModel overlay,
            SourceCatalogue source,
            Map<String, String> currentParents,
            Set<String> candidateFamilies) {
        Set<String> strictCodes = source.nodes().values().stream()
                .filter(node -> node.code().startsWith(overlay.strictRoot() + "-"))
                .filter(node -> overlay.strictState().equalsIgnoreCase(node.state()))
                .map(SourceNode::code)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        Map<String, List<String>> children = childrenByParent(currentParents);

        List<Proposal> proposals = new ArrayList<>();
        for (String code : strictCodes) {
            SourceNode node = source.nodes().get(code);
            Patch patch = overlay.patches().get(code);
            String role = patch != null
                    ? patch.analysisRole()
                    : children.containsKey(code)
                            ? CatalogueOverlayProposalGenerator.ROLE_PRODUCT_FAMILY
                            : CatalogueOverlayProposalGenerator.ROLE_PRODUCT;
            String currentParent = patch == null ? null : patch.parentCode();
            List<String> currentSecondary = patch == null
                    ? List.of() : patch.secondaryClassificationCodes();
            List<CandidateScore> ranked = rankCandidates(
                    node,
                    currentParent,
                    candidateFamilies,
                    currentParents,
                    source.nodes());

            String proposedParent;
            List<String> proposedSecondary;
            BigDecimal confidence;
            String status;
            String authority;
            String reason;
            boolean unresolved = false;

            if (patch != null && !patch.reviewRequired()) {
                proposedParent = patch.parentCode();
                proposedSecondary = patch.secondaryClassificationCodes();
                confidence = patch.confidence();
                status = "REVIEWED_LOCKED";
                authority = "REVIEWED_OVERLAY";
                reason = "Reviewed overlay mapping is authoritative; the generator did not change it.";
            } else {
                CandidateDecision decision = decideCandidate(ranked, currentParent, patch == null);
                proposedParent = decision.parentCode();
                confidence = decision.confidence();
                unresolved = decision.unresolved();
                proposedSecondary = unresolved && patch == null
                        ? List.of()
                        : secondarySuggestions(ranked, proposedParent, 2);
                authority = "AUTOMATED_PROPOSAL";

                if (patch == null) {
                    status = unresolved ? "NEW_UNRESOLVED" : "NEW_MAPPING";
                } else if (unresolved) {
                    proposedParent = patch.parentCode();
                    proposedSecondary = patch.secondaryClassificationCodes();
                    confidence = patch.confidence();
                    status = "REVIEW_REQUIRED_UNCHANGED";
                } else if (!Objects.equals(proposedParent, patch.parentCode())
                        || !proposedSecondary.equals(patch.secondaryClassificationCodes())) {
                    status = "REVIEW_REQUIRED_CHANGE";
                } else {
                    status = "REVIEW_REQUIRED_UNCHANGED";
                }
                reason = decision.reason();
            }

            proposals.add(new Proposal(
                    code,
                    node.title(),
                    node.state(),
                    node.sourceLevel(),
                    node.sourceParentCode(),
                    currentParent,
                    role,
                    currentSecondary,
                    proposedParent,
                    proposedSecondary,
                    confidence,
                    status,
                    authority,
                    unresolved,
                    reason,
                    patch == null ? null : patch.justification(),
                    ranked.stream().limit(5).toList()));
        }
        return List.copyOf(proposals);
    }

    static List<SemanticChange> semanticChanges(List<Proposal> proposals) {
        List<SemanticChange> changes = new ArrayList<>();
        for (Proposal proposal : proposals) {
            if ("REVIEWED_LOCKED".equals(proposal.status())) {
                continue;
            }
            boolean newMapping = proposal.currentOverlayParentCode() == null;
            boolean changed = newMapping
                    ? proposal.proposedParentCode() != null
                    : !Objects.equals(
                            proposal.currentOverlayParentCode(), proposal.proposedParentCode())
                            || !proposal.currentSecondaryClassificationCodes().equals(
                                    proposal.proposedSecondaryClassificationCodes());
            if (!changed) {
                continue;
            }
            changes.add(new SemanticChange(
                    proposal.code(),
                    newMapping ? "ADD_MAPPING" : "CHANGE_MAPPING",
                    proposal.currentOverlayParentCode(),
                    proposal.proposedParentCode(),
                    proposal.currentSecondaryClassificationCodes(),
                    proposal.proposedSecondaryClassificationCodes(),
                    proposal.confidence(),
                    proposal.reason()));
        }
        return List.copyOf(changes);
    }

    static FanOut fanOut(Map<String, String> parents) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (String parent : parents.values()) {
            counts.merge(parent, 1, Integer::sum);
        }
        int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return new FanOut(Map.copyOf(counts), maximum);
    }

    private static List<CandidateScore> rankCandidates(
            SourceNode node,
            String currentParent,
            Set<String> candidateFamilies,
            Map<String, String> currentParents,
            Map<String, SourceNode> nodes) {
        TokenProfile source = tokens(node.title(), node.description());
        List<CandidateScoreDraft> drafts = new ArrayList<>();
        for (String candidateCode : candidateFamilies) {
            if (candidateCode.equals(node.code())
                    || CatalogueOverlayProposalValidator.createsCycle(
                            node.code(), candidateCode, currentParents)) {
                continue;
            }
            SourceNode candidate = nodes.get(candidateCode);
            if (candidate == null) {
                continue;
            }
            TokenProfile family = tokens(candidate.title(), candidate.description());
            Set<String> titleMatches = intersection(source.titleTokens(), family.titleTokens());
            Set<String> allMatches = intersection(source.allTokens(), family.allTokens());
            Set<String> domainMatches = allMatches.stream()
                    .filter(token -> !FORM_TOKENS.contains(token))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
            Set<String> formMatches = allMatches.stream()
                    .filter(FORM_TOKENS::contains)
                    .collect(Collectors.toCollection(java.util.TreeSet::new));

            int score = 0;
            for (String token : titleMatches) {
                score += titleMatchWeight(token);
            }
            for (String token : domainMatches) {
                if (!titleMatches.contains(token)) {
                    score += descriptionMatchWeight(token);
                }
            }
            for (String token : formMatches) {
                if (!titleMatches.contains(token)) {
                    score += 15;
                }
            }
            if (candidateCode.equals(currentParent)) {
                score += 20;
            }

            Set<String> matched = new java.util.TreeSet<>();
            matched.addAll(domainMatches);
            matched.addAll(formMatches);
            drafts.add(new CandidateScoreDraft(
                    candidateCode, candidate.title(), score, List.copyOf(matched)));
        }
        drafts.sort(Comparator.comparingInt(CandidateScoreDraft::score).reversed()
                .thenComparing(CandidateScoreDraft::code));

        List<CandidateScore> result = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            CandidateScoreDraft draft = drafts.get(index);
            int nextScore = index + 1 < drafts.size() ? drafts.get(index + 1).score() : 0;
            result.add(new CandidateScore(
                    draft.code(),
                    draft.title(),
                    draft.score(),
                    confidence(draft.score(), draft.score() - nextScore),
                    draft.matchedTokens()));
        }
        return List.copyOf(result);
    }

    private static int titleMatchWeight(String token) {
        if (FORM_TOKENS.contains(token)) {
            return 80;
        }
        return BROAD_DOMAIN_TOKENS.contains(token) ? 60 : 220;
    }

    private static int descriptionMatchWeight(String token) {
        return BROAD_DOMAIN_TOKENS.contains(token) ? 8 : 35;
    }

    private static CandidateDecision decideCandidate(
            List<CandidateScore> ranked,
            String currentParent,
            boolean newMapping) {
        if (ranked.isEmpty()) {
            return new CandidateDecision(
                    newMapping ? null : currentParent,
                    BigDecimal.ZERO,
                    "No valid product-family candidate is available; expert review is required.",
                    true);
        }
        CandidateScore best = ranked.get(0);
        int secondScore = ranked.size() > 1 ? ranked.get(1).score() : 0;
        int margin = best.score() - secondScore;
        boolean sufficient = best.score() >= 120
                && (margin >= 25 || best.code().equals(currentParent));
        if (!sufficient) {
            return new CandidateDecision(
                    newMapping ? null : currentParent,
                    best.confidence(),
                    "Lexical evidence is inconclusive (best score " + best.score()
                            + ", margin " + margin + "); expert review is required.",
                    true);
        }
        String matches = best.matchedTokens().isEmpty()
                ? "none" : String.join(", ", best.matchedTokens());
        return new CandidateDecision(
                best.code(),
                best.confidence(),
                "Deterministic lexical evidence selected " + best.code()
                        + " (score " + best.score() + ", margin " + margin
                        + ", matched tokens: " + matches + ").",
                false);
    }

    private static List<String> secondarySuggestions(
            List<CandidateScore> ranked,
            String primary,
            int maximum) {
        return ranked.stream()
                .filter(candidate -> candidate.score() >= MINIMUM_SECONDARY_SCORE)
                .map(CandidateScore::code)
                .filter(code -> !Objects.equals(code, primary))
                .limit(maximum)
                .toList();
    }

    private static Map<String, List<String>> childrenByParent(Map<String, String> parents) {
        Map<String, List<String>> children = new HashMap<>();
        for (Map.Entry<String, String> entry : parents.entrySet()) {
            children.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        children.values().forEach(list -> list.sort(Comparator.naturalOrder()));
        return children;
    }

    private static TokenProfile tokens(String title, String description) {
        Set<String> titleTokens = tokenize(title);
        Set<String> all = new LinkedHashSet<>(titleTokens);
        all.addAll(tokenize(description));
        return new TokenProfile(Set.copyOf(titleTokens), Set.copyOf(all));
    }

    private static Set<String> tokenize(String text) {
        String decomposed = Normalizer.normalize(nullToEmpty(text), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        for (String raw : decomposed.split("[^a-z0-9]+")) {
            String token = singular(raw);
            if (token.length() < 2 || GENERIC_TOKENS.contains(token)) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    private static String singular(String token) {
        return switch (token) {
            case "analyses" -> "analysis";
            case "matrices" -> "matrix";
            case "statuses" -> "status";
            default -> token.endsWith("s")
                    && token.length() > 3
                    && !token.endsWith("ss")
                    && !token.endsWith("is")
                    && !token.endsWith("us")
                    && !token.endsWith("ics")
                    ? token.substring(0, token.length() - 1)
                    : token;
        };
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static BigDecimal confidence(int score, int margin) {
        if (score <= 0) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.UNNECESSARY);
        }
        double value = 0.35
                + Math.min(score, 600) / 1000.0
                + Math.min(Math.max(margin, 0), 300) / 1500.0;
        return decimal(Math.min(value, 0.99));
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record TokenProfile(Set<String> titleTokens, Set<String> allTokens) {
    }

    private record CandidateScoreDraft(
            String code,
            String title,
            int score,
            List<String> matchedTokens) {
    }

    private record CandidateDecision(
            String parentCode,
            BigDecimal confidence,
            String reason,
            boolean unresolved) {
    }
}
