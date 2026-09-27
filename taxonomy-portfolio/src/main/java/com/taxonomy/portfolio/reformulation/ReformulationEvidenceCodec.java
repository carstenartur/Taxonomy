package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.MetaAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.ast.SourceLocation;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.reformulation.ReformulationBaseline;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encodes adoption evidence with business identities only and validates it
 * before portfolio materialization can mutate a target workspace.
 */
@Service
public class ReformulationEvidenceCodec {

    public static final String BLOCK_KIND = "reformulationEvidence";
    public static final String CURRENT_SCHEMA = "reformulation-evidence-v1";
    public static final String ANCESTRY_SCHEMA = "reformulation-evidence-v2";
    private static final Set<String> READABLE_SCHEMAS = Set.of(CURRENT_SCHEMA, "1", ANCESTRY_SCHEMA);
    private static final SourceLocation GENERATED =
            new SourceLocation("reformulation-evidence-projection", 1, 1);

    private final PortfolioJsonCodec json;
    private final ReformulationAdoptionRepository adoptions;
    private final ReformulationPortableEvidenceRepository importedEvidence;
    private final ArchitectureProjectRepository projects;
    private final ProjectRequirementRepository requirements;
    private final ProjectRequirementVersionRepository versions;
    private final ReformulationProposalRepository proposals;
    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public ReformulationEvidenceCodec(
            PortfolioJsonCodec json,
            ReformulationAdoptionRepository adoptions,
            ReformulationPortableEvidenceRepository importedEvidence,
            ArchitectureProjectRepository projects,
            ProjectRequirementRepository requirements,
            ProjectRequirementVersionRepository versions,
            ReformulationProposalRepository proposals) {
        this.json = json;
        this.adoptions = adoptions;
        this.importedEvidence = importedEvidence;
        this.projects = projects;
        this.requirements = requirements;
        this.versions = versions;
        this.proposals = proposals;
    }

    /**
     * Portable immutable evidence. No field is a source database identifier.
     */
    public record Payload(
            String projectKey,
            String requirementKey,
            int sourceVersionNumber,
            int previousActiveVersionNumber,
            int targetVersionNumber,
            String analysisSnapshotId,
            String originalText,
            String finalText,
            String targetContentHash,
            long proposalRevision,
            String actor,
            String rationale,
            ReformulationDtos.Revision revision) {
    }

    public record Evidence(
            String projectKey,
            String requirementKey,
            int targetVersionNumber,
            String schemaVersion,
            String payload,
            String evidenceHash,
            String targetTextHash) {
    }

    public record PayloadV2(Payload adoption, List<String> ancestorHashes) {
        public PayloadV2 { ancestorHashes = List.copyOf(ancestorHashes); }
    }

    /** Frozen exact archive and separate prompt-safe projection of its historical decisions. */
    public record LineageSnapshot(List<Evidence> entries, List<String> roots, String decisionContext) {}

    public LineageSnapshot freezeSource(String scopeKey, Long requirementId, Long versionId,
            String projectKey, String requirementKey, int versionNumber, String contentHash) {
        var receipts = adoptions.findByRequirementIdAndTargetVersionIdAndScopeKey(
                requirementId, versionId, scopeKey);
        List<Evidence> roots = new ArrayList<>(sourceEvidenceFor(receipts, scopeKey));
        for (ReformulationPortableEvidence stored : importedEvidence.findMatchingCurrent(
                scopeKey, projectKey, requirementKey, versionNumber, contentHash)) {
            roots.add(fromStored(stored));
        }
        Map<String, Evidence> unique = new LinkedHashMap<>();
        for (Evidence entry : roots) {
            if (!entry.projectKey().equalsIgnoreCase(projectKey)
                    || !entry.requirementKey().equalsIgnoreCase(requirementKey)
                    || entry.targetVersionNumber() != versionNumber
                    || !entry.targetTextHash().equals(contentHash)
                    || !StableIdentityHash.sha256(entry.payload()).equals(entry.evidenceHash())) {
                throw PortfolioException.conflict("Adopted source evidence does not match selected version");
            }
            payload(entry);
            merge(unique, entry);
        }
        if (roots.isEmpty()) return new LineageSnapshot(List.of(), List.of(), "[]");
        for (Evidence ancestor : sourceClosure(receipts, scopeKey)) merge(unique, ancestor);
        var pending = new ArrayList<>(roots);
        for (int index = 0; index < pending.size(); index++) {
            for (String hash : ancestorHashes(pending.get(index))) {
                if (unique.containsKey(hash)) continue;
                var stored = importedEvidence.findByScopeKeyAndEvidenceHash(scopeKey, hash)
                        .orElseThrow(() -> PortfolioException.conflict("Adopted source ancestry closure is missing"));
                Evidence ancestor = fromStored(stored);
                merge(unique, ancestor);
                pending.add(ancestor);
            }
        }
        validateClosure(unique);
        List<String> rootHashes = roots.stream().map(Evidence::evidenceHash).distinct().sorted().toList();
        List<Evidence> entries = unique.values().stream().sorted(Comparator.comparing(Evidence::evidenceHash)).toList();
        List<Object> contexts = new ArrayList<>();
        for (Evidence evidence : entries) {
            Payload payload = payload(evidence);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("historicalEvidenceHash", evidence.evidenceHash());
            context.put("historicalSourceVersion", payload.sourceVersionNumber());
            context.put("historicalTargetVersion", evidence.targetVersionNumber());
            context.put("adoptionActor", payload.actor());
            context.put("adoptionRationale", payload.rationale());
            // The selected current original is already supplied in full. A verbatim
            // historical source statement would inject an obsolete entire original;
            // retain it only in the immutable archive, not the semantic prompt view.
            var applicable = payload.revision().statements().stream()
                    .filter(statement -> !(statement.provenance().isSource()
                            && statement.wording().equals(payload.originalText()))).toList();
            context.put("statements", withoutOldSpans(applicable));
            context.put("questions", withoutOldSpans(payload.revision().questions()));
            context.put("humanAnswers", withoutOldSpans(payload.revision().answers()));
            context.put("historicalReview", withoutOldSpans(payload.revision().validation()));
            contexts.add(context);
        }
        return new LineageSnapshot(entries, rootHashes, json.write(contexts));
    }

    private JsonNode withoutOldSpans(Object value) {
        JsonNode tree = json.readStrictEvidence(json.write(value), JsonNode.class);
        removeOldSpans(tree);
        return tree;
    }

    private static void removeOldSpans(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("sourceSpans");
            object.properties().forEach(property -> removeOldSpans(property.getValue()));
        } else if (node.isArray()) node.forEach(ReformulationEvidenceCodec::removeOldSpans);
    }

    /** Replace only this codec's blocks; all other portfolio/architecture DSL is preserved. */
    public String contributeTo(String dsl, String scopeKey) {
        DocumentAst document = parse(dsl, "reformulation-evidence-contribute.taxdsl");
        List<BlockAst> blocks = new ArrayList<>();
        for (BlockAst block : document.getBlocks()) {
            if (!BLOCK_KIND.equals(block.getKind())) blocks.add(block);
        }

        Map<String, Evidence> byHash = new LinkedHashMap<>();
        if (scopeKey == null || scopeKey.isBlank()) {
            throw PortfolioException.validation("Reformulation evidence requires an exact portfolio scope");
        }
        for (ReformulationPortableEvidence stored : importedEvidence
                .findByScopeKeyOrderByProjectKeyAscRequirementKeyAscTargetVersionNumberAscEvidenceHashAsc(
                        scopeKey)) {
            merge(byHash, fromStored(stored));
        }
        for (Evidence evidence : sourceEvidence(scopeKey)) {
            merge(byHash, evidence);
        }

        byHash.values().stream()
                .sorted(Comparator.comparing(Evidence::projectKey)
                        .thenComparing(Evidence::requirementKey)
                        .thenComparingInt(Evidence::targetVersionNumber)
                        .thenComparing(Evidence::evidenceHash))
                .map(this::block)
                .forEach(blocks::add);
        return serializer.serialize(new DocumentAst(document.getMeta(), blocks));
    }

    /**
     * Parse and validate every evidence block without touching persistence.
     * Call this before ordinary portfolio materialization.
     */
    public List<Evidence> validateForMaterialization(String dsl) {
        DocumentAst document = parse(dsl, "reformulation-evidence-import.taxdsl");
        List<Evidence> result = new ArrayList<>();
        Map<String, Evidence> byHash = new LinkedHashMap<>();
        Set<String> roots = new LinkedHashSet<>();
        for (BlockAst block : document.blocksOfKind(BLOCK_KIND)) {
            Evidence evidence = validate(block, document);
            String root = evidence.projectKey().toLowerCase(java.util.Locale.ROOT) + "\u0000"
                    + evidence.requirementKey().toLowerCase(java.util.Locale.ROOT) + "\u0000"
                    + evidence.targetVersionNumber() + "\u0000" + evidence.evidenceHash();
            if (!roots.add(root)) {
                throw PortfolioException.validation("Duplicate reformulation evidence root");
            }
            Evidence previous = byHash.putIfAbsent(evidence.evidenceHash(), evidence);
            if (previous != null && !previous.equals(evidence)) {
                throw PortfolioException.conflict(
                        "Conflicting reformulation evidence shares the same content hash");
            }
            if (previous == null) result.add(evidence);
        }
        validateClosure(byHash);
        return List.copyOf(result);
    }

    /** Store only already validated imported evidence in the target tenant. */
    public void storeImported(List<Evidence> evidence, String scopeKey) {
        if (evidence == null || evidence.isEmpty()) return;
        if (scopeKey == null || scopeKey.isBlank()) {
            throw PortfolioException.validation("Reformulation evidence requires an exact portfolio scope");
        }
        List<Evidence> ordered = evidence.stream()
                .sorted(Comparator.comparing(Evidence::projectKey)
                        .thenComparing(Evidence::requirementKey)
                        .thenComparingInt(Evidence::targetVersionNumber)
                        .thenComparing(Evidence::evidenceHash))
                .toList();
        for (Evidence item : ordered) {
            ArchitectureProject project = projects
                    .findByScopeKeyAndProjectKeyIgnoreCase(scopeKey, item.projectKey())
                    .orElseThrow(() -> PortfolioException.validation(
                            "Reformulation evidence project was not materialized"));
            requirements.findByProjectIdAndScopeKeyAndRequirementKeyIgnoreCaseForUpdate(
                            project.getId(), scopeKey, item.requirementKey())
                    .orElseThrow(() -> PortfolioException.validation(
                            "Reformulation evidence requirement was not materialized"));

            // The pessimistic requirement-row lock serializes identical evidence imports
            // across application instances before this idempotence check.
            var existing = importedEvidence.findByScopeKeyAndEvidenceHash(
                    scopeKey, item.evidenceHash());
            if (existing.isPresent()) {
                if (!same(existing.get(), item)) {
                    throw PortfolioException.conflict(
                            "Stored reformulation evidence conflicts with imported content");
                }
                continue;
            }
            String id = StableIdentityHash.sha256(scopeKey + "\u0000" + item.evidenceHash());
            importedEvidence.save(new ReformulationPortableEvidence(
                    id,
                    scopeKey,
                    item.projectKey(),
                    item.requirementKey(),
                    item.targetVersionNumber(),
                    item.schemaVersion(),
                    item.evidenceHash(),
                    item.targetTextHash(),
                    item.payload(),
                    Instant.now()));
        }
    }

    private List<Evidence> sourceEvidence(String scopeKey) {
        var rows = adoptions.findByScopeKeyOrderByCreatedAtAsc(scopeKey);
        var result = new ArrayList<>(sourceEvidenceFor(rows, scopeKey));
        result.addAll(sourceClosure(rows, scopeKey));
        return result;
    }

    private List<Evidence> sourceClosure(List<ReformulationAdoption> rows, String scopeKey) {
        var result = new ArrayList<Evidence>();
        for (var row : rows) result.addAll(ancestry(row, scopeKey).entries());
        return result;
    }

    private LineageSnapshot ancestry(ReformulationAdoption receipt, String scopeKey) {
        var proposal = proposals.findById(receipt.getProposalId())
                .orElseThrow(() -> PortfolioException.conflict("Adopted proposal is missing"));
        if (!scopeKey.equals(receipt.getScopeKey()) || !scopeKey.equals(proposal.getScopeKey())
                || !Objects.equals(receipt.getRequirementId(), proposal.getRequirementId())) {
            throw PortfolioException.conflict("Adopted proposal scope is inconsistent");
        }
        ReformulationBaseline baseline = json.read(proposal.getBaselinePayload(), ReformulationBaseline.class);
        PortfolioTenantIdentity tenant = PortfolioTenantIdentity.parse(scopeKey);
        String workspaceId = tenant.workspaceScope().equals(PortfolioTenantIdentity.CENTRAL_SCOPE)
                ? null : tenant.workspaceScope().substring(PortfolioTenantIdentity.WORKSPACE_SCOPE_PREFIX.length());
        if (baseline == null || baseline.scope() == null
                || !tenant.repositoryId().equals(baseline.scope().repositoryId())
                || !Objects.equals(workspaceId, baseline.scope().workspaceId())
                || !tenant.branch().equals(baseline.scope().branch())
                || !Objects.equals(proposal.getProjectId(), baseline.scope().projectId())
                || !Objects.equals(proposal.getRequirementId(), baseline.scope().requirementId())
                || !Objects.equals(proposal.getSourceVersionId(), baseline.sourceVersionId())
                || !Objects.equals(proposal.getSnapshotId(), baseline.snapshotId())) {
            throw PortfolioException.conflict("Adopted proposal baseline does not match physical scope and source");
        }
        String frozen = baseline.frozenContext().get("adoptedLineage");
        if (frozen == null) return new LineageSnapshot(List.of(), List.of(), "[]");
        LineageSnapshot lineage = json.readStrictEvidence(frozen, LineageSnapshot.class);
        if (lineage == null || lineage.roots() == null || lineage.entries() == null
                || lineage.decisionContext() == null) {
            throw PortfolioException.conflict("Adopted proposal ancestry is incomplete");
        }
        return lineage;
    }

    private List<Evidence> sourceEvidenceFor(List<ReformulationAdoption> rows, String scopeKey) {
        if (rows.isEmpty()) return List.of();

        record SourceRow(
                ReformulationAdoption adoption,
                ReformulationAdoptionDtos.Result receipt,
                ReformulationAdoptionDtos.PreviewContent preview) {}

        List<SourceRow> sources = new ArrayList<>(rows.size());
        var sourceVersionIds = new LinkedHashSet<Long>();
        for (ReformulationAdoption adoption : rows) {
            ReformulationAdoptionDtos.Result receipt =
                    json.read(adoption.getPayload(), ReformulationAdoptionDtos.Result.class);
            if (receipt == null
                    || !Objects.equals(adoption.getProposalId(), receipt.proposalId())
                    || !Objects.equals(adoption.getPreviewId(), receipt.previewId())
                    || !Objects.equals(adoption.getTargetVersionId(), receipt.targetVersionId())) {
                throw PortfolioException.conflict(
                        "Stored adoption receipt is inconsistent with its reformulation evidence");
            }
            ReformulationAdoptionPreview previewRow = adoption.getPreview();
            if (previewRow == null
                    || !StableIdentityHash.sha256(previewRow.getPayload())
                            .equals(previewRow.getContentHash())) {
                throw PortfolioException.conflict(
                        "Stored adoption preview integrity check failed");
            }
            ReformulationAdoptionDtos.PreviewContent preview =
                    json.read(previewRow.getPayload(), ReformulationAdoptionDtos.PreviewContent.class);
            if (preview == null || preview.currentRequirement() == null
                    || preview.currentRequirement().currentVersion() == null
                    || preview.revision() == null) {
                throw PortfolioException.conflict(
                        "Adoption preview is incomplete for portable reformulation evidence");
            }
            sourceVersionIds.add(preview.sourceVersionId());
            sources.add(new SourceRow(adoption, receipt, preview));
        }

        Map<Long, ProjectRequirementVersion> sourceVersions = new LinkedHashMap<>();
        for (ProjectRequirementVersion source :
                versions.findByScopeKeyAndIdIn(scopeKey, sourceVersionIds)) {
            sourceVersions.put(source.getId(), source);
        }

        List<Evidence> result = new ArrayList<>(sources.size());
        for (SourceRow row : sources) {
            ReformulationAdoption adoption = row.adoption();
            ReformulationAdoptionDtos.Result receipt = row.receipt();
            ReformulationAdoptionDtos.PreviewContent preview = row.preview();

            ProjectRequirementVersion target = adoption.getVersion();
            ProjectRequirement requirement = target == null ? null : target.getRequirement();
            ArchitectureProject project = requirement == null ? null : requirement.getProject();
            ProjectRequirementVersion source = sourceVersions.get(preview.sourceVersionId());
            if (target == null || requirement == null || project == null
                    || source == null
                    || !scopeKey.equals(target.getScopeKey())
                    || !scopeKey.equals(requirement.getScopeKey())
                    || !scopeKey.equals(project.getScopeKey())
                    || !scopeKey.equals(source.getScopeKey())
                    || !Objects.equals(adoption.getRequirementId(), requirement.getId())
                    || !Objects.equals(source.getRequirementId(), requirement.getId())) {
                throw PortfolioException.conflict(
                        "Adoption business identity is inconsistent with portable reformulation evidence");
            }

            int previousVersion = preview.currentRequirement().currentVersion().versionNumber();
            if (target.getVersionNumber() != receipt.targetVersionNumber()
                    || receipt.previousVersionId()
                    != preview.currentRequirement().currentVersionId()
                    || !preview.originalText().equals(source.getText())
                    || !StableIdentityHash.sha256(preview.originalText()).equals(source.getContentHash())) {
                throw PortfolioException.conflict(
                        "Adoption version binding is inconsistent with portable reformulation evidence");
            }

            Payload value = new Payload(
                    project.getProjectKey(),
                    requirement.getRequirementKey(),
                    source.getVersionNumber(),
                    previousVersion,
                    target.getVersionNumber(),
                    preview.analysisSnapshotId(),
                    preview.originalText(),
                    preview.finalText(),
                    target.getContentHash(),
                    receipt.proposalRevision(),
                    receipt.actor(),
                    receipt.rationale(),
                    preview.revision());
            LineageSnapshot ancestry = ancestry(adoption, scopeKey);
            if (!Objects.equals(preview.sourceVersionId(),
                    proposals.findById(adoption.getProposalId()).orElseThrow().getSourceVersionId())) {
                throw PortfolioException.conflict("Adopted source baseline does not match preview");
            }
            for (String ancestor : ancestry.roots()) {
                Evidence parent = ancestry.entries().stream().filter(e -> e.evidenceHash().equals(ancestor))
                        .findFirst().orElseThrow(() -> PortfolioException.conflict("Adopted source ancestry is missing"));
                if (parent.targetVersionNumber() != source.getVersionNumber()
                        || !parent.targetTextHash().equals(source.getContentHash())
                        || !parent.projectKey().equalsIgnoreCase(project.getProjectKey())
                        || !parent.requirementKey().equalsIgnoreCase(requirement.getRequirementKey())) {
                    throw PortfolioException.conflict("Adopted source ancestry does not match source version");
                }
            }
            String payload = ancestry.roots().isEmpty() ? json.write(value)
                    : json.write(new PayloadV2(value, ancestry.roots()));
            String hash = StableIdentityHash.sha256(payload);
            result.add(new Evidence(
                    value.projectKey(),
                    value.requirementKey(),
                    value.targetVersionNumber(),
                    ancestry.roots().isEmpty() ? CURRENT_SCHEMA : ANCESTRY_SCHEMA,
                    payload,
                    hash,
                    value.targetContentHash()));
        }
        return result;
    }

    private Evidence validate(BlockAst block, DocumentAst document) {
        if (block.getHeaderTokens().size() != 4) {
            throw PortfolioException.validation(
                    "Reformulation evidence requires project, requirement, target version and evidence hash");
        }
        Set<String> expectedProperties = Set.of("schemaVersion", "payload", "evidenceHash", "targetTextHash");
        Set<String> observedProperties = new LinkedHashSet<>();
        for (PropertyAst property : block.getProperties()) observedProperties.add(property.key());
        if (block.getProperties().size() != expectedProperties.size()
                || !observedProperties.equals(expectedProperties)
                || !block.getChildren().isEmpty()
                || !block.getExtensions().isEmpty()) {
            throw PortfolioException.validation("Reformulation evidence DSL schema is ambiguous");
        }
        String projectKey = requiredToken(block, 0);
        String requirementKey = requiredToken(block, 1);
        int targetVersion = positiveInt(requiredToken(block, 2));
        String headerHash = requiredToken(block, 3);
        String schema = required(block, "schemaVersion");
        if (!READABLE_SCHEMAS.contains(schema)) {
            throw PortfolioException.validation(
                    "Unsupported reformulation evidence schema: " + schema);
        }
        String payload = required(block, "payload");
        String evidenceHash = required(block, "evidenceHash");
        String targetTextHash = required(block, "targetTextHash");
        if (!hex64(headerHash) || !headerHash.equals(evidenceHash)
                || !StableIdentityHash.sha256(payload).equals(evidenceHash)) {
            throw PortfolioException.validation(
                    "Reformulation evidence integrity check failed");
        }
        if (!hex64(targetTextHash)) {
            throw PortfolioException.validation(
                    "Reformulation evidence target requirement version hash is invalid");
        }

        Evidence evidence = new Evidence(projectKey, requirementKey, targetVersion,
                schema, payload, evidenceHash, targetTextHash);
        Payload decoded;
        try {
            decoded = payload(evidence);
        } catch (PortfolioException invalid) {
            throw new PortfolioException(
                    PortfolioException.Kind.VALIDATION,
                    "Reformulation evidence integrity payload is invalid",
                    invalid);
        }
        if (decoded == null
                || decoded.revision() == null
                || decoded.sourceVersionNumber() <= 0
                || decoded.previousActiveVersionNumber() <= 0
                || decoded.targetVersionNumber() <= 0
                || decoded.proposalRevision() <= 0
                || blank(decoded.analysisSnapshotId())
                || decoded.originalText() == null
                || decoded.finalText() == null
                || blank(decoded.actor())
                || blank(decoded.rationale())
                || !projectKey.equals(decoded.projectKey())
                || !requirementKey.equals(decoded.requirementKey())
                || targetVersion != decoded.targetVersionNumber()) {
            throw PortfolioException.validation(
                    "Reformulation evidence integrity payload does not match its business identity");
        }
        if (!targetTextHash.equals(decoded.targetContentHash())) {
            throw PortfolioException.validation(
                    "Reformulation evidence does not match target requirement version");
        }

        List<BlockAst> sourceBlocks = document.blocksOfKind(PortfolioGitService.VERSION_BLOCK)
                .stream()
                .filter(candidate -> candidate.getHeaderTokens().size() >= 3)
                .filter(candidate -> projectKey.equalsIgnoreCase(candidate.getHeaderTokens().get(0)))
                .filter(candidate -> requirementKey.equalsIgnoreCase(candidate.getHeaderTokens().get(1)))
                .filter(candidate -> Integer.toString(decoded.sourceVersionNumber())
                        .equals(candidate.getHeaderTokens().get(2)))
                .toList();
        if (sourceBlocks.size() != 1
                || !decoded.originalText().equals(sourceBlocks.getFirst().property("text"))
                || !StableIdentityHash.sha256(decoded.originalText())
                        .equals(sourceBlocks.getFirst().property("contentHash"))) {
            throw PortfolioException.validation(
                    "Reformulation evidence does not match source requirement version");
        }

        List<BlockAst> targetBlocks = document.blocksOfKind(PortfolioGitService.VERSION_BLOCK)
                .stream()
                .filter(candidate -> candidate.getHeaderTokens().size() >= 3)
                .filter(candidate -> projectKey.equalsIgnoreCase(candidate.getHeaderTokens().get(0)))
                .filter(candidate -> requirementKey.equalsIgnoreCase(candidate.getHeaderTokens().get(1)))
                .filter(candidate -> Integer.toString(targetVersion)
                        .equals(candidate.getHeaderTokens().get(2)))
                .toList();
        if (targetBlocks.size() != 1
                || !targetTextHash.equals(targetBlocks.getFirst().property("contentHash"))
                || !decoded.finalText().equals(targetBlocks.getFirst().property("text"))) {
            throw PortfolioException.validation(
                    "Reformulation evidence does not match target requirement version");
        }

        return evidence;
    }

    public Payload payload(Evidence evidence) {
        if (!READABLE_SCHEMAS.contains(evidence.schemaVersion()))
            throw PortfolioException.validation(
                    "Unsupported reformulation evidence schema: " + evidence.schemaVersion());
        if (ANCESTRY_SCHEMA.equals(evidence.schemaVersion())) {
            PayloadV2 value = json.readStrictEvidence(evidence.payload(), PayloadV2.class);
            if (value == null || value.adoption() == null || value.ancestorHashes() == null
                    || value.ancestorHashes().isEmpty())
                throw PortfolioException.validation("Reformulation evidence ancestry is incomplete");
            return value.adoption();
        }
        return json.readStrictEvidence(evidence.payload(), Payload.class);
    }

    private List<String> ancestorHashes(Evidence evidence) {
        if (!ANCESTRY_SCHEMA.equals(evidence.schemaVersion())) return List.of();
        PayloadV2 value = json.readStrictEvidence(evidence.payload(), PayloadV2.class);
        if (value == null || value.ancestorHashes() == null || value.ancestorHashes().isEmpty()
                || new LinkedHashSet<>(value.ancestorHashes()).size() != value.ancestorHashes().size()
                || value.ancestorHashes().stream().anyMatch(hash -> !hex64(hash)))
            throw PortfolioException.validation("Reformulation evidence ancestry references are invalid");
        return value.ancestorHashes();
    }

    private void validateClosure(Map<String, Evidence> entries) {
        var complete = new LinkedHashSet<String>();
        for (String hash : entries.keySet()) visit(hash, entries, new LinkedHashSet<>(), complete);
    }

    private void visit(String hash, Map<String, Evidence> entries, Set<String> visiting, Set<String> complete) {
        if (complete.contains(hash)) return;
        Evidence child = entries.get(hash);
        if (child == null || !StableIdentityHash.sha256(child.payload()).equals(hash))
            throw PortfolioException.validation("Reformulation evidence ancestry closure or hash is invalid");
        if (!visiting.add(hash)) throw PortfolioException.validation("Reformulation evidence ancestry cycle");
        Payload current = payload(child);
        for (String parentHash : ancestorHashes(child)) {
            Evidence parent = entries.get(parentHash);
            if (parent == null) throw PortfolioException.validation("Reformulation evidence ancestry closure is missing");
            Payload previous = payload(parent);
            if (!child.projectKey().equalsIgnoreCase(parent.projectKey())
                    || !child.requirementKey().equalsIgnoreCase(parent.requirementKey())
                    || current.sourceVersionNumber() != parent.targetVersionNumber()
                    || !StableIdentityHash.sha256(current.originalText()).equals(parent.targetTextHash())
                    || !current.originalText().equals(previous.finalText()))
                throw PortfolioException.validation("Reformulation evidence ancestry identity mismatch");
            visit(parentHash, entries, visiting, complete);
        }
        visiting.remove(hash);
        complete.add(hash);
    }

    private BlockAst block(Evidence evidence) {
        return new BlockAst(
                BLOCK_KIND,
                List.of(
                        evidence.projectKey(),
                        evidence.requirementKey(),
                        Integer.toString(evidence.targetVersionNumber()),
                        evidence.evidenceHash()),
                List.of(
                        property("schemaVersion", evidence.schemaVersion()),
                        property("payload", evidence.payload()),
                        property("evidenceHash", evidence.evidenceHash()),
                        property("targetTextHash", evidence.targetTextHash())),
                List.of(),
                Map.of(),
                GENERATED);
    }

    private static void merge(Map<String, Evidence> byHash, Evidence evidence) {
        Evidence previous = byHash.putIfAbsent(evidence.evidenceHash(), evidence);
        if (previous != null && !previous.equals(evidence)) {
            throw PortfolioException.conflict(
                    "Conflicting reformulation evidence shares the same content hash");
        }
    }

    private static Evidence fromStored(ReformulationPortableEvidence stored) {
        return new Evidence(
                stored.getProjectKey(),
                stored.getRequirementKey(),
                stored.getTargetVersionNumber(),
                stored.getSchemaVersion(),
                stored.getPayload(),
                stored.getEvidenceHash(),
                stored.getTargetTextHash());
    }

    private static boolean same(
            ReformulationPortableEvidence stored,
            Evidence evidence) {
        return stored.getProjectKey().equals(evidence.projectKey())
                && stored.getRequirementKey().equals(evidence.requirementKey())
                && stored.getTargetVersionNumber() == evidence.targetVersionNumber()
                && stored.getSchemaVersion().equals(evidence.schemaVersion())
                && stored.getEvidenceHash().equals(evidence.evidenceHash())
                && stored.getTargetTextHash().equals(evidence.targetTextHash())
                && stored.getPayload().equals(evidence.payload());
    }

    private DocumentAst parse(String dsl, String sourceName) {
        if (dsl == null || dsl.isBlank()) {
            return new DocumentAst(
                    new MetaAst(MetaAst.LANGUAGE_ID, MetaAst.CURRENT_VERSION, "portfolio", GENERATED),
                    List.of());
        }
        return parser.parse(dsl, sourceName);
    }

    private static PropertyAst property(String key, String value) {
        return new PropertyAst(key, value, GENERATED);
    }

    private static String required(BlockAst block, String key) {
        String value = block.property(key);
        if (value == null || value.isBlank()) {
            throw PortfolioException.validation(
                    "Reformulation evidence is missing " + key);
        }
        return value;
    }

    private static String requiredToken(BlockAst block, int index) {
        String value = block.getHeaderTokens().get(index);
        if (value == null || value.isBlank()) {
            throw PortfolioException.validation(
                    "Reformulation evidence contains a blank identity token");
        }
        return value;
    }

    private static int positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // Handled by the shared validation error below.
        }
        throw PortfolioException.validation(
                "Reformulation evidence target version must be positive");
    }

    private static boolean hex64(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
