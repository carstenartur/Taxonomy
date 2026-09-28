package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.service.PortablePortfolioGitService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioDocumentPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "llm.mock=true")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@WithMockUser(username = "architect", roles = "ARCHITECT")
class ReformulationEvidenceRoundTripTest extends ReformulationWorkflowFixture {
    private static final String EVIDENCE_BLOCK = "reformulationEvidence";
    private static final String CURRENT_SCHEMA = "reformulation-evidence-v1";

    @Autowired PortablePortfolioGitService git;
    @Autowired ReformulationAdoptionService adoptions;
    @Autowired WorkspacePortfolioDocumentPort documents;

    private final TaxDslParser parser = new TaxDslParser();
    private final TaxDslSerializer serializer = new TaxDslSerializer();

    @Test
    void adoptedEvidenceJoinsTheSameCheckpointAndRoundTripsByBusinessIdentity() throws Exception {
        String dsl = adoptAndExport();
        BlockAst evidence = onlyEvidence(dsl);

        assertThat(evidence.getHeaderTokens()).hasSize(4);
        assertThat(evidence.getHeaderTokens().subList(0, 3))
                .containsExactly("P", "R", "2");
        assertThat(evidence.property("schemaVersion")).isEqualTo(CURRENT_SCHEMA);

        String payload = evidence.property("payload");
        String evidenceHash = evidence.property("evidenceHash");
        assertThat(evidence.getHeaderTokens().get(3)).isEqualTo(evidenceHash);
        assertThat(evidenceHash).isEqualTo(StableIdentityHash.sha256(payload));
        assertThat(evidence.property("targetTextHash")).isNotBlank();

        var content = json.readTree(payload);
        assertThat(content.path("projectKey").asText()).isEqualTo("P");
        assertThat(content.path("requirementKey").asText()).isEqualTo("R");
        assertThat(content.path("sourceVersionNumber").asInt()).isEqualTo(1);
        assertThat(content.path("previousActiveVersionNumber").asInt()).isEqualTo(1);
        assertThat(content.path("targetVersionNumber").asInt()).isEqualTo(2);
        assertThat(content.path("analysisSnapshotId").asText()).isEqualTo(snapshot);
        assertThat(content.path("originalText").asText()).isEqualTo(ORIGINAL);
        assertThat(content.path("finalText").asText())
                .isEqualTo("Arbeitsbeginn und Ende erfassen.\n\nUnabhängige Abrechnung.");
        assertThat(content.path("targetContentHash").asText())
                .isEqualTo(evidence.property("targetTextHash"));
        assertThat(content.path("proposalRevision").asLong()).isEqualTo(2);
        assertThat(content.path("actor").asText()).isEqualTo("architect");
        assertThat(content.path("rationale").asText()).isEqualTo("Adopt reviewed draft");
        assertThat(content.path("revision").path("questions")).isNotEmpty();

        for (String local : List.of("projectId", "requirementId", "sourceVersionId",
                "previousVersionId", "targetVersionId", "proposalId", "previewId")) {
            assertThat(content.has(local)).as(local).isFalse();
        }

        var repository = documents.resolveRepository(context);
        var commit = git.commit(context.currentBranch(), "Checkpoint adopted reformulation",
                "architect", context);
        assertThat(commit.changed()).isTrue();
        String committed = repository.getDslAtCommit(commit.commitId());
        BlockAst committedEvidence = onlyEvidence(committed);
        assertThat(committedEvidence.property("evidenceHash")).isEqualTo(evidenceHash);
        assertThat(committedEvidence.property("payload")).isEqualTo(payload);
        assertThat(committed).contains("requirementVersion P R 2");

        WorkspaceContext target = newWorkspace("Portable import");
        var result = git.materialize(dsl, "architect", target);
        assertThat(result.warnings()).isEmpty();

        String roundTrip = git.exportPortfolio("architect", target);
        BlockAst imported = onlyEvidence(roundTrip);
        assertThat(imported.getHeaderTokens()).isEqualTo(evidence.getHeaderTokens());
        assertThat(imported.property("schemaVersion")).isEqualTo(CURRENT_SCHEMA);
        assertThat(imported.property("evidenceHash")).isEqualTo(evidenceHash);
        assertThat(imported.property("payload")).isEqualTo(payload);

        var targetProjects = projects.listProjects("architect", target);
        var targetRequirements = projects.listRequirements(targetProjects.getFirst().id(), "architect", target);
        assertThat(targetProjects.getFirst().id()).isNotEqualTo(project.id());
        assertThat(targetRequirements.getFirst().id()).isNotEqualTo(requirement.id());
        assertThat(targetRequirements.getFirst().currentVersion().versionNumber()).isEqualTo(2);
        assertThat(targetRequirements.getFirst().currentVersion().text())
                .isEqualTo(content.path("finalText").asText());

        git.materialize(dsl, "architect", target);
        assertThat(evidenceBlocks(git.exportPortfolio("architect", target))).hasSize(1);
    }

    @Test
    void legacySchemaAliasIsReadableButUnknownSchemasFailClosed() throws Exception {
        String dsl = adoptAndExport();
        BlockAst original = onlyEvidence(dsl);

        WorkspaceContext legacyTarget = newWorkspace("Legacy evidence import");
        String legacy = replaceEvidenceProperty(dsl, "schemaVersion", "1");
        git.materialize(legacy, "architect", legacyTarget);
        BlockAst legacyRoundTrip = onlyEvidence(git.exportPortfolio("architect", legacyTarget));
        assertThat(legacyRoundTrip.property("schemaVersion")).isEqualTo("1");
        assertThat(legacyRoundTrip.property("payload")).isEqualTo(original.property("payload"));
        assertThat(legacyRoundTrip.property("evidenceHash")).isEqualTo(original.property("evidenceHash"));

        WorkspaceContext futureTarget = newWorkspace("Future evidence rejected");
        String future = replaceEvidenceProperty(dsl, "schemaVersion", "reformulation-evidence-v99");
        assertThatThrownBy(() -> git.materialize(future, "architect", futureTarget))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Unsupported reformulation evidence schema");
        assertThat(projects.listProjects("architect", futureTarget)).isEmpty();
    }

    @Test
    void contentHashAndTargetVersionBindingAreVerifiedBeforeImport() throws Exception {
        String dsl = adoptAndExport();
        BlockAst original = onlyEvidence(dsl);

        WorkspaceContext hashTarget = newWorkspace("Tampered evidence rejected");
        String tamperedPayload = replaceEvidenceProperty(
                dsl, "payload", original.property("payload") + " ");
        assertThatThrownBy(() -> git.materialize(tamperedPayload, "architect", hashTarget))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("integrity");
        assertThat(projects.listProjects("architect", hashTarget)).isEmpty();

        WorkspaceContext versionTarget = newWorkspace("Wrong target hash rejected");
        String tamperedTarget = replaceEvidenceProperty(
                dsl, "targetTextHash", "0".repeat(64));
        assertThatThrownBy(() -> git.materialize(tamperedTarget, "architect", versionTarget))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("target requirement version");
        assertThat(projects.listProjects("architect", versionTarget)).isEmpty();
    }

    @Test
    void duplicatePortableRootIsRejectedBeforeMaterialization() throws Exception {
        String dsl = adoptAndExport();
        DocumentAst document = parser.parse(dsl, "duplicate-root.taxdsl");
        List<BlockAst> blocks = new ArrayList<>(document.getBlocks());
        blocks.add(onlyEvidence(dsl));
        String duplicate = serializer.serialize(new DocumentAst(document.getMeta(), blocks));
        WorkspaceContext target = newWorkspace("Duplicate evidence root rejected");

        assertThatThrownBy(() -> git.materialize(duplicate, "architect", target))
                .isInstanceOf(PortfolioException.class);
        assertThat(projects.listProjects("architect", target)).isEmpty();
    }

    @Test
    void checksumValidDuplicateJsonKeyIsRejectedBeforeMaterialization() throws Exception {
        String dsl = adoptAndExport();
        String original = onlyEvidence(dsl).property("payload");
        String duplicate = original.replaceFirst("\\\"projectKey\\\":\\\"P\\\"",
                "\\\"projectKey\\\":\\\"P\\\",\\\"projectKey\\\":\\\"P\\\"");
        assertThat(duplicate).isNotEqualTo(original);
        WorkspaceContext target = newWorkspace("Duplicate JSON key rejected");

        assertThatThrownBy(() -> git.materialize(withEvidencePayload(dsl, duplicate), "architect", target))
                .isInstanceOf(PortfolioException.class);
        assertThat(projects.listProjects("architect", target)).isEmpty();
    }

    @Test
    void checksumValidTrailingJsonTokenIsRejectedBeforeMaterialization() throws Exception {
        String dsl = adoptAndExport();
        WorkspaceContext target = newWorkspace("Trailing JSON token rejected");

        assertThatThrownBy(() -> git.materialize(withEvidencePayload(
                dsl, onlyEvidence(dsl).property("payload") + " {}"), "architect", target))
                .isInstanceOf(PortfolioException.class);
        assertThat(projects.listProjects("architect", target)).isEmpty();
    }

    @Test
    void checksumValidV1SourceTextMismatchRejectsBeforeMaterialization() throws Exception {
        String dsl = adoptAndExport();
        String original = onlyEvidence(dsl).property("payload");
        // Keep the schema shape exact: change just the value, not add a field.
        var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        tree.put("originalText", "Forged old source");
        String changed = json.writeValueAsString(tree);
        assertThat(changed).isNotEqualTo(original);
        WorkspaceContext target = newWorkspace("Mismatched v1 source rejected");

        String tampered = withEvidencePayload(dsl, changed);
        assertThatThrownBy(() -> git.materialize(tampered, "architect", target))
                .isInstanceOf(PortfolioException.class);
        assertThat(projects.listProjects("architect", target)).isEmpty();
    }

    @Test
    void checksumValidUnexpectedV1FieldRejectsBeforeMaterialization() throws Exception {
        String dsl = adoptAndExport();
        String original = onlyEvidence(dsl).property("payload");
        String extra = original.replaceFirst("\\\"projectKey\\\":", "\\\"unexpectedField\\\":true,\\\"projectKey\\\":");
        WorkspaceContext target = newWorkspace("Unexpected evidence field rejected");
        assertThatThrownBy(() -> git.materialize(withEvidencePayload(dsl, extra), "architect", target))
                .isInstanceOf(PortfolioException.class);
        assertThat(projects.listProjects("architect", target)).isEmpty();
    }

    @Test
    void duplicateOrUnknownEvidenceDslPropertyRejectsBeforeMaterialization() throws Exception {
        String dsl = adoptAndExport();
        for (String propertyKey : List.of("payload", "unexpectedEvidenceProperty")) {
            DocumentAst parsed = parser.parse(dsl, "duplicate-evidence-property.taxdsl");
            String ambiguous = serializer.serialize(new DocumentAst(parsed.getMeta(), parsed.getBlocks().stream()
                    .map(block -> {
                        if (!EVIDENCE_BLOCK.equals(block.getKind())) return block;
                        var properties = new ArrayList<>(block.getProperties());
                        properties.add(new PropertyAst(propertyKey, "{}", block.getSourceLocation()));
                        return new BlockAst(block.getKind(), block.getHeaderTokens(), properties,
                                block.getChildren(), block.getExtensions(), block.getSourceLocation());
                    }).toList()));
            WorkspaceContext target = newWorkspace("Ambiguous DSL property rejected");
            assertThatThrownBy(() -> git.materialize(ambiguous, "architect", target))
                    .isInstanceOf(PortfolioException.class);
            assertThat(projects.listProjects("architect", target)).isEmpty();
        }
    }

    @Test
    void localAdoptedSourceFreezesExactEvidenceAndConcreteDecisionsAtOfferCreation() throws Exception {
        String dsl = adoptAndExport();
        BlockAst sourceEvidence = onlyEvidence(dsl);
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        String adoptedSnapshot = snapshot(requirement);

        var offer = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), adoptedSnapshot, "de"),
                "architect", context);
        var frozen = offer.baseline().frozenContext();
        assertThat(frozen.get("adoptedLineage")).contains(sourceEvidence.property("evidenceHash"));
        assertThat(frozen.get("inheritedDecisionContext"))
                .contains("Arbeitsbeginn und Ende erfassen.")
                .contains("MODEL_ADDITION")
                .contains("Question channel")
                .contains("Adopt reviewed draft")
                .contains("architect");
    }

    @Test
    void importedAdoptedSourceFreezesPortableEvidenceWithPhysicalCanonicalKeys() throws Exception {
        String dsl = adoptAndExport();
        WorkspaceContext target = newWorkspace("Imported lineage source");
        git.materialize(dsl, "architect", target);
        select(target);
        var importedProject = projects.listProjects("architect", target).getFirst();
        var importedRequirement = projects.listRequirements(importedProject.id(), "architect", target).getFirst();
        String adoptedSnapshot = snapshotFor(importedProject.id(), importedRequirement, target);

        var offer = reformulations.create(importedProject.id(), importedRequirement.id(),
                new ReformulationDtos.CreateRequest(importedRequirement.currentVersionId(), adoptedSnapshot, "de"),
                "architect", target);
        assertThat(offer.baseline().frozenContext().get("adoptedLineage"))
                .contains(onlyEvidence(dsl).property("evidenceHash"));
        assertThat(offer.baseline().frozenContext().get("inheritedDecisionContext"))
                .contains("Question channel").contains("Adopt reviewed draft");
    }

    @Test
    void lowercasePortableIdentityBindsToPhysicalCanonicalKeysWithoutRewritingEvidenceBytes() throws Exception {
        String dsl = adoptAndExport();
        BlockAst original = onlyEvidence(dsl);
        String payload = original.property("payload")
                .replaceFirst("\\\"projectKey\\\":\\\"P\\\"", "\\\"projectKey\\\":\\\"p\\\"")
                .replaceFirst("\\\"requirementKey\\\":\\\"R\\\"", "\\\"requirementKey\\\":\\\"r\\\"");
        assertThat(payload).isNotEqualTo(original.property("payload"));
        String hash = StableIdentityHash.sha256(payload);
        DocumentAst document = parser.parse(withEvidencePayload(dsl, payload), "lowercase-evidence.taxdsl");
        String portable = serializer.serialize(new DocumentAst(document.getMeta(), document.getBlocks().stream()
                .map(block -> EVIDENCE_BLOCK.equals(block.getKind())
                        ? new BlockAst(block.getKind(), List.of("p", "r", "2", hash),
                                block.getProperties(), block.getChildren(), block.getExtensions(), block.getSourceLocation())
                        : block).toList()));
        WorkspaceContext target = newWorkspace("Lowercase evidence identity");
        git.materialize(portable, "architect", target);
        BlockAst retained = onlyEvidence(git.exportPortfolio("architect", target));
        assertThat(retained.property("payload")).isEqualTo(payload);
        assertThat(retained.property("evidenceHash")).isEqualTo(hash);
        assertThat(retained.getHeaderTokens().subList(0, 2)).containsExactly("p", "r");

        select(target);
        var physicalProject = projects.listProjects("architect", target).getFirst();
        var physicalRequirement = projects.listRequirements(physicalProject.id(), "architect", target).getFirst();
        assertThat(physicalProject.projectKey()).isEqualTo("P");
        assertThat(physicalRequirement.requirementKey()).isEqualTo("R");
        var offer = reformulations.create(physicalProject.id(), physicalRequirement.id(),
                new ReformulationDtos.CreateRequest(physicalRequirement.currentVersionId(),
                        snapshotFor(physicalProject.id(), physicalRequirement, target), "de"),
                "architect", target);
        assertThat(offer.baseline().frozenContext().get("adoptedLineage")).contains(hash);
    }

    @Test
    void historicalFullSourceStatementRemainsArchivedButIsNotPromptContext() throws Exception {
        documentTransform = document -> {
            var source = new com.taxonomy.reformulation.Statement("old-whole-source", ORIGINAL,
                    List.of(new com.taxonomy.reformulation.Statement.SourceSpan(0, ORIGINAL.length(), ORIGINAL)),
                    com.taxonomy.reformulation.Statement.Provenance.ORIGINAL, List.of(), List.of(), null,
                    com.taxonomy.reformulation.Statement.EditingOrigin.SOURCE, "UNREVIEWED");
            var statements = new ArrayList<>(document.statements());
            statements.add(source);
            return new com.taxonomy.reformulation.ReformulationDocument(document.text(),
                    document.sections(), statements, document.questions(), document.validation(), document.nodeResults());
        };
        String dsl = adoptAndExport();
        var archived = json.readTree(onlyEvidence(dsl).property("payload"))
                .path("revision").path("statements");
        assertThat(archived.get(2).path("id").asText()).isEqualTo("old-whole-source");
        assertThat(archived.get(2).path("wording").asText()).isEqualTo(ORIGINAL);
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var offer = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot(requirement), "de"),
                "architect", context);
        assertThat(offer.baseline().frozenContext().get("inheritedDecisionContext"))
                .doesNotContain("old-whole-source")
                .contains("Arbeitsbeginn und Ende erfassen.");
    }

    @Test
    void secondGenerationExportsNonrecursiveHashLinkedAncestryAndRoundTrips() throws Exception {
        String first = adoptAndExport();
        String firstHash = onlyEvidence(first).property("evidenceHash");
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var offer = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot(requirement), "de"),
                "architect", context);
        var revised = reformulations.saveDraft(project.id(), requirement.id(), offer.id(), 1,
                new ReformulationDtos.SaveDraftRequest("Dritte Fassung mit Erfassung." , "Human revision"),
                "architect", context);
        var preview = adoptions.preview(project.id(), requirement.id(), offer.id(),
                revised.currentRevision().number(), "architect", context);
        adoptions.adopt(project.id(), requirement.id(), offer.id(), revised.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(),
                        preview.content().id(), preview.hash(), true, true, "Second adoption"),
                "architect", context);

        String dsl = git.exportPortfolio("architect", context);
        var blocks = evidenceBlocks(dsl);
        assertThat(blocks).hasSize(2);
        BlockAst second = blocks.stream().filter(b -> b.getHeaderTokens().get(2).equals("3"))
                .findFirst().orElseThrow();
        assertThat(second.property("schemaVersion")).isEqualTo("reformulation-evidence-v2");
        var payload = json.readTree(second.property("payload"));
        assertThat(payload.path("ancestorHashes")).hasSize(1);
        assertThat(payload.path("ancestorHashes").get(0).asText()).isEqualTo(firstHash);
        assertThat(second.property("payload")).doesNotContain(onlyEvidence(first).property("payload"));

        WorkspaceContext target = newWorkspace("Second generation import");
        git.materialize(dsl, "architect", target);
        var roundTrip = evidenceBlocks(git.exportPortfolio("architect", target));
        assertThat(roundTrip).hasSize(2);
        assertThat(roundTrip.stream().map(b -> b.property("evidenceHash")).toList())
                .containsExactlyInAnyOrderElementsOf(blocks.stream().map(b -> b.property("evidenceHash")).toList());
    }

    @Test
    void missingReferencedAncestorRejectsSecondGenerationBeforeMaterialization() throws Exception {
        String first = adoptAndExport();
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var offer = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot(requirement), "de"),
                "architect", context);
        var revised = reformulations.saveDraft(project.id(), requirement.id(), offer.id(), 1,
                new ReformulationDtos.SaveDraftRequest("Dritte Fassung", "Human revision"), "architect", context);
        var preview = adoptions.preview(project.id(), requirement.id(), offer.id(),
                revised.currentRevision().number(), "architect", context);
        adoptions.adopt(project.id(), requirement.id(), offer.id(), revised.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(),
                        preview.content().id(), preview.hash(), true, true, "Second adoption"),
                "architect", context);
        DocumentAst document = parser.parse(git.exportPortfolio("architect", context), "missing-ancestor.taxdsl");
        String firstHash = onlyEvidence(first).property("evidenceHash");
        String missing = serializer.serialize(new DocumentAst(document.getMeta(), document.getBlocks().stream()
                .filter(block -> !EVIDENCE_BLOCK.equals(block.getKind())
                        || !block.property("evidenceHash").equals(firstHash)).toList()));
        WorkspaceContext target = newWorkspace("Missing ancestor rejected");

        assertThatThrownBy(() -> git.materialize(missing, "architect", target))
                .isInstanceOf(PortfolioException.class);
        assertThat(projects.listProjects("architect", target)).isEmpty();
    }

    @Test
    void importedSecondGenerationConflictStillBlocksPositiveReview() throws Exception {
        adoptAndExport();
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var offer = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot(requirement), "de"),
                "architect", context);
        var run = reformulations.beginRun(project.id(), requirement.id(), offer.id(), 1,
                "TEST", "test", "v1", "v1", "frozen", "architect", context);
        var conflict = new com.taxonomy.reformulation.DecisionQuestion("inherited-conflict",
                new com.taxonomy.reformulation.DecisionQuestion.Key("capture", "method", "global"),
                "Conflicting capture choices", List.of(), List.of(),
                new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(
                        com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.Kind.TEXT,
                        List.of(), null, null, null), List.of(), List.of(), "Expert resolution needed",
                com.taxonomy.reformulation.DecisionQuestion.State.CONFLICT);
        reformulations.finishRun(project.id(), requirement.id(), offer.id(), run.id(),
                new com.taxonomy.reformulation.ReformulationDocument("Third text with unresolved method",
                        List.of(), List.of(), List.of(conflict),
                        new com.taxonomy.reformulation.ValidationReport(List.of()), List.of()),
                null, "architect", context);
        var revised = reformulations.get(project.id(), requirement.id(), offer.id(), "architect", context);
        assertThat(revised.currentRevision().number()).isEqualTo(2);
        var preview = adoptions.preview(project.id(), requirement.id(), offer.id(), 2, "architect", context);
        adoptions.adopt(project.id(), requirement.id(), offer.id(), 2,
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(),
                        preview.content().id(), preview.hash(), true, true, "Adopt unresolved offer"),
                "architect", context);
        String dsl = git.exportPortfolio("architect", context);
        assertThat(evidenceBlocks(dsl)).extracting(block -> block.property("schemaVersion"))
                .contains("reformulation-evidence-v2");
        WorkspaceContext target = newWorkspace("Imported v2 review guard");
        git.materialize(dsl, "architect", target);
        select(target);
        var importedProject = projects.listProjects("architect", target).getFirst();
        var importedRequirement = projects.listRequirements(importedProject.id(), "architect", target).getFirst();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/projects/" + importedProject.id() + "/requirements/" + importedRequirement.id())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(projects.getRequirement(importedProject.id(), importedRequirement.id(),
                "architect", target).status()).isNotEqualTo(com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus.APPROVED);
    }

    @Test
    void checksumValidV2AncestorIdentityMismatchAndDuplicateKeyFailBeforeMaterialization() throws Exception {
        adoptAndExport();
        requirement = projects.getRequirement(project.id(), requirement.id(), "architect", context);
        var offer = reformulations.create(project.id(), requirement.id(),
                new ReformulationDtos.CreateRequest(requirement.currentVersionId(), snapshot(requirement), "de"),
                "architect", context);
        var revised = reformulations.saveDraft(project.id(), requirement.id(), offer.id(), 1,
                new ReformulationDtos.SaveDraftRequest("Third text", "Revision"), "architect", context);
        var preview = adoptions.preview(project.id(), requirement.id(), offer.id(),
                revised.currentRevision().number(), "architect", context);
        adoptions.adopt(project.id(), requirement.id(), offer.id(), revised.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(UUID.randomUUID().toString(),
                        preview.content().id(), preview.hash(), true, true, "Second adoption"),
                "architect", context);
        String dsl = git.exportPortfolio("architect", context);
        String v2 = evidenceBlocks(dsl).stream().filter(b -> "3".equals(b.getHeaderTokens().get(2)))
                .findFirst().orElseThrow().property("payload");
        String mismatch = v2.replaceFirst("\\\"sourceVersionNumber\\\":2", "\\\"sourceVersionNumber\\\":1");
        assertThat(mismatch).isNotEqualTo(v2);
        String duplicate = v2.replaceFirst("\\\"ancestorHashes\\\":", "\\\"ancestorHashes\\\":[],\\\"ancestorHashes\\\":");
        for (String invalid : List.of(mismatch, duplicate)) {
            DocumentAst parsed = parser.parse(dsl, "invalid-v2.taxdsl");
            String hash = StableIdentityHash.sha256(invalid);
            String tampered = serializer.serialize(new DocumentAst(parsed.getMeta(), parsed.getBlocks().stream()
                    .map(block -> EVIDENCE_BLOCK.equals(block.getKind()) && "3".equals(block.getHeaderTokens().get(2))
                            ? new BlockAst(block.getKind(), List.of("P", "R", "3", hash),
                                    block.getProperties().stream().map(property -> new PropertyAst(property.key(),
                                            switch (property.key()) {
                                                case "payload" -> invalid;
                                                case "evidenceHash" -> hash;
                                                default -> property.value();
                                            }, property.sourceLocation())).toList(),
                                    block.getChildren(), block.getExtensions(), block.getSourceLocation())
                            : block).toList()));
            WorkspaceContext target = newWorkspace("Invalid v2 rejected");
            assertThatThrownBy(() -> git.materialize(tampered, "architect", target))
                    .isInstanceOf(PortfolioException.class);
            assertThat(projects.listProjects("architect", target)).isEmpty();
        }
    }

    @Test
    void sourceOnlyRejectionGuardAlsoProtectsAdoptedSourceProvenance() throws Exception {
        documentTransform = document -> {
            var protectedSource = new com.taxonomy.reformulation.Statement("adopted-source-protection",
                    "Selected adopted source", List.of(),
                    com.taxonomy.reformulation.Statement.Provenance.ADOPTED_SOURCE,
                    List.of(), List.of(), null,
                    com.taxonomy.reformulation.Statement.EditingOrigin.SOURCE, "UNREVIEWED");
            var statements = new ArrayList<>(document.statements());
            statements.add(protectedSource);
            return new com.taxonomy.reformulation.ReformulationDocument(document.text(),
                    document.sections(), statements, document.questions(), document.validation(), document.nodeResults());
        };
        var offer = seed();
        for (String action : List.of("REJECT", "EDIT")) {
            assertThatThrownBy(() -> reformulations.statement(project.id(), requirement.id(), offer.id(),
                    offer.currentRevision().number(), "adopted-source-protection",
                    new ReformulationDtos.StatementRequest(action, "Mutated source", "Source stays immutable"),
                    "architect", context))
                    .isInstanceOf(PortfolioException.class);
        }
        assertThat(reformulations.get(project.id(), requirement.id(), offer.id(), "architect", context)
                .currentRevision().number()).isEqualTo(offer.currentRevision().number());
    }

    private String snapshotFor(long projectId, com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView selected,
            WorkspaceContext selectedContext) {
        var job = analyses.createOrReuseJob(projectId, List.of(selected.id()), null, 25,
                UUID.randomUUID().toString(), "architect", selectedContext);
        var result = new com.taxonomy.dto.AnalysisResult(java.util.Map.of("BP-1", 45), List.of());
        result.setStatus("PARTIAL");
        String id = UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(), job.id(), projectId,
                com.taxonomy.portfolio.service.PortfolioScope.key("architect", selectedContext),
                id, "session-" + id, result, null, null, null, null, null,
                "prompt-fingerprint", "catalogue-fingerprint", "architect", selectedContext, 1);
        return id;
    }

    private String withEvidencePayload(String dsl, String payload) {
        DocumentAst document = parser.parse(dsl, "reformulation-evidence-payload-rewrite.taxdsl");
        String hash = StableIdentityHash.sha256(payload);
        List<BlockAst> blocks = new ArrayList<>(document.getBlocks().size());
        for (BlockAst block : document.getBlocks()) {
            if (!EVIDENCE_BLOCK.equals(block.getKind())) {
                blocks.add(block);
                continue;
            }
            List<PropertyAst> properties = block.getProperties().stream().map(property ->
                    new PropertyAst(property.key(), switch (property.key()) {
                        case "payload" -> payload;
                        case "evidenceHash" -> hash;
                        default -> property.value();
                    }, property.sourceLocation())).toList();
            List<String> header = new ArrayList<>(block.getHeaderTokens());
            header.set(3, hash);
            blocks.add(new BlockAst(block.getKind(), header, properties,
                    block.getChildren(), block.getExtensions(), block.getSourceLocation()));
        }
        return serializer.serialize(new DocumentAst(document.getMeta(), blocks));
    }

    private String adoptAndExport() throws Exception {
        var proposal = seed();
        var preview = adoptions.preview(project.id(), requirement.id(), proposal.id(),
                proposal.currentRevision().number(), "architect", context);
        adoptions.adopt(project.id(), requirement.id(), proposal.id(),
                proposal.currentRevision().number(),
                new ReformulationAdoptionDtos.ConfirmRequest(
                        UUID.randomUUID().toString(),
                        preview.content().id(),
                        preview.hash(),
                        true,
                        true,
                        "Adopt reviewed draft"),
                "architect", context);
        return git.exportPortfolio("architect", context);
    }

    private WorkspaceContext newWorkspace(String name) {
        var workspace = workspaces.createWorkspace(
                "architect", name + " " + UUID.randomUUID(), "Portable evidence round-trip");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        return new WorkspaceContext(
                "architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(),
                workspace.getSourceRepositoryId());
    }

    private BlockAst onlyEvidence(String dsl) {
        var blocks = evidenceBlocks(dsl);
        assertThat(blocks).hasSize(1);
        return blocks.getFirst();
    }

    private List<BlockAst> evidenceBlocks(String dsl) {
        return parser.parse(dsl, "reformulation-evidence-test.taxdsl")
                .blocksOfKind(EVIDENCE_BLOCK);
    }

    private String replaceEvidenceProperty(String dsl, String key, String value) {
        DocumentAst document = parser.parse(dsl, "reformulation-evidence-rewrite.taxdsl");
        List<BlockAst> blocks = new ArrayList<>(document.getBlocks().size());
        for (BlockAst block : document.getBlocks()) {
            if (!EVIDENCE_BLOCK.equals(block.getKind())) {
                blocks.add(block);
                continue;
            }
            List<PropertyAst> properties = new ArrayList<>(block.getProperties().size());
            boolean replaced = false;
            for (PropertyAst property : block.getProperties()) {
                if (key.equals(property.key())) {
                    properties.add(new PropertyAst(key, value, property.sourceLocation()));
                    replaced = true;
                } else {
                    properties.add(property);
                }
            }
            assertThat(replaced).as("evidence property " + key).isTrue();
            blocks.add(new BlockAst(block.getKind(), block.getHeaderTokens(), properties,
                    block.getChildren(), block.getExtensions(), block.getSourceLocation()));
        }
        return serializer.serialize(new DocumentAst(document.getMeta(), blocks));
    }
}
