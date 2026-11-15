package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.service.ProposalBranchAdoptionService.State;
import com.taxonomy.relations.service.ProposalReviewStateStore.ProposalSnapshot;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.DslWorkspaceReadAdapter;
import com.taxonomy.workspace.storage.DslWorkspaceVersionAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposalBranchAdoptionServiceTest {
    private static final RepositoryContext MAIN = RepositoryContext.centralRead("repo", "main", "reviewer");
    private static final RepositoryContext DRAFT = RepositoryContext.workspace("repo", "workspace", "draft", "reviewer");
    private static final String SOURCE = "relation A USES B {\n  status: accepted;\n  confidence: 0.8;\n"
            + "  provenance: proposal-review;\n  x-proposal-id: 17;\n}\n";
    private static final String DRAFT_TEXT = "# keep my local comment\nelement C type Capability {\n title: \"Local\";\n}\n";
    private static final CommandMetadata META = new CommandMetadata("adopt-17");
    private final DslGitRepositoryFactory git = new DslGitRepositoryFactory(null);
    private final ProposalReviewStateStore store = mock();
    private final GitAuthoritativeRelationMutationService mutations = mock();
    private final ProposalBranchAdoptionService service = new ProposalBranchAdoptionService(store, new DslWorkspaceReadAdapter(git), mutations);

    ProposalBranchAdoptionServiceTest() {
        when(store.centralProposal(17L, "repo")).thenReturn(Optional.of(new ProposalSnapshot(17L, "A", "B",
                RelationType.USES, ProposalStatus.ACCEPTED, 0.8, "Review", "generator")));
    }

    @AfterEach void close() { git.close(); }

    private String write(RepositoryContext ctx, String dsl) throws IOException {
        return git.resolveRepository(ctx).commitDsl(ctx.branch(), dsl, ctx.username(), "Seed test");
    }

    @Test void copiesOnlyAcceptedDecisionAndRecognizesItOnRetry() throws Exception {
        String prior = write(MAIN, SOURCE.replace("0.8", "0.4"));
        String from = write(MAIN, "# not in the draft\n" + SOURCE);
        String to = write(DRAFT, DRAFT_TEXT);
        var before = service.preview(17L, MAIN, DRAFT);
        assertThat(before.state()).isEqualTo(State.AVAILABLE);
        assertThat(before.reviewedOnSourceBranch()).isFalse();
        var commands = new ArchitectureRelationGitCommandService(new DslWorkspaceVersionAdapter(git));
        when(mutations.adopt(eq(DRAFT), eq(to), any())).thenAnswer(invocation -> {
            var authority = commands.execute(DRAFT, to, invocation.getArgument(2,
                    ArchitectureRelationGitCommandService.AdoptRelation.class));
            return new GitAuthoritativeRelationMutationService.MutationResult(authority, mock());
        });
        var after = service.adopt(17L, MAIN, from, DRAFT, to, META);
        assertThat(after.state()).isEqualTo(State.ALREADY_PRESENT);
        assertThat(after.targetCommit()).isNotEqualTo(to);
        assertThat(git.resolveRepository(DRAFT).getDslAtHead("draft")).startsWith(DRAFT_TEXT)
                .contains("17", "0.8", "proposal-adoption", from).doesNotContain("not in the draft");
        assertThat(git.resolveRepository(MAIN).getHeadCommit("main")).isEqualTo(from);
        assertThat(service.adopt(17L, MAIN, from, DRAFT, after.targetCommit(), META)).isEqualTo(after);
        String later = write(MAIN, "# a later and unrelated edit\n" + SOURCE);
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.ALREADY_PRESENT);
        var copied = git.resolveRepository(DRAFT).getDslAtHead("draft");
        write(DRAFT, copied.replace(from, "a".repeat(40)));
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.CONFLICT);
        write(DRAFT, copied.replace(from, prior));
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.CONFLICT);
        assertThat(git.resolveRepository(MAIN).getHeadCommit("main")).isEqualTo(later);
        verify(mutations, times(1)).adopt(any(), any(), any());
        verify(store, never()).transition(any(), any(), any(), any());
    }

    @Test void newAcceptanceRecordsTheActualCentralBranchWithoutAssumingAnImportedDecisionOrigin() throws Exception {
        var writable = RepositoryContext.centralWrite(MAIN.repositoryId(), MAIN.branch(), MAIN.username());
        String initial = write(MAIN, "# architecture\n");
        write(DRAFT, DRAFT_TEXT);
        var commands = new ArchitectureRelationGitCommandService(new DslWorkspaceVersionAdapter(git));
        when(store.require(17L, writable)).thenReturn(new ProposalSnapshot(17L, "A", "B",
                RelationType.USES, ProposalStatus.PENDING, 0.8, "reviewed", "generated"));
        when(store.transition(17L, writable, ProposalStatus.PENDING, ProposalStatus.ACCEPTED)).thenReturn(ProposalStatus.ACCEPTED);
        when(mutations.upsert(eq(writable), eq(initial), any(), eq(META))).thenAnswer(invocation -> {
            RelationDefinition definition = invocation.getArgument(2);
            var result = commands.execute(writable, initial,
                    new ArchitectureRelationGitCommandService.UpsertRelation(definition, META));
            return new GitAuthoritativeRelationMutationService.MutationResult(result, mock());
        });
        new GitAuthoritativeProposalReviewService(store, mutations).accept(17L, writable, initial, META);
        var preview = service.preview(17L, MAIN, DRAFT);
        assertThat(preview.state()).isEqualTo(State.AVAILABLE);
        assertThat(preview.reviewedOnSourceBranch()).isTrue();
        assertThat(service.preview(17L, MAIN, MAIN).state()).isEqualTo(State.ALREADY_PRESENT);
        String accepted = git.resolveRepository(MAIN).getDslAtHead("main");
        write(MAIN, accepted.replace("x-proposal-review-branch: \"main\"", "x-proposal-review-branch: \"another\""));
        assertThat(service.preview(17L, MAIN, DRAFT).reviewedOnSourceBranch()).isFalse();
    }

    @Test void missingOrMalformedSourceAndUnavailableTargetRemainReadOnly() throws Exception {
        write(DRAFT, DRAFT_TEXT);
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.UNVERIFIED_SOURCE);
        write(MAIN, SOURCE.substring(0, SOURCE.lastIndexOf("}")));
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.UNVERIFIED_SOURCE);
        write(MAIN, SOURCE.replace("x-proposal-id: 17;", "nested {\n  x-proposal-id: 17;\n }"));
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.UNVERIFIED_SOURCE);
        String otherRelation = SOURCE.replace("A USES B", "A USES C");
        write(MAIN, SOURCE + otherRelation);
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.UNVERIFIED_SOURCE);
        write(MAIN, SOURCE + SOURCE);
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.UNVERIFIED_SOURCE);
        write(MAIN, SOURCE);
        write(DRAFT, otherRelation);
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.CONFLICT);
        write(DRAFT, SOURCE.replace("x-proposal-id: 17;", "nested {\n  x-proposal-id: 17;\n }"));
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.CONFLICT);
        write(DRAFT, "# broken target\nelement A type Capability {\n");
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.CONFLICT);
        var missing = RepositoryContext.workspace("repo", "workspace", "missing", "reviewer");
        assertThat(service.preview(17L, MAIN, missing).state()).isEqualTo(State.CONFLICT);
        verifyNoInteractions(mutations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status: rejected;", "x-proposal-id: 18;", "confidence: 0.4;", "provenance: manual;"})
    void divergingTargetIsNotOverwritten(String edit) throws Exception {
        String from = write(MAIN, SOURCE);
        String to = write(DRAFT, SOURCE.replaceFirst(edit.split(":")[0] + ": [^;]*;", edit));
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.CONFLICT);
        assertThatThrownBy(() -> service.adopt(17L, MAIN, from, DRAFT, to, META))
                .isInstanceOf(ProposalBranchAdoptionService.AdoptionConflictException.class);
        verifyNoInteractions(mutations);
    }

    @Test void ignoresUnknownHistoricalOriginAndRejectsStalePins() throws Exception {
        String from = write(MAIN, SOURCE.replace("17", "23"));
        String to = write(DRAFT, DRAFT_TEXT);
        assertThat(service.preview(17L, MAIN, DRAFT).state()).isEqualTo(State.UNVERIFIED_SOURCE);
        String current = write(MAIN, SOURCE);
        assertThatThrownBy(() -> service.adopt(17L, MAIN, from, DRAFT, to, META)).isInstanceOf(BranchHeadConflictException.class);
        write(DRAFT, DRAFT_TEXT + "# new\n");
        assertThatThrownBy(() -> service.adopt(17L, MAIN, current, DRAFT, to, META)).isInstanceOf(BranchHeadConflictException.class);
        verifyNoInteractions(mutations);
    }

    @Test void missingProposalAndForeignRepositoryCannotBeRead() {
        assertThatThrownBy(() -> service.preview(18L, MAIN, DRAFT))
                .isInstanceOf(ProposalBranchAdoptionService.ProposalNotFoundException.class);
        assertThatThrownBy(() -> service.preview(17L, MAIN, RepositoryContext.workspace("other", "w", "draft", "reviewer")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
