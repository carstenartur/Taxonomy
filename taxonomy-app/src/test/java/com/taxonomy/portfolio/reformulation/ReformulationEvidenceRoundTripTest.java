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
