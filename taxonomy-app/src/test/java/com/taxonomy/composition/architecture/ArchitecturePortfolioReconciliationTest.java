package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.command.ArchitectureCommand.UpdateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.requirements.RequirementRealization;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.editor.ArchitectureEditorController;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.editor.ArchitectureEditorService.*;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual editor, portfolio transaction and JGit publication boundary, reconstructed after checkout loss. */
@SpringBootTest
@WithMockUser(username = "alice", roles = "ADMIN")
class ArchitecturePortfolioReconciliationTest {
    private static final String MODEL = """
            element recorder type Component {
              title: "Recorder";
            }
            """;

    @Autowired ArchitectureRequirementService changes;
    @Autowired ArchitectureLifecycleService lifecycle;
    @Autowired ArchitectureEditorService editor;
    @Autowired ArchitectureEditorController editorController;
    @Autowired ProjectPortfolioService projects;
    @Autowired PortfolioGitService portfolio;
    @Autowired SystemRepositoryService repositories;
    @Autowired UserWorkspaceRepository workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired EditorJournal journal;
    @Autowired EntityManagerFactory entityManagers;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incomingRequirementDecisionRemainsCurrentAfterReconciliationAndLaterSave(boolean changedVersion) throws Exception {
        RepositoryContext local = workspace("positive"), incoming = workspace("incoming");
        git.resolveRepository(local).commitDsl("draft", MODEL, "alice", "Initial authored architecture");
        var saved = create(local, null, "OBSERVATION", "REQ-1", "Original requirement");
        String baseline = editor.read(local, null).dsl();
        importBaseline(incoming, baseline);
        var incomingProject = projects.listProjects("alice", scope(incoming)).getFirst();
        String expectedKey = changedVersion ? "REQ-1" : "REQ-2";
        String expectedText = changedVersion ? "Reviewed second requirement version" : "Additional incoming requirement";
        if (changedVersion) {
            var current = projects.listRequirements(incomingProject.id(), "alice", scope(incoming)).getFirst();
            revise(incoming, incomingProject.id(), current.id(), expectedText);
        } else create(incoming, incomingProject.id(), null, expectedKey, expectedText);
        String incomingDsl = editor.read(incoming, null).dsl();
        Context retained = dirtyModel(local);
        git.resolveRepository(local).commitDsl("draft", incomingDsl, "alice", "Incoming requirement decision");
        Metadata metadata = metadata("Reconcile incoming requirement decision");
        var preview = editor.previewReconciliation(local, new ReconciliationRequest(retained, metadata, Map.of(), null, null));
        assertThat(preview.canAccept()).isTrue();
        assertThat(preview.conflicts()).isEmpty();
        var accepted = editor.acceptReconciliation(local, new ReconciliationRequest(retained, metadata, Map.of(), preview.headCommit(), preview.fingerprint()));
        assertThat(git.resolveRepository(local).getDslAtCommit(accepted.commitId())).contains(expectedText, "Local recorder");
        assertThat(lifecycle.overview(local, null, null).requirements()).anySatisfy(requirement -> {
            assertThat(requirement.requirementKey()).isEqualTo(expectedKey);
            assertThat(requirement.text()).isEqualTo(expectedText);
        });
        assertThat(projects.listRequirements(saved.projectId(), "alice", scope(local))).anySatisfy(requirement -> {
            assertThat(requirement.requirementKey()).isEqualTo(expectedKey);
            assertThat(requirement.currentVersion().text()).isEqualTo(expectedText);
        });
        var unrelated = create(local, saved.projectId(), null, "REQ-3", "Independent later requirement");
        assertThat(lifecycle.overview(local, unrelated.publication().checkpoint().commitId(), null).requirements()).anySatisfy(requirement -> {
            assertThat(requirement.requirementKey()).isEqualTo(expectedKey);
            assertThat(requirement.text()).isEqualTo(expectedText);
        });
    }

    @Test
    void conflictingImmutableVersionRollsBackPortfolioAndEditorBeforeGitPublication() throws Exception {
        RepositoryContext local = workspace("collision"), incoming = workspace("collision-incoming");
        git.resolveRepository(local).commitDsl("draft", MODEL, "alice", "Initial authored architecture");
        var saved = create(local, null, "COLLISION", "REQ-1", "Original immutable first version");
        importBaseline(incoming, editor.read(local, null).dsl());
        revise(local, saved.projectId(), saved.requirementId(), "Local immutable second version");
        var incomingProject = projects.listProjects("alice", scope(incoming)).getFirst();
        var incomingRequirement = projects.listRequirements(incomingProject.id(), "alice", scope(incoming)).getFirst();
        revise(incoming, incomingProject.id(), incomingRequirement.id(), "Incoming immutable second version");
        projects.updateProject(incomingProject.id(), new UpdateProjectRequest("Incoming project title", null, null, null, null, null, null), "alice", scope(incoming));
        String incomingDsl = portfolio.contributeTo(editor.read(incoming, null).dsl(), "alice", scope(incoming));
        Context retained = dirtyModel(local);
        git.resolveRepository(local).commitDsl("draft", incomingDsl, "alice", "Conflicting immutable version");
        Metadata metadata = metadata("Resolve immutable source collision");
        var preview = editor.previewReconciliation(local, new ReconciliationRequest(retained, metadata, Map.of(), null, null));
        assertThat(preview.canAccept()).isFalse();
        String immutableId = ArchitectureSemanticPatch.index(incomingDsl).entrySet().stream()
                .filter(entry -> "requirementVersion".equals(entry.getValue().getKind())
                        && "2".equals(entry.getValue().getHeaderTokens().getLast())).map(Map.Entry::getKey).findFirst().orElseThrow();
        assertThat(preview.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.id()).isEqualTo(immutableId);
            assertThat(conflict.base()).contains("Local immutable second version");
            assertThat(conflict.local()).contains("Local immutable second version");
            assertThat(conflict.incoming()).contains("Incoming immutable second version");
        });
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).contains("element:recorder");
        var beforeJournal = journal.read(local);
        var beforeCheckpoints = checkpointIds(local);
        String beforeHead = git.resolveRepository(local).getHeadCommit("draft");
        String beforeDsl = editor.read(local, null).dsl();
        var beforeProject = projects.getProject(saved.projectId(), "alice", scope(local));
        var beforeRequirement = projects.getRequirement(saved.projectId(), saved.requirementId(), "alice", scope(local));
        var beforeVersions = projects.listRequirementVersions(saved.projectId(), saved.requirementId(), "alice", scope(local));
        Map<String, Resolution> incompatible = localChoices(preview);
        incompatible.put(immutableId, Resolution.INCOMING);
        assertRejectedChoice(local, retained, metadata, preview, incompatible);
        assertThat(journal.read(local)).isEqualTo(beforeJournal);
        assertThat(checkpointIds(local)).isEqualTo(beforeCheckpoints);
        assertThat(git.resolveRepository(local).getHeadCommit("draft")).isEqualTo(beforeHead);
        assertThat(editor.read(local, null).dsl()).isEqualTo(beforeDsl);
        assertThat(projects.getProject(saved.projectId(), "alice", scope(local))).isEqualTo(beforeProject);
        assertThat(projects.getRequirement(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeRequirement);
        assertThat(projects.listRequirementVersions(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeVersions);
        var accepted = acceptLocalChoices(local, retained, metadata, preview);
        assertThat(git.resolveRepository(local).getDslAtCommit(accepted.commitId())).contains("Local immutable second version", "Local recorder")
                .doesNotContain("Incoming immutable second version", "Incoming project title");
        assertThat(projects.getProject(saved.projectId(), "alice", scope(local))).isEqualTo(beforeProject);
        assertThat(projects.getRequirement(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeRequirement);
        assertThat(projects.listRequirementVersions(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeVersions);
        assertThat(journal.read(local).state().revision()).isEqualTo(beforeJournal.state().revision() + 1);
        assertThat(lifecycle.overview(local, null, null).requirements()).extracting(ArchitectureLifecycleService.Requirement::text)
                .contains("Local immutable second version");
    }

    @Test
    void canonicalTextCannotContradictItsPortableImmutableVersionAfterReconciliation() throws Exception {
        RepositoryContext local = workspace("canonical");
        git.resolveRepository(local).commitDsl("draft", MODEL, "alice", "Initial authored architecture");
        var saved = create(local, null, "CONSISTENT", "REQ-1", "Immutable business text");
        String baseline = editor.read(local, null).dsl();
        BlockAst canonical = ArchitectureSemanticPatch.index(baseline).values().stream().filter(b -> "requirement".equals(b.getKind())).findFirst().orElseThrow();
        String canonicalId = ArchitectureSemanticPatch.key(canonical);
        List<PropertyAst> values = canonical.getProperties().stream().map(p -> "text".equals(p.key())
                ? new PropertyAst("text", "Contradictory canonical text", null) : p).toList();
        String incoming = ArchitectureSemanticPatch.replace(baseline, canonical, new BlockAst(canonical.getKind(), canonical.getHeaderTokens(),
                values, canonical.getChildren(), canonical.getExtensions(), null));
        Context retained = dirtyModel(local);
        git.resolveRepository(local).commitDsl("draft", incoming, "alice", "Inconsistent canonical source");
        Metadata metadata = metadata("Resolve canonical source mismatch");
        var preview = editor.previewReconciliation(local, new ReconciliationRequest(retained, metadata, Map.of(), null, null));
        assertThat(preview.canAccept()).isFalse();
        assertThat(preview.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.id()).isEqualTo(canonicalId);
            assertThat(conflict.base()).contains("Immutable business text");
            assertThat(conflict.local()).contains("Immutable business text");
            assertThat(conflict.incoming()).contains("Contradictory canonical text");
        });
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).contains("element:recorder");
        var beforeJournal = journal.read(local);
        var beforeCheckpoints = checkpointIds(local);
        String beforeHead = git.resolveRepository(local).getHeadCommit("draft");
        String beforeDsl = editor.read(local, null).dsl();
        var beforeRequirement = projects.getRequirement(saved.projectId(), saved.requirementId(), "alice", scope(local));
        var beforeVersions = projects.listRequirementVersions(saved.projectId(), saved.requirementId(), "alice", scope(local));
        Map<String, Resolution> incompatible = localChoices(preview);
        incompatible.put(canonicalId, Resolution.INCOMING);
        assertRejectedChoice(local, retained, metadata, preview, incompatible);
        assertThat(journal.read(local)).isEqualTo(beforeJournal);
        assertThat(checkpointIds(local)).isEqualTo(beforeCheckpoints);
        assertThat(git.resolveRepository(local).getHeadCommit("draft")).isEqualTo(beforeHead);
        assertThat(editor.read(local, null).dsl()).isEqualTo(beforeDsl);
        assertThat(projects.getRequirement(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeRequirement);
        assertThat(projects.listRequirementVersions(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeVersions);
        var accepted = acceptLocalChoices(local, retained, metadata, preview);
        assertThat(git.resolveRepository(local).getDslAtCommit(accepted.commitId())).contains("Immutable business text", "Local recorder")
                .doesNotContain("Contradictory canonical text");
        assertThat(projects.getRequirement(saved.projectId(), saved.requirementId(), "alice", scope(local))).isEqualTo(beforeRequirement);
        assertThat(journal.read(local).state().revision()).isEqualTo(beforeJournal.state().revision() + 1);
        assertThat(lifecycle.overview(local, null, null).requirements()).extracting(ArchitectureLifecycleService.Requirement::text)
                .containsExactly("Immutable business text");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forgedPortableVersionHashesAreRejectedInReadOnlyPreview(boolean historicalVersion) throws Exception {
        RepositoryContext local = workspace("hash-local"), incoming = workspace("hash-incoming");
        git.resolveRepository(local).commitDsl("draft", MODEL, "alice", "Initial authored architecture");
        git.resolveRepository(incoming).commitDsl("draft", MODEL, "alice", "Initial incoming architecture");
        var saved = create(incoming, null, "HASH", "REQ-1", "Exact original version text");
        if (historicalVersion) revise(incoming, saved.projectId(), saved.requirementId(), "Exact current second version text");
        String incomingDsl = editor.read(incoming, null).dsl();
        String forgedHash = "0".repeat(64);
        String forgedVersionId = null;
        for (BlockAst block : ArchitectureSemanticPatch.index(incomingDsl).values()) {
            if ("requirementVersion".equals(block.getKind()) && "1".equals(block.getHeaderTokens().getLast())) {
                forgedVersionId = ArchitectureSemanticPatch.key(block);
                incomingDsl = replaceProperty(incomingDsl, block, "contentHash", forgedHash);
            } else if (!historicalVersion && "requirement".equals(block.getKind())) {
                incomingDsl = replaceProperty(incomingDsl, block, "x-content-hash", forgedHash);
            }
        }
        assertThat(forgedVersionId).isNotNull();
        Context retained = dirtyModel(local);
        git.resolveRepository(local).commitDsl("draft", incomingDsl, "alice", "Incoming forged immutable version hash");
        var beforeJournal = journal.read(local);
        var beforeCheckpoints = checkpointIds(local);
        String beforeHead = git.resolveRepository(local).getHeadCommit("draft");
        String beforeDsl = editor.read(local, null).dsl();
        Metadata metadata = metadata("Resolve forged immutable version hash");

        var preview = editor.previewReconciliation(local, new ReconciliationRequest(retained, metadata, Map.of(), null, null));
        assertThat(preview.canAccept()).as("Invalid incoming hashes must be detected before materialization").isFalse();
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).contains(forgedVersionId);
        Map<String, Resolution> incomingChoices = new LinkedHashMap<>();
        preview.conflicts().forEach(conflict -> incomingChoices.put(conflict.id(), Resolution.INCOMING));
        assertRejectedChoice(local, retained, metadata, preview, incomingChoices);
        assertThat(journal.read(local)).isEqualTo(beforeJournal);
        assertThat(checkpointIds(local)).isEqualTo(beforeCheckpoints);
        assertThat(git.resolveRepository(local).getHeadCommit("draft")).isEqualTo(beforeHead);
        assertThat(editor.read(local, null).dsl()).isEqualTo(beforeDsl);
        assertThat(projects.listProjects("alice", scope(local))).isEmpty();

        var accepted = acceptLocalChoices(local, retained, metadata, preview);
        assertThat(git.resolveRepository(local).getDslAtCommit(accepted.commitId())).contains("Local recorder")
                .doesNotContain("requirementVersion", forgedHash);
        assertThat(projects.listProjects("alice", scope(local))).isEmpty();
    }

    private static String replaceProperty(String source, BlockAst block, String key, String value) {
        BlockAst current = ArchitectureSemanticPatch.index(source).get(ArchitectureSemanticPatch.key(block));
        List<PropertyAst> properties = current.getProperties().stream().map(property -> key.equals(property.key())
                ? new PropertyAst(key, value, null) : property).toList();
        return ArchitectureSemanticPatch.replace(source, current, new BlockAst(current.getKind(), current.getHeaderTokens(), properties,
                current.getChildren(), current.getExtensions(), null));
    }

    private RepositoryContext workspace(String purpose) {
        String repository = repositories.getPrimaryRepository().getRepositoryId();
        String id = "portfolio-reconcile-" + purpose + "-" + UUID.randomUUID();
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(id); workspace.setUsername("alice"); workspace.setDisplayName(id);
        workspace.setSourceRepositoryId(repository); workspace.setSourceBranch("draft"); workspace.setCurrentBranch("draft");
        workspace.setCreatedAt(Instant.now()); workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        workspaces.saveAndFlush(workspace);
        return RepositoryContext.workspace(repository, id, "draft", "alice");
    }

    private void importBaseline(RepositoryContext context, String source) throws Exception {
        portfolio.materialize(source, "alice", scope(context));
        git.resolveRepository(context).commitDsl("draft", source, "alice", "Import exact portfolio baseline");
    }

    private ArchitectureRequirementService.Saved create(RepositoryContext context, Long projectId, String projectKey,
                                                        String requirementKey, String text) throws Exception {
        var input = new CreateRequirementRequest(requirementKey, requirementKey, text, null, null, null, null, null, "alice", "Create reviewed requirement", null);
        var request = new ArchitectureRequirementService.Request(editor.read(context, null).context(), metadata("Create reviewed requirement"), "NEW",
                projectId, projectId == null ? new CreateProjectRequest(projectKey, projectKey, null, null, null, null, null, null) : null,
                null, null, input, null, null, RequirementRealization.textHash(text));
        var saved = changes.save(context, request);
        assertThat(saved.publication().state()).isEqualTo("CHECKPOINTED");
        return saved;
    }

    private void revise(RepositoryContext context, Long projectId, Long requirementId, String text) throws Exception {
        var current = projects.getRequirement(projectId, requirementId, "alice", scope(context));
        var input = new CreateRequirementRequest(current.requirementKey(), current.title(), text, null, null, null, null, null, "alice", "Revise exact version", null);
        var request = new ArchitectureRequirementService.Request(editor.read(context, null).context(), metadata("Revise exact version"), "CHANGE", projectId,
                null, null, requirementId, input, current.currentVersionId(), current.currentVersion().contentHash(), RequirementRealization.textHash(text));
        assertThat(changes.save(context, request).publication().state()).isEqualTo("CHECKPOINTED");
    }

    private Context dirtyModel(RepositoryContext context) throws Exception {
        return editor.execute(context, new Command(editor.read(context, null).context(), metadata("Keep local model decision"),
                new SemanticCommand(new UpdateArchitectureElement("recorder", "Component", Map.of("title", "Local recorder"))))).context();
    }

    private List<String> checkpointIds(RepositoryContext context) {
        try (var manager = entityManagers.createEntityManager()) {
            return manager.createQuery("select c.commandId from EditorCheckpoint c where c.scopeId = :scope order by c.commandId", String.class)
                    .setParameter("scope", EditorJournal.scope(context)).getResultList();
        }
    }

    private Map<String, Resolution> localChoices(ReconciliationPreview preview) {
        Map<String, Resolution> choices = new LinkedHashMap<>();
        preview.conflicts().forEach(conflict -> choices.put(conflict.id(), Resolution.LOCAL));
        return choices;
    }

    private void assertRejectedChoice(RepositoryContext context, Context retained, Metadata metadata,
                                      ReconciliationPreview preview, Map<String, Resolution> choices) {
        var request = new ReconciliationRequest(retained, metadata, choices, preview.headCommit(), preview.fingerprint());
        assertThatThrownBy(() -> editor.previewReconciliation(context, request)).isInstanceOfSatisfying(CommandProblem.class, this::assertPortfolioConflict);
        assertThatThrownBy(() -> editor.acceptReconciliation(context, request)).isInstanceOfSatisfying(CommandProblem.class, this::assertPortfolioConflict);
    }

    private void assertPortfolioConflict(CommandProblem problem) {
        assertThat(problem.code()).isEqualTo("PORTFOLIO_RECONCILIATION_REQUIRED");
        var response = editorController.problem(problem);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "PORTFOLIO_RECONCILIATION_REQUIRED");
    }

    private CheckpointAccepted acceptLocalChoices(RepositoryContext context, Context retained, Metadata metadata,
                                                  ReconciliationPreview original) throws Exception {
        Map<String, Resolution> choices = localChoices(original);
        var preview = editor.previewReconciliation(context, new ReconciliationRequest(retained, metadata, choices, null, null));
        assertThat(preview.canAccept()).isTrue();
        assertThat(preview.conflicts()).extracting(ReconciliationConflict::id).containsExactlyInAnyOrderElementsOf(choices.keySet());
        return editor.acceptReconciliation(context, new ReconciliationRequest(retained, metadata, choices, preview.headCommit(), preview.fingerprint()));
    }

    private static WorkspaceContext scope(RepositoryContext context) {
        return new WorkspaceContext(context.username(), context.workspaceId(), context.branch(), context.repositoryId());
    }
    private static Metadata metadata(String rationale) {
        String id = UUID.randomUUID().toString();
        return new Metadata(id, id, id, rationale);
    }
}
