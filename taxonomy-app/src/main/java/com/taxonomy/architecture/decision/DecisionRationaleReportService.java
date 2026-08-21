package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.ChildDecision;
import com.taxonomy.architecture.decision.DecisionRationaleReport.DecisionChapter;
import com.taxonomy.architecture.decision.DecisionRationaleReport.Disposition;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ExecutiveSummary;
import com.taxonomy.architecture.decision.DecisionRationaleReport.LeafCandidate;
import com.taxonomy.architecture.decision.DecisionRationaleReport.PathStep;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReasonSource;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportMetadata;
import com.taxonomy.architecture.decision.DecisionRationaleReport.ReportStatus;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a traceable report from one completed or partially completed hierarchy analysis.
 *
 * <p>The service does not call an LLM while the report is generated. It reuses the original
 * AI reasons captured during scoring, marks deterministic fallback text explicitly, and keeps
 * all numeric values and hierarchy links immutable. Consequently, regenerating a report from
 * the same analysis snapshot and taxonomy data produces the same decision content.</p>
 */
@Service
public class DecisionRationaleReportService {

    private static final Comparator<TaxonomyNode> NODE_ORDER = Comparator
            .comparing((TaxonomyNode node) -> node.getSortOrder() == null
                    ? Integer.MAX_VALUE : node.getSortOrder())
            .thenComparing(TaxonomyNode::getCode, Comparator.nullsLast(String::compareTo));

    private final TaxonomyService taxonomyService;
    private final TaxonomyCatalogueMetadataService catalogueMetadataService;
    private final DecisionReportBuildMetadataService buildMetadataService;
    private final String reportTimeZone;

    public DecisionRationaleReportService(
            TaxonomyService taxonomyService,
            TaxonomyCatalogueMetadataService catalogueMetadataService,
            DecisionReportBuildMetadataService buildMetadataService,
            @Value("${taxonomy.report.time-zone:Europe/Berlin}") String reportTimeZone) {
        this.taxonomyService = taxonomyService;
        this.catalogueMetadataService = catalogueMetadataService;
        this.buildMetadataService = buildMetadataService;
        this.reportTimeZone = normalized(reportTimeZone, "Europe/Berlin");
    }

    /** Input captured from the analysis session or an immutable portfolio snapshot. */
    public record DecisionAnalysisInput(
            String businessText,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            String provider,
            String analysisStatus,
            List<TaxonomyDiscrepancy> discrepancies,
            List<TaxonomyNodeDto> taxonomyTree,
            AnalysisSnapshotProvenance snapshotProvenance) {

        public DecisionAnalysisInput {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
            discrepancies = discrepancies == null ? List.of() : List.copyOf(discrepancies);
            taxonomyTree = taxonomyTree == null ? List.of() : List.copyOf(taxonomyTree);
        }

        /** Backward-compatible ad-hoc analysis input using the currently loaded hierarchy. */
        public DecisionAnalysisInput(
                String businessText,
                Map<String, Integer> scores,
                Map<String, String> reasons,
                String provider,
                String analysisStatus,
                List<TaxonomyDiscrepancy> discrepancies) {
            this(businessText, scores, reasons, provider, analysisStatus, discrepancies,
                    List.of(), null);
        }
    }

    /** Immutable evidence copied from a persisted requirement-analysis snapshot. */
    public record AnalysisSnapshotProvenance(
            String snapshotId,
            Long projectId,
            Long requirementId,
            Long requirementVersionId,
            Integer requirementVersionNumber,
            Instant createdAt,
            String createdBy,
            String modelName,
            String taxonomyFingerprintSha256,
            String promptFingerprintSha256) {
    }

    @Transactional(readOnly = true)
    public DecisionRationaleReport generate(
            DecisionAnalysisInput input,
            WorkspaceContext workspaceContext,
            ViewContext viewContext,
            Locale locale) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(workspaceContext, "workspaceContext must not be null");

        Locale effectiveLocale = locale == null ? Locale.ENGLISH : locale;
        boolean german = "de".equalsIgnoreCase(effectiveLocale.getLanguage());
        Map<String, Integer> scores = sanitizeScores(input.scores());
        Map<String, String> reasons = sanitizeReasons(input.reasons());

        HierarchyData hierarchy = input.taxonomyTree().isEmpty()
                ? currentHierarchy()
                : hierarchyFromSnapshot(input.taxonomyTree());
        List<TaxonomyNode> roots = hierarchy.roots();
        Map<String, List<TaxonomyNode>> childrenMap = hierarchy.childrenMap();
        Map<String, TaxonomyNode> nodesByCode = indexNodes(roots, childrenMap);
        List<TaxonomyNode> hierarchyOrder = preOrder(roots, childrenMap);

        Completeness completeness = assessCompleteness(roots, hierarchyOrder, childrenMap, scores);
        List<DecisionChapter> chapters = buildChapters(
                hierarchyOrder, childrenMap, scores, reasons, german);
        List<LeafCandidate> leaves = findLeadingLeaves(
                hierarchyOrder, childrenMap, scores, reasons, german);
        LeafCandidate leadingLeaf = leaves.isEmpty() ? null : leaves.get(0);
        List<PathStep> leadingPath = leadingLeaf == null
                ? List.of()
                : buildPath(leadingLeaf.code(), nodesByCode, scores, reasons, german);

        List<String> warnings = new ArrayList<>(buildWarnings(
                input, viewContext, completeness, scores, reasons, leaves,
                nodesByCode.keySet(), childrenMap, german));
        Instant generatedAt = Instant.now();

        TaxonomyCatalogueMetadataService.CatalogueMetadata catalogue =
                catalogueMetadataService.getMetadata();
        DecisionReportBuildMetadataService.BuildMetadata buildMetadata =
                buildMetadataService.current();
        String actualDataFingerprint = fingerprint(nodesByCode.values());
        String analysisSnapshotFingerprint = fingerprintAnalysis(input, scores, reasons);
        if (input.snapshotProvenance() != null
                && input.snapshotProvenance().taxonomyFingerprintSha256() != null
                && !input.snapshotProvenance().taxonomyFingerprintSha256().isBlank()
                && !input.snapshotProvenance().taxonomyFingerprintSha256()
                        .equalsIgnoreCase(actualDataFingerprint)) {
            warnings.add(german
                    ? "Der im Analysesnapshot gespeicherte Taxonomie-Fingerabdruck stimmt nicht mit der eingefrorenen Hierarchie im Analyse-Payload überein. Der Bericht bleibt nachvollziehbar, muss aber fachlich und technisch geprüft werden."
                    : "The taxonomy fingerprint stored in the analysis snapshot does not match the frozen hierarchy in the analysis payload. The report remains traceable but requires technical and substantive review.");
        }

        ReportStatus reportStatus = determineStatus(
                completeness.complete(), input.analysisStatus(), leaves, warnings,
                input.discrepancies());

        int suppliedReasonCount = (int) reasons.values().stream()
                .filter(reason -> reason != null && !reason.isBlank())
                .count();
        int positiveNodeCount = (int) scores.values().stream().filter(value -> value > 0).count();
        String branch = firstNonBlank(
                viewContext != null ? viewContext.basedOnBranch() : null,
                workspaceContext.currentBranch(),
                "unknown");
        String basedOnCommit = viewContext != null
                ? normalized(viewContext.basedOnCommit(), "unknown") : "unknown";
        boolean immutableSnapshot = !input.taxonomyTree().isEmpty();
        String recordedTaxonomyFingerprint = snapshotValue(
                input, AnalysisSnapshotProvenance::taxonomyFingerprintSha256, "unknown");
        String catalogueFile = immutableSnapshot
                ? "analysisPayload.tree · snapshot "
                        + snapshotValue(input, AnalysisSnapshotProvenance::snapshotId, "unknown")
                : catalogue.filename();
        String dataVersion = immutableSnapshot
                ? "snapshot fingerprint " + abbreviateHash(recordedTaxonomyFingerprint)
                : catalogue.version();
        String catalogueResourceFingerprint = immutableSnapshot
                ? "not persisted separately in the historical snapshot"
                : catalogue.sha256();
        String dataSource = immutableSnapshot
                ? (german ? "Unveränderlicher Anforderungs-Analysesnapshot"
                        : "Immutable requirement analysis snapshot")
                : catalogue.source();

        ReportMetadata metadata = new ReportMetadata(
                generatedAt,
                normalized(workspaceContext.username(), "system"),
                buildMetadata.version(),
                buildMetadata.commit(),
                catalogueFile,
                dataVersion,
                catalogueResourceFingerprint,
                actualDataFingerprint,
                analysisSnapshotFingerprint,
                dataSource,
                nodesByCode.size(),
                roots.size(),
                workspaceContext.repositoryId(),
                normalized(workspaceContext.workspaceId(), german ? "Zentral" : "Central"),
                branch,
                basedOnCommit,
                viewContext != null ? viewContext.commitTimestamp() : null,
                viewContext != null && viewContext.projectionStale(),
                viewContext != null && viewContext.indexStale(),
                normalized(input.provider(), "unknown"),
                normalized(input.analysisStatus(), "UNKNOWN"),
                snapshotValue(input, AnalysisSnapshotProvenance::modelName, "unknown"),
                snapshotValue(input, AnalysisSnapshotProvenance::snapshotId, "ad-hoc"),
                input.snapshotProvenance() != null ? input.snapshotProvenance().projectId() : null,
                input.snapshotProvenance() != null ? input.snapshotProvenance().requirementId() : null,
                input.snapshotProvenance() != null ? input.snapshotProvenance().requirementVersionId() : null,
                input.snapshotProvenance() != null ? input.snapshotProvenance().requirementVersionNumber() : null,
                input.snapshotProvenance() != null ? input.snapshotProvenance().createdAt() : null,
                snapshotValue(input, AnalysisSnapshotProvenance::createdBy, "unknown"),
                recordedTaxonomyFingerprint,
                snapshotValue(input, AnalysisSnapshotProvenance::promptFingerprintSha256, "unknown"),
                immutableSnapshot,
                reportTimeZone,
                suppliedReasonCount,
                scores.size(),
                positiveNodeCount,
                completeness.percent());

        ExecutiveSummary executiveSummary = new ExecutiveSummary(
                leadingLeaf,
                leadingPath,
                conciseConclusion(leadingLeaf, leadingPath, german),
                methodologyNote(german));

        return new DecisionRationaleReport(
                german ? "Hierarchischer Entscheidungs- und Begründungsbericht"
                        : "Hierarchical Decision Rationale Report",
                effectiveLocale.toLanguageTag(),
                normalized(input.businessText(), ""),
                reportStatus,
                metadata,
                executiveSummary,
                chapters,
                leaves,
                warnings,
                input.discrepancies(),
                viewContext);
    }

    private HierarchyData currentHierarchy() {
        List<TaxonomyNode> roots = new ArrayList<>(taxonomyService.getRootNodes());
        roots.sort(NODE_ORDER);
        return new HierarchyData(
                List.copyOf(roots),
                Map.copyOf(copyAndSort(taxonomyService.getChildrenMap())));
    }

    /**
     * Reconstructs transient domain nodes from the hierarchy frozen inside an immutable
     * {@link com.taxonomy.dto.AnalysisResult}. No current catalogue row is consulted.
     */
    private HierarchyData hierarchyFromSnapshot(List<TaxonomyNodeDto> tree) {
        if (tree == null || tree.isEmpty()) {
            throw new IllegalArgumentException(
                    "The analysis snapshot does not contain a frozen taxonomy hierarchy");
        }
        List<TaxonomyNode> roots = new ArrayList<>();
        Map<String, List<TaxonomyNode>> childrenMap = new LinkedHashMap<>();
        Set<String> codes = new LinkedHashSet<>();
        for (TaxonomyNodeDto rootDto : tree) {
            TaxonomyNode root = copySnapshotNode(rootDto, null, childrenMap, codes);
            if (root != null) {
                roots.add(root);
            }
        }
        roots.sort(NODE_ORDER);
        return new HierarchyData(List.copyOf(roots), immutableChildrenMap(childrenMap));
    }

    private TaxonomyNode copySnapshotNode(
            TaxonomyNodeDto dto,
            String inheritedParentCode,
            Map<String, List<TaxonomyNode>> childrenMap,
            Set<String> codes) {
        if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
            return null;
        }
        String code = dto.getCode().strip();
        if (!codes.add(code)) {
            throw new IllegalArgumentException(
                    "The frozen taxonomy hierarchy contains duplicate node code " + code);
        }
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setUuid(dto.getUuid());
        node.setNameEn(dto.getNameEn());
        node.setNameDe(dto.getNameDe());
        node.setDescriptionEn(dto.getDescriptionEn());
        node.setDescriptionDe(dto.getDescriptionDe());
        node.setParentCode(firstNonBlank(dto.getParentCode(), inheritedParentCode));
        node.setTaxonomyRoot(dto.getTaxonomyRoot());
        node.setLevel(dto.getLevel());
        node.setDataset(dto.getDataset());
        node.setExternalId(dto.getExternalId());
        node.setSource(dto.getSource());
        node.setReference(dto.getReference());
        node.setSortOrder(dto.getSortOrder());
        node.setState(dto.getState());

        List<TaxonomyNode> children = new ArrayList<>();
        for (TaxonomyNodeDto childDto : dto.getChildren() == null
                ? List.<TaxonomyNodeDto>of() : dto.getChildren()) {
            TaxonomyNode child = copySnapshotNode(childDto, code, childrenMap, codes);
            if (child != null) {
                children.add(child);
            }
        }
        children.sort(NODE_ORDER);
        if (!children.isEmpty()) {
            childrenMap.put(code, List.copyOf(children));
        }
        return node;
    }

    private Map<String, List<TaxonomyNode>> immutableChildrenMap(
            Map<String, List<TaxonomyNode>> source) {
        Map<String, List<TaxonomyNode>> immutable = new LinkedHashMap<>();
        source.forEach((code, children) -> immutable.put(code, List.copyOf(children)));
        return Map.copyOf(immutable);
    }

    private String snapshotValue(
            DecisionAnalysisInput input,
            java.util.function.Function<AnalysisSnapshotProvenance, String> accessor,
            String fallback) {
        if (input.snapshotProvenance() == null) {
            return fallback;
        }
        return normalized(accessor.apply(input.snapshotProvenance()), fallback);
    }

    private Map<String, Integer> sanitizeScores(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((code, value) -> {
            if (code == null || code.isBlank() || value == null) {
                return;
            }
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException(
                        "Score for node " + code + " must be between 0 and 100");
            }
            result.put(code.strip(), value);
        });
        return result;
    }

    private Map<String, String> sanitizeReasons(Map<String, String> source) {
        Map<String, String> result = new HashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((code, reason) -> {
            if (code != null && !code.isBlank() && reason != null && !reason.isBlank()) {
                result.put(code.strip(), reason.strip());
            }
        });
        return result;
    }

    private Map<String, List<TaxonomyNode>> copyAndSort(
            Map<String, List<TaxonomyNode>> original) {
        Map<String, List<TaxonomyNode>> result = new HashMap<>();
        if (original == null) {
            return result;
        }
        original.forEach((parentCode, children) -> {
            List<TaxonomyNode> sorted = new ArrayList<>(children == null ? List.of() : children);
            sorted.sort(NODE_ORDER);
            result.put(parentCode, List.copyOf(sorted));
        });
        return result;
    }

    private Map<String, TaxonomyNode> indexNodes(
            List<TaxonomyNode> roots,
            Map<String, List<TaxonomyNode>> childrenMap) {
        Map<String, TaxonomyNode> result = new LinkedHashMap<>();
        roots.forEach(node -> result.put(node.getCode(), node));
        childrenMap.values().forEach(children -> children.forEach(
                node -> result.putIfAbsent(node.getCode(), node)));
        return result;
    }

    private List<TaxonomyNode> preOrder(
            List<TaxonomyNode> roots,
            Map<String, List<TaxonomyNode>> childrenMap) {
        List<TaxonomyNode> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (TaxonomyNode root : roots) {
            visit(root, childrenMap, visited, ordered);
        }
        return ordered;
    }

    private void visit(
            TaxonomyNode node,
            Map<String, List<TaxonomyNode>> childrenMap,
            Set<String> visited,
            List<TaxonomyNode> ordered) {
        if (node == null || node.getCode() == null || !visited.add(node.getCode())) {
            return;
        }
        ordered.add(node);
        for (TaxonomyNode child : childrenMap.getOrDefault(node.getCode(), List.of())) {
            visit(child, childrenMap, visited, ordered);
        }
    }

    private Completeness assessCompleteness(
            List<TaxonomyNode> roots,
            List<TaxonomyNode> hierarchyOrder,
            Map<String, List<TaxonomyNode>> childrenMap,
            Map<String, Integer> scores) {
        Set<String> expectedCodes = new LinkedHashSet<>();
        roots.stream().map(TaxonomyNode::getCode).forEach(expectedCodes::add);
        List<String> incompleteParents = new ArrayList<>();
        List<String> unresolvedParents = new ArrayList<>();

        for (TaxonomyNode node : hierarchyOrder) {
            List<TaxonomyNode> children = childrenMap.getOrDefault(node.getCode(), List.of());
            if (children.isEmpty()) {
                continue;
            }
            Integer score = scores.get(node.getCode());
            List<String> childCodes = children.stream().map(TaxonomyNode::getCode).toList();
            boolean anyPositiveChild = childCodes.stream()
                    .anyMatch(code -> scores.getOrDefault(code, 0) > 0);

            // A positive child cannot form a traceable decision when its parent was not
            // evaluated positively. Keep the chapter visible, but mark the snapshot incomplete.
            if (anyPositiveChild && (score == null || score <= 0)) {
                expectedCodes.add(node.getCode());
                expectedCodes.addAll(childCodes);
                incompleteParents.add(node.getCode());
                continue;
            }
            if (score == null || score <= 0) {
                continue;
            }

            expectedCodes.addAll(childCodes);
            boolean allEvaluated = childCodes.stream().allMatch(scores::containsKey);
            if (!allEvaluated) {
                incompleteParents.add(node.getCode());
            } else if (!anyPositiveChild) {
                unresolvedParents.add(node.getCode());
            }
        }

        long evaluatedExpected = expectedCodes.stream().filter(scores::containsKey).count();
        double percent = expectedCodes.isEmpty()
                ? 100.0
                : roundOneDecimal(evaluatedExpected * 100.0 / expectedCodes.size());
        return new Completeness(
                incompleteParents.isEmpty()
                        && unresolvedParents.isEmpty()
                        && evaluatedExpected == expectedCodes.size(),
                percent,
                List.copyOf(incompleteParents),
                List.copyOf(unresolvedParents));
    }

    private List<DecisionChapter> buildChapters(
            List<TaxonomyNode> hierarchyOrder,
            Map<String, List<TaxonomyNode>> childrenMap,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            boolean german) {
        List<DecisionChapter> chapters = new ArrayList<>();
        int chapterNumber = 1;

        for (TaxonomyNode parent : hierarchyOrder) {
            List<TaxonomyNode> children = childrenMap.getOrDefault(parent.getCode(), List.of());
            if (children.isEmpty()
                    || children.stream().noneMatch(child -> scores.getOrDefault(child.getCode(), 0) > 0)) {
                continue;
            }

            List<String> missingChildCodes = children.stream()
                    .map(TaxonomyNode::getCode)
                    .filter(code -> !scores.containsKey(code))
                    .toList();
            Integer parentScore = scores.get(parent.getCode());
            int highestChildScore = children.stream()
                    .map(TaxonomyNode::getCode)
                    .map(scores::get)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0);

            List<TaxonomyNode> rankingOrder = children.stream()
                    .filter(child -> scores.getOrDefault(child.getCode(), 0) > 0)
                    .sorted(Comparator
                            .comparingInt((TaxonomyNode child) -> scores.get(child.getCode())).reversed()
                            .thenComparing(NODE_ORDER))
                    .toList();
            Map<String, Integer> ranks = new HashMap<>();
            int previousScore = Integer.MIN_VALUE;
            int previousRank = 0;
            for (int index = 0; index < rankingOrder.size(); index++) {
                TaxonomyNode child = rankingOrder.get(index);
                int childScore = scores.get(child.getCode());
                int rank = childScore == previousScore ? previousRank : index + 1;
                ranks.put(child.getCode(), rank);
                previousScore = childScore;
                previousRank = rank;
            }

            List<ChildDecision> childDecisions = new ArrayList<>();
            for (TaxonomyNode child : children) {
                Integer score = scores.get(child.getCode());
                boolean leaf = childrenMap.getOrDefault(child.getCode(), List.of()).isEmpty();
                Disposition disposition;
                if (score == null) {
                    disposition = Disposition.NOT_EVALUATED;
                } else if (score == 0) {
                    disposition = Disposition.REJECTED;
                } else if (leaf) {
                    disposition = Disposition.LEAF_CANDIDATE;
                } else {
                    disposition = Disposition.CONTINUED;
                }
                Reason reason = reasonFor(child, score, reasons, german);
                childDecisions.add(new ChildDecision(
                        child.getCode(),
                        displayName(child, german),
                        displayDescription(child, german),
                        score,
                        localShare(score, parentScore),
                        score != null && score > 0 ? ranks.get(child.getCode()) : null,
                        score != null && score > 0 && score == highestChildScore,
                        disposition,
                        reason.text(),
                        reason.source(),
                        leaf));
            }

            childDecisions.sort(Comparator
                    .comparing((ChildDecision child) -> child.absoluteScore() == null)
                    .thenComparing((ChildDecision child) -> child.absoluteScore() == null
                            ? Integer.MIN_VALUE : child.absoluteScore(), Comparator.reverseOrder())
                    .thenComparing(ChildDecision::code));

            chapters.add(new DecisionChapter(
                    chapterNumber++,
                    parent.getCode(),
                    displayName(parent, german),
                    displayDescription(parent, german),
                    parentScore,
                    parent.getLevel(),
                    missingChildCodes.isEmpty(),
                    decisionSummary(parent, childDecisions, german),
                    comparativeRationale(parent, parentScore, childDecisions, german),
                    childDecisions,
                    missingChildCodes));
        }
        return List.copyOf(chapters);
    }

    private List<LeafCandidate> findLeadingLeaves(
            List<TaxonomyNode> hierarchyOrder,
            Map<String, List<TaxonomyNode>> childrenMap,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            boolean german) {
        List<LeafCandidate> leaves = hierarchyOrder.stream()
                .filter(node -> childrenMap.getOrDefault(node.getCode(), List.of()).isEmpty())
                .filter(node -> scores.getOrDefault(node.getCode(), 0) > 0)
                .map(node -> {
                    Reason reason = reasonFor(node, scores.get(node.getCode()), reasons, german);
                    List<TaxonomyNode> path = pathToRoot(node.getCode(), hierarchyOrder);
                    return new LeafCandidate(
                            node.getCode(),
                            displayName(node, german),
                            scores.get(node.getCode()),
                            normalized(node.getTaxonomyRoot(), rootFromCode(node.getCode())),
                            node.getLevel(),
                            path.stream().map(TaxonomyNode::getCode).collect(Collectors.joining(" → ")),
                            reason.text(),
                            reason.source());
                })
                .sorted(Comparator
                        .comparingInt(LeafCandidate::score).reversed()
                        .thenComparing(Comparator.comparingInt(LeafCandidate::depth).reversed())
                        .thenComparing(LeafCandidate::code))
                .limit(20)
                .toList();
        return List.copyOf(leaves);
    }

    /**
     * Builds a path without issuing one query per level. The supplied hierarchy list contains
     * all current nodes, so parent links can be followed deterministically in memory.
     */
    private List<TaxonomyNode> pathToRoot(String code, Collection<TaxonomyNode> nodes) {
        Map<String, TaxonomyNode> byCode = nodes.stream()
                .filter(node -> node.getCode() != null)
                .collect(Collectors.toMap(
                        TaxonomyNode::getCode,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<TaxonomyNode> reversed = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        TaxonomyNode current = byCode.get(code);
        while (current != null && visited.add(current.getCode())) {
            reversed.add(current);
            String parentCode = current.getParentCode();
            current = parentCode == null ? null : byCode.get(parentCode);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private List<PathStep> buildPath(
            String leafCode,
            Map<String, TaxonomyNode> nodesByCode,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            boolean german) {
        List<TaxonomyNode> path = pathToRoot(leafCode, nodesByCode.values());
        List<PathStep> result = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            TaxonomyNode node = path.get(index);
            Integer score = scores.get(node.getCode());
            Integer parentScore = index == 0 ? null : scores.get(path.get(index - 1).getCode());
            Reason reason = reasonFor(node, score, reasons, german);
            result.add(new PathStep(
                    index + 1,
                    node.getCode(),
                    displayName(node, german),
                    score,
                    index == 0 ? null : localShare(score, parentScore),
                    reason.text(),
                    reason.source()));
        }
        return List.copyOf(result);
    }

    private List<String> buildWarnings(
            DecisionAnalysisInput input,
            ViewContext viewContext,
            Completeness completeness,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            List<LeafCandidate> leaves,
            Set<String> knownNodeCodes,
            Map<String, List<TaxonomyNode>> childrenMap,
            boolean german) {
        List<String> warnings = new ArrayList<>();
        if (input.snapshotProvenance() == null) {
            warnings.add(german
                    ? "Dieser Bericht wurde aus dem aktuellen Analysezustand erzeugt und ist nicht an einen unveränderlichen Projekt-Analysesnapshot gebunden. Für einen formalen Entscheidungsnachweis sollte der snapshotbasierte Export verwendet werden."
                    : "This report was generated from the current analysis state and is not bound to an immutable project-analysis snapshot. Use the snapshot-backed export for formal decision evidence.");
        }
        List<String> unknownCodes = scores.keySet().stream()
                .filter(code -> !knownNodeCodes.contains(code))
                .sorted()
                .toList();
        if (!unknownCodes.isEmpty()) {
            warnings.add(german
                    ? "Bewertungen verweisen auf Knoten, die im aktuell geladenen Taxonomiedatenbestand nicht vorkommen: "
                        + String.join(", ", unknownCodes) + "."
                    : "Scores refer to nodes that do not exist in the currently loaded taxonomy data: "
                        + String.join(", ", unknownCodes) + ".");
        }
        if (!completeness.incompleteParents().isEmpty()) {
            warnings.add(german
                    ? "Die Analyse ist unvollständig. Bei folgenden positiven Vaterknoten wurden nicht alle direkten Kinder bewertet: "
                        + String.join(", ", completeness.incompleteParents()) + "."
                    : "The analysis is incomplete. Not all direct children were evaluated for these positive parent nodes: "
                        + String.join(", ", completeness.incompleteParents()) + ".");
        }
        if (!completeness.unresolvedParents().isEmpty()) {
            warnings.add(german
                    ? "Positive Vaterknoten ohne positiv bewertetes direktes Kind: "
                        + String.join(", ", completeness.unresolvedParents()) + "."
                    : "Positive parent nodes without a positively scored direct child: "
                        + String.join(", ", completeness.unresolvedParents()) + ".");
        }
        if (leaves.isEmpty()) {
            warnings.add(german
                    ? "Es wurde kein positiv bewerteter tatsächlicher Blattknoten ermittelt."
                    : "No positively scored actual leaf node was identified.");
        }
        long missingPositiveReasons = scores.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .filter(entry -> !reasons.containsKey(entry.getKey()))
                .count();
        if (missingPositiveReasons > 0) {
            warnings.add(german
                    ? "Für " + missingPositiveReasons
                        + " positiv bewertete Knoten lag keine ursprüngliche KI-Einzelbegründung vor; diese Stellen sind im Bericht als deterministische Herleitung gekennzeichnet."
                    : "No original AI scoring reason was available for " + missingPositiveReasons
                        + " positively scored nodes; those passages are marked as deterministic derivations.");
        }
        List<String> inconsistentBudgets = new ArrayList<>();
        for (Map.Entry<String, List<TaxonomyNode>> entry : childrenMap.entrySet()) {
            Integer parentScore = scores.get(entry.getKey());
            if (parentScore == null || parentScore <= 0) {
                continue;
            }
            List<TaxonomyNode> children = entry.getValue();
            boolean allEvaluated = children.stream()
                    .map(TaxonomyNode::getCode)
                    .allMatch(scores::containsKey);
            if (!allEvaluated) {
                continue;
            }
            int childTotal = children.stream()
                    .map(TaxonomyNode::getCode)
                    .mapToInt(code -> scores.getOrDefault(code, 0))
                    .sum();
            if (childTotal != 0 && childTotal != parentScore) {
                inconsistentBudgets.add(entry.getKey() + " (" + childTotal + "% / "
                        + parentScore + "%)");
            }
        }
        if (!inconsistentBudgets.isEmpty()) {
            warnings.add(german
                    ? "Bei folgenden Vaterknoten entspricht die Summe der vollständig bewerteten direkten Kinder nicht dem gespeicherten Vaterbudget (Kindersumme / Vaterwert): "
                        + String.join(", ", inconsistentBudgets) + "."
                    : "For the following parent nodes, the sum of fully evaluated direct children does not match the stored parent budget (child sum / parent score): "
                        + String.join(", ", inconsistentBudgets) + ".");
        }
        if (input.analysisStatus() != null
                && !"SUCCESS".equalsIgnoreCase(input.analysisStatus())) {
            warnings.add(german
                    ? "Der Analysezustand lautet " + input.analysisStatus()
                        + "; das Ergebnis darf nicht ohne Prüfung als vollständig behandelt werden."
                    : "The analysis status is " + input.analysisStatus()
                        + "; the result must not be treated as complete without review.");
        }
        if (viewContext != null && viewContext.projectionStale()) {
            warnings.add(german
                    ? "Die zugrunde liegende relationale Projektion war bei der Berichterzeugung als veraltet markiert."
                    : "The underlying relational projection was marked stale when the report was generated.");
        }
        if (viewContext != null && viewContext.indexStale()) {
            warnings.add(german
                    ? "Der zugrunde liegende Suchindex war bei der Berichterzeugung als veraltet markiert."
                    : "The underlying search index was marked stale when the report was generated.");
        }
        if (input.discrepancies() != null && !input.discrepancies().isEmpty()) {
            warnings.add(german
                    ? "Die Analyse enthält " + input.discrepancies().size()
                        + " dokumentierte Abweichung(en) zwischen Rohverteilung und Vaterbudget."
                    : "The analysis contains " + input.discrepancies().size()
                        + " documented discrepancy/discrepancies between the raw distribution and parent budget.");
        }
        return List.copyOf(warnings);
    }

    private ReportStatus determineStatus(
            boolean complete,
            String analysisStatus,
            List<LeafCandidate> leaves,
            List<String> warnings,
            List<TaxonomyDiscrepancy> discrepancies) {
        boolean analysisSucceeded = "SUCCESS".equalsIgnoreCase(analysisStatus);
        if (leaves.isEmpty()) {
            return complete && analysisSucceeded
                    ? ReportStatus.NO_RESULT : ReportStatus.DRAFT_INCOMPLETE;
        }
        if (!complete || !analysisSucceeded) {
            return ReportStatus.DRAFT_INCOMPLETE;
        }
        if (!warnings.isEmpty() || (discrepancies != null && !discrepancies.isEmpty())) {
            return ReportStatus.FINAL_WITH_WARNINGS;
        }
        return ReportStatus.FINAL;
    }

    private String decisionSummary(
            TaxonomyNode parent,
            List<ChildDecision> children,
            boolean german) {
        List<ChildDecision> positive = children.stream()
                .filter(child -> child.absoluteScore() != null && child.absoluteScore() > 0)
                .toList();
        List<ChildDecision> leaders = positive.stream().filter(ChildDecision::leadingSibling).toList();
        String allPositive = positive.stream()
                .map(child -> child.code() + " (" + child.absoluteScore() + " %)")
                .collect(Collectors.joining(", "));
        String leaderText = leaders.stream()
                .map(child -> child.code() + " (" + child.absoluteScore() + " %)")
                .collect(Collectors.joining(", "));
        if (german) {
            return "Vom Vaterknoten " + parent.getCode() + " werden die positiven Pfade "
                    + allPositive + " weitergeführt. Innerhalb dieser Geschwistergruppe führt "
                    + leaderText + ".";
        }
        return "From parent node " + parent.getCode() + ", the positive paths "
                + allPositive + " are continued. Within this sibling group, "
                + leaderText + " leads.";
    }

    private String comparativeRationale(
            TaxonomyNode parent,
            Integer parentScore,
            List<ChildDecision> children,
            boolean german) {
        List<ChildDecision> positive = children.stream()
                .filter(child -> child.absoluteScore() != null && child.absoluteScore() > 0)
                .toList();
        List<ChildDecision> leaders = positive.stream().filter(ChildDecision::leadingSibling).toList();
        long rejected = children.stream()
                .filter(child -> child.disposition() == Disposition.REJECTED)
                .count();
        long missing = children.stream()
                .filter(child -> child.disposition() == Disposition.NOT_EVALUATED)
                .count();

        String leaderReasons = leaders.stream()
                .map(child -> child.code() + ": " + child.reason())
                .collect(Collectors.joining(" "));
        if (german) {
            StringBuilder text = new StringBuilder();
            text.append("Der Vaterknoten ").append(parent.getCode())
                    .append(" trägt ").append(scoreText(parentScore, true))
                    .append(" der anforderungsbezogenen Relevanz in diese Entscheidung. ")
                    .append("Die höchste direkte Kinderbewertung entfällt auf ")
                    .append(leaders.stream()
                            .map(child -> child.code() + " mit " + child.absoluteScore() + " %")
                            .collect(Collectors.joining(" und ")))
                    .append(". ").append(leaderReasons);
            if (positive.size() > leaders.size()) {
                text.append(" Weitere positive Kinder werden entsprechend ihrer niedrigeren Werte als nachgeordnete, aber weiterhin relevante Pfade dokumentiert.");
            }
            if (rejected > 0) {
                text.append(" ").append(rejected)
                        .append(" direkt bewertete Alternative(n) erhielten 0 % und wurden nicht weiter verfolgt.");
            }
            if (missing > 0) {
                text.append(" Für ").append(missing)
                        .append(" direkte Kinder fehlt eine Bewertung; daraus wird ausdrücklich keine Ablehnung abgeleitet.");
            }
            return text.toString();
        }

        StringBuilder text = new StringBuilder();
        text.append("Parent node ").append(parent.getCode())
                .append(" carries ").append(scoreText(parentScore, false))
                .append(" of requirement-related relevance into this decision. ")
                .append("The highest direct child score belongs to ")
                .append(leaders.stream()
                        .map(child -> child.code() + " at " + child.absoluteScore() + "%")
                        .collect(Collectors.joining(" and ")))
                .append(". ").append(leaderReasons);
        if (positive.size() > leaders.size()) {
            text.append(" Other positive children remain documented as subordinate but relevant paths according to their lower scores.");
        }
        if (rejected > 0) {
            text.append(" ").append(rejected)
                    .append(" directly evaluated alternative(s) scored 0% and were not pursued.");
        }
        if (missing > 0) {
            text.append(" A score is missing for ").append(missing)
                    .append(" direct child/children; this is explicitly not interpreted as rejection.");
        }
        return text.toString();
    }

    private String conciseConclusion(
            LeafCandidate leadingLeaf,
            List<PathStep> path,
            boolean german) {
        if (leadingLeaf == null) {
            return german
                    ? "Aus den vorliegenden Bewertungen konnte kein positiv bewerteter tatsächlicher Blattknoten abgeleitet werden."
                    : "No positively scored actual leaf node could be derived from the available evaluations.";
        }
        String pathText = path.stream().map(PathStep::code).collect(Collectors.joining(" → "));
        if (german) {
            return "Der am höchsten bewertete tatsächliche Blattknoten ist "
                    + leadingLeaf.code() + " – " + leadingLeaf.title() + " mit "
                    + leadingLeaf.score() + " %. Der dokumentierte Entscheidungspfad lautet: "
                    + pathText + ".";
        }
        return "The highest-rated actual leaf node is " + leadingLeaf.code() + " – "
                + leadingLeaf.title() + " at " + leadingLeaf.score()
                + "%. The documented decision path is: " + pathText + ".";
    }

    private String methodologyNote(boolean german) {
        if (german) {
            return "Die Prozentwerte sind Relevanz- und Zuordnungswerte, keine statistischen Wahrscheinlichkeiten. "
                    + "Wurzelbereiche werden unabhängig auf 0–100 bewertet; unterhalb eines Vaterknotens dokumentiert der lokale Anteil die Verteilung innerhalb seiner direkten Kinder. "
                    + "Der Bericht übernimmt ursprüngliche KI-Begründungen unverändert und kennzeichnet deterministische Ersatztexte ausdrücklich.";
        }
        return "Percentages are relevance and allocation scores, not statistical probabilities. "
                + "Root areas are rated independently on 0–100; below a parent, the local share documents the distribution among its direct children. "
                + "The report preserves original AI reasons and explicitly marks deterministic fallback text.";
    }

    private Reason reasonFor(
            TaxonomyNode node,
            Integer score,
            Map<String, String> reasons,
            boolean german) {
        String reason = reasons.get(node.getCode());
        if (reason != null && !reason.isBlank()) {
            return new Reason(reason, ReasonSource.AI_SCORING);
        }
        if (score == null) {
            return new Reason(
                    german
                            ? "Nicht bewertet. Aus der fehlenden Bewertung darf keine fachliche Ablehnung abgeleitet werden."
                            : "Not evaluated. A missing score must not be interpreted as substantive rejection.",
                    ReasonSource.MISSING);
        }
        if (score == 0) {
            return new Reason(
                    german
                            ? "Der Knoten wurde bewertet, erhielt 0 % und wurde deshalb in diesem Pfad nicht weiter verfolgt."
                            : "The node was evaluated, scored 0%, and was therefore not pursued in this path.",
                    ReasonSource.DETERMINISTIC_TRACE);
        }
        return new Reason(
                german
                        ? "Für diesen positiven Bewertungswert lag keine separate KI-Einzelbegründung vor. Die Aufnahme in den Bericht folgt ausschließlich aus dem gespeicherten Wert und der Taxonomiehierarchie."
                        : "No separate AI reason was available for this positive score. Inclusion in the report follows only from the stored value and taxonomy hierarchy.",
                ReasonSource.DETERMINISTIC_TRACE);
    }

    private Double localShare(Integer childScore, Integer parentScore) {
        if (childScore == null || parentScore == null || parentScore <= 0) {
            return null;
        }
        return roundOneDecimal(childScore * 100.0 / parentScore);
    }

    private String displayName(TaxonomyNode node, boolean german) {
        if (german && node.getNameDe() != null && !node.getNameDe().isBlank()) {
            return node.getNameDe().strip();
        }
        return normalized(node.getNameEn(), node.getCode());
    }

    private String displayDescription(TaxonomyNode node, boolean german) {
        if (german && node.getDescriptionDe() != null && !node.getDescriptionDe().isBlank()) {
            return node.getDescriptionDe().strip();
        }
        return normalized(node.getDescriptionEn(), "");
    }

    private String fingerprint(Collection<TaxonomyNode> nodes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            nodes.stream()
                    .filter(node -> node.getCode() != null)
                    .sorted(Comparator.comparing(TaxonomyNode::getCode))
                    .map(this::canonicalNodeLine)
                    .forEach(line -> digest.update(line.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            return "unavailable";
        }
    }

    /** Matches {@code PortfolioFingerprintService.taxonomyFingerprint()}. */
    private String canonicalNodeLine(TaxonomyNode node) {
        return String.join("\u001f",
                value(node.getCode()),
                value(node.getNameEn()),
                value(node.getDescriptionEn()),
                value(node.getTaxonomyRoot()),
                Integer.toString(node.getLevel())) + "\n";
    }

    private String fingerprintAnalysis(
            DecisionAnalysisInput input,
            Map<String, Integer> scores,
            Map<String, String> reasons) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "requirement", normalized(input.businessText(), ""));
            updateDigest(digest, "provider", normalized(input.provider(), "unknown"));
            updateDigest(digest, "status", normalized(input.analysisStatus(), "UNKNOWN"));
            if (input.snapshotProvenance() != null) {
                updateDigest(digest, "snapshot-id",
                        normalized(input.snapshotProvenance().snapshotId(), ""));
                updateDigest(digest, "snapshot-created-at",
                        String.valueOf(input.snapshotProvenance().createdAt()));
                updateDigest(digest, "snapshot-created-by",
                        normalized(input.snapshotProvenance().createdBy(), ""));
                updateDigest(digest, "taxonomy-fingerprint",
                        normalized(input.snapshotProvenance().taxonomyFingerprintSha256(), ""));
                updateDigest(digest, "prompt-fingerprint",
                        normalized(input.snapshotProvenance().promptFingerprintSha256(), ""));
            }
            scores.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> updateDigest(
                            digest, "score:" + entry.getKey(), String.valueOf(entry.getValue())));
            reasons.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> updateDigest(
                            digest, "reason:" + entry.getKey(), entry.getValue()));
            for (int index = 0; index < input.discrepancies().size(); index++) {
                updateDigest(digest, "discrepancy:" + index,
                        String.valueOf(input.discrepancies().get(index)));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            return "unavailable";
        }
    }

    private void updateDigest(MessageDigest digest, String key, String value) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
        digest.update(normalized(value, "").getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private String rootFromCode(String code) {
        if (code == null || code.isBlank()) {
            return "unknown";
        }
        int separator = code.indexOf('-');
        return separator > 0 ? code.substring(0, separator) : code;
    }

    private String abbreviateHash(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.strip();
        return normalized.length() <= 16 ? normalized : normalized.substring(0, 16);
    }

    private String scoreText(Integer score, boolean german) {
        if (score == null) {
            return german ? "keinen gespeicherten Wert" : "no stored score";
        }
        return score + (german ? " %" : "%");
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private record HierarchyData(
            List<TaxonomyNode> roots,
            Map<String, List<TaxonomyNode>> childrenMap) {
    }

    private record Completeness(
            boolean complete,
            double percent,
            List<String> incompleteParents,
            List<String> unresolvedParents) {
    }

    private record Reason(String text, ReasonSource source) {
    }
}
