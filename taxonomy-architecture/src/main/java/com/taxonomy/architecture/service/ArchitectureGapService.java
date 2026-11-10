package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.dsl.validation.RelationMetamodel;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Evaluates explicit business obligations against an exact, authorized model revision. */
@Service
public class ArchitectureGapService {
    private final RelationProjectionReadService projectionReader;
    private final WorkspaceDslReadPort dslReader;
    public ArchitectureGapService(RelationProjectionReadService projectionReader, WorkspaceDslReadPort dslReader) {
        this.projectionReader = projectionReader; this.dslReader = dslReader;
    }
    @Transactional(readOnly = true)
    public GapAnalysisView analyze(RepositoryContext context, String commitId, String profileId, String profileVersion,
                                  EvidenceCompleteness completeness, String businessText) throws IOException {
        Objects.requireNonNull(completeness, "evidenceCompleteness must be explicit");
        var profile = ObligationProfiles.require(profileId, profileVersion);
        var snapshot = projectionReader.readSnapshot(context, commitId);
        var read = dslReader.openRead(context);
        String dsl = read.dslAtCommit(snapshot.commitId()).orElseThrow(() -> new IllegalStateException("Selected revision has no canonical model"));
        var model = checkedModel(dsl);
        Map<String, String> roots = new TreeMap<>();
        boolean knownTypes = true;
        for (var element : model.getElements()) {
            String root = TaxonomyRootTypes.rootFor(element.getType());
            if (root == null) knownTypes = false;
            else roots.put(element.getId(), root);
        }
        List<ObligationEvaluator.Relation> relations = snapshot.relations().stream()
                .map(r -> new ObligationEvaluator.Relation(r.sourceCode(), r.targetCode(), r.relationType().name(), r.status())).toList();
        boolean knownEndpoints = relations.stream().allMatch(r -> roots.containsKey(r.sourceId()) && roots.containsKey(r.targetId()));
        EvidenceCompleteness effective = knownTypes && knownEndpoints ? completeness : EvidenceCompleteness.INCOMPLETE;
        var view = assessment(profile, effective, businessText);
        view.setWorkspaceId(context.workspaceId()); view.setWorkspaceScopeKey(com.taxonomy.model.WorkspaceOverlayScope.keyFor(context.workspaceId()));
        view.setRepositoryId(context.repositoryId()); view.setBranch(context.branch()); view.setCommitId(snapshot.commitId());
        view.setEvidenceAuthority("CANONICAL_SNAPSHOT"); view.setTotalAnchors(model.getElements().size());
        view.setJudgments(new ObligationEvaluator().evaluate(profile, roots, relations, effective));
        if (effective != completeness) view.getNotes().add("Unknown element types or unresolved relation endpoints prevent complete assessment.");
        view.getNotes().add("Completeness and judgments are limited to this model revision and selected profile; external architecture is not assessed.");
        read.verifyExpectedHead(snapshot.commitId());
        return view;
    }
    /** Retained relevance analysis has no selected obligation profile or authoritative model snapshot. */
    public static GapAnalysisView compatibilityAssessment(String businessText) {
        var view = assessment(ObligationProfiles.COMPATIBILITY, EvidenceCompleteness.INCOMPLETE, businessText);
        view.setEvidenceAuthority("RETAINED_ANALYSIS");
        view.getNotes().add("Compatibility permits relations but imposes no business obligations. Select a profile and canonical model revision to assess obligations.");
        return view;
    }
    private static GapAnalysisView assessment(ObligationProfile profile, EvidenceCompleteness completeness, String businessText) {
        var view = new GapAnalysisView(); view.setBusinessText(businessText);
        view.setProfileId(profile.id()); view.setProfileVersion(profile.version()); view.setMetamodelVersion(RelationMetamodel.VERSION);
        view.setEvidenceCompleteness(completeness); return view;
    }
    private static com.taxonomy.dsl.model.CanonicalArchitectureModel checkedModel(String dsl) {
        var model = new ArchitectureDslCommands().model(dsl);
        if (!new com.taxonomy.dsl.validation.DslValidator().validate(model).isValid())
            throw new IllegalStateException("Selected canonical model has invalid or duplicate identities");
        return model;
    }
    /** Counts only explicit APQC imports and their accepted links within the selected snapshot. */
    @Transactional(readOnly = true)
    public ApqcCoverageResult analyzeApqcCoverage(RepositoryContext context, String commitId) throws IOException {
        var snapshot = projectionReader.readSnapshot(context, commitId);
        var read = dslReader.openRead(context);
        var model = checkedModel(read.dslAtCommit(snapshot.commitId())
                .orElseThrow(() -> new IllegalStateException("Selected revision has no canonical model")));
        var apqc = model.getElements().stream().filter(e -> "apqc".equals(e.getExtensions().get("x-source-framework"))).toList();
        Set<String> members = model.getElements().stream().map(com.taxonomy.dsl.model.ArchitectureElement::getId).collect(Collectors.toSet());
        Set<String> linked = snapshot.relations().stream().filter(r -> "accepted".equals(r.status()))
                .filter(r -> members.contains(r.sourceCode()) && members.contains(r.targetCode()))
                .flatMap(r -> java.util.stream.Stream.of(r.sourceCode(), r.targetCode())).collect(Collectors.toSet());
        Map<String,Integer> levels = new TreeMap<>();
        apqc.forEach(e -> levels.merge(e.getExtensions().getOrDefault("x-apqc-level", "Unknown"), 1, Integer::sum));
        var unlinked = apqc.stream().map(com.taxonomy.dsl.model.ArchitectureElement::getId).filter(id -> !linked.contains(id)).sorted().toList();
        int count = apqc.size() - unlinked.size();
        read.verifyExpectedHead(snapshot.commitId());
        return new ApqcCoverageResult(apqc.isEmpty() ? "INCONCLUSIVE" : "OBSERVED", apqc.size(), count,
                apqc.isEmpty() ? null : count * 100.0 / apqc.size(), unlinked, levels, context.repositoryId(), context.workspaceId(),
                context.branch(), snapshot.commitId(), List.of(apqc.isEmpty()
                ? "No explicit APQC provenance is present in this snapshot; APQC coverage was not assessed."
                : "Counts describe observed APQC elements and accepted links in this revision; unlinked elements are not business obligation violations."));
    }
}
