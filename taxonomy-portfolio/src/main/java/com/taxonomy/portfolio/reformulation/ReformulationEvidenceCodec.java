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
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final Set<String> READABLE_SCHEMAS = Set.of(CURRENT_SCHEMA, "1");
    private static final SourceLocation GENERATED =
            new SourceLocation("reformulation-evidence-projection", 1, 1);

    private final PortfolioJsonCodec json;
    private final ReformulationAdoptionRepository adoptions;
    private final ReformulationAdoptionPreviewRepository previews;
    private final ReformulationPortableEvidenceRepository importedEvidence;
    private final ArchitectureProjectRepository projects;
    private final ProjectRequirementRepository requirements;
    private final ProjectRequirementVersionRepository versions;
    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    public ReformulationEvidenceCodec(
            PortfolioJsonCodec json,
            ReformulationAdoptionRepository adoptions,
            ReformulationAdoptionPreviewRepository previews,
            ReformulationPortableEvidenceRepository importedEvidence,
            ArchitectureProjectRepository projects,
            ProjectRequirementRepository requirements,
            ProjectRequirementVersionRepository versions) {
        this.json = json;
        this.adoptions = adoptions;
        this.previews = previews;
        this.importedEvidence = importedEvidence;
        this.projects = projects;
        this.requirements = requirements;
        this.versions = versions;
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

    /** Replace only this codec's blocks; all other portfolio/architecture DSL is preserved. */
    public String contributeTo(String dsl, String username, WorkspaceContext context) {
        DocumentAst document = parse(dsl, "reformulation-evidence-contribute.taxdsl");
        List<BlockAst> blocks = new ArrayList<>();
        for (BlockAst block : document.getBlocks()) {
            if (!BLOCK_KIND.equals(block.getKind())) blocks.add(block);
        }

        Map<String, Evidence> byHash = new LinkedHashMap<>();
        String scopeKey = PortfolioScope.key(username, context);
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
        for (BlockAst block : document.blocksOfKind(BLOCK_KIND)) {
            Evidence evidence = validate(block, document);
            Evidence previous = byHash.putIfAbsent(evidence.evidenceHash(), evidence);
            if (previous != null && !previous.equals(evidence)) {
                throw PortfolioException.conflict(
                        "Conflicting reformulation evidence shares the same content hash");
            }
            if (previous == null) result.add(evidence);
        }
        return List.copyOf(result);
    }

    /** Store only already validated imported evidence in the target tenant. */
    public void storeImported(
            List<Evidence> evidence,
            String username,
            WorkspaceContext context) {
        if (evidence == null || evidence.isEmpty()) return;
        String scopeKey = PortfolioScope.key(username, context);
        for (Evidence item : evidence) {
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
        List<Evidence> result = new ArrayList<>();
        for (ReformulationAdoption adoption :
                adoptions.findByScopeKeyOrderByCreatedAtAsc(scopeKey)) {
            ReformulationAdoptionDtos.Result receipt =
                    json.read(adoption.getPayload(), ReformulationAdoptionDtos.Result.class);
            if (receipt == null
                    || !Objects.equals(adoption.getProposalId(), receipt.proposalId())
                    || !Objects.equals(adoption.getPreviewId(), receipt.previewId())
                    || !Objects.equals(adoption.getTargetVersionId(), receipt.targetVersionId())) {
                throw PortfolioException.conflict(
                        "Stored adoption receipt is inconsistent with its reformulation evidence");
            }
            ReformulationAdoptionPreview previewRow = previews
                    .findByIdAndProposalIdAndScopeKey(
                            adoption.getPreviewId(), adoption.getProposalId(), scopeKey)
                    .orElseThrow(() -> PortfolioException.conflict(
                            "Adoption preview is missing for portable reformulation evidence"));
            if (!StableIdentityHash.sha256(previewRow.getPayload())
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

            Long projectId = preview.currentRequirement().projectId();
            ArchitectureProject project = projects.findByIdAndScopeKey(projectId, scopeKey)
                    .orElseThrow(() -> PortfolioException.conflict(
                            "Adoption project is missing for portable reformulation evidence"));
            ProjectRequirement requirement = requirements
                    .findByIdAndProjectIdAndScopeKey(
                            adoption.getRequirementId(), projectId, scopeKey)
                    .orElseThrow(() -> PortfolioException.conflict(
                            "Adoption requirement is missing for portable reformulation evidence"));
            ProjectRequirementVersion source = versions
                    .findByIdAndRequirementIdAndScopeKey(
                            preview.sourceVersionId(), requirement.getId(), scopeKey)
                    .orElseThrow(() -> PortfolioException.conflict(
                            "Adoption source version is missing for portable reformulation evidence"));
            ProjectRequirementVersion target = versions
                    .findByIdAndRequirementIdAndScopeKey(
                            adoption.getTargetVersionId(), requirement.getId(), scopeKey)
                    .orElseThrow(() -> PortfolioException.conflict(
                            "Adoption target version is missing for portable reformulation evidence"));
            int previousVersion = preview.currentRequirement().currentVersion().versionNumber();
            if (target.getVersionNumber() != receipt.targetVersionNumber()
                    || receipt.previousVersionId()
                    != preview.currentRequirement().currentVersionId()) {
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
            String payload = json.write(value);
            String hash = StableIdentityHash.sha256(payload);
            result.add(new Evidence(
                    value.projectKey(),
                    value.requirementKey(),
                    value.targetVersionNumber(),
                    CURRENT_SCHEMA,
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

        Payload decoded;
        try {
            decoded = json.read(payload, Payload.class);
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
                || targetVersion != decoded.targetVersionNumber()
                || !targetTextHash.equals(decoded.targetContentHash())) {
            throw PortfolioException.validation(
                    "Reformulation evidence integrity payload does not match its business identity");
        }

        List<BlockAst> targetBlocks = document.blocksOfKind(PortfolioGitService.VERSION_BLOCK)
                .stream()
                .filter(candidate -> candidate.getHeaderTokens().size() >= 3)
                .filter(candidate -> projectKey.equals(candidate.getHeaderTokens().get(0)))
                .filter(candidate -> requirementKey.equals(candidate.getHeaderTokens().get(1)))
                .filter(candidate -> Integer.toString(targetVersion)
                        .equals(candidate.getHeaderTokens().get(2)))
                .toList();
        if (targetBlocks.size() != 1
                || !targetTextHash.equals(targetBlocks.getFirst().property("contentHash"))
                || !decoded.finalText().equals(targetBlocks.getFirst().property("text"))) {
            throw PortfolioException.validation(
                    "Reformulation evidence does not match target requirement version");
        }

        return new Evidence(
                projectKey,
                requirementKey,
                targetVersion,
                schema,
                payload,
                evidenceHash,
                targetTextHash);
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
