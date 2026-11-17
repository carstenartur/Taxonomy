package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.PersonalRebaseReceipt;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.Objects;

@Service
public class PersonalWorkspaceRebase {
    private final WorkspaceUpstreamService upstream;
    private final WorkspaceArchitectureVersionPort editor;
    private final WorkspacePortfolioGitPort portfolio;

    public PersonalWorkspaceRebase(WorkspaceUpstreamService upstream, WorkspaceArchitectureVersionPort editor,
                                   WorkspacePortfolioGitPort portfolio) {
        this.upstream = upstream;
        this.editor = editor;
        this.portfolio = portfolio;
    }

    @Transactional(rollbackFor = IOException.class)
    public DslGitRepository.RebaseReplay apply(RepositoryContext context, UpstreamRebasePlan.ApplyRequest request)
            throws IOException {
        var copy = upstream.lockForRebase(context);
        var transfer = copy.transfer();
        String scope = context.repositoryWorkspaceScopeKey();
        var pending = PersonalRebaseReceipt.read(transfer.target(), scope);
        var row = copy.workspace();
        if (pending != null && !pending.replay().originalHead().equals(transfer.target().getHeadCommit(context.branch()))
                && !pending.acknowledgedBy(transfer.target(), row.getCurrentCommit(), row.getLastIntegratedCommit()))
            throw changed(context);
        var replay = UpstreamRebasePlan.replay(transfer, context.repositoryId(), context.workspaceId(),
                context.branch(), request, false);
        if (!replay.completed()) return replay;
        var local = transfer.target();
        var workspace = WorkspaceResolver.workspaceContext(context);
        portfolio.requireAvailable();
        var oldDsl = local.getDslAtCommit(replay.originalHead());
        var candidate = local.getDslAtCommit(replay.newHead());
        String digest = portfolio.lockedPublicationDigest(context.username(), workspace);
        requireCheckpointedPortfolio(oldDsl, workspace);
        portfolio.validatePortfolio(oldDsl, candidate, context.username(), workspace);
        var receipt = new PersonalRebaseReceipt(context.repositoryId(), context.workspaceId(), context.branch(),
                transfer.upstream().branch(), transfer.forkPoint(), context.username(), row.getOwnerPrincipalId(),
                request.expectedTrackedCommit(), digest, row.getCurrentCommit(), row.getLastIntegratedCommit(), replay);
        editor.applyPreparedWorkspaceRebase(context, replay.originalHead(), replay.newHead(), "Rebase personal working copy", () -> {
            String current = UpstreamRebasePlan.readRefTag(transfer, context.repositoryId(), context.workspaceId(), context.branch());
            if (!request.expectedRefTag().equals(current)) throw changed(context);
            // The original head is still present or both SQL pointers already cover the retained replay.
            if (pending != null) pending.release(local, scope);
            receipt.retain(local, scope);
            try {
                if (!request.expectedRefTag().equals(UpstreamRebasePlan.readRefTag(transfer, context.repositoryId(),
                        context.workspaceId(), context.branch()))) throw changed(context);
                receipt.publish(local);
            } catch (IOException | RuntimeException failure) {
                // A storage error may have occurred after its CAS. Keep a visible or uncertain candidate recoverable.
                if (failure instanceof BranchHeadConflictException || replay.originalHead().equals(local.getHeadCommit(context.branch())))
                    receipt.release(local, scope);
                throw failure;
            }
            return null;
        });
        return finish(copy, context, receipt, candidate, replay.newHead(), receipt.tracked());
    }

    @Transactional(rollbackFor = IOException.class)
    public DslGitRepository.RebaseReplay resume(RepositoryContext context, String candidate, String expectedHead) throws IOException {
        var copy = upstream.lockForRebase(context);
        var transfer = copy.transfer();
        var local = transfer.target();
        var row = copy.workspace();
        var receipt = PersonalRebaseReceipt.read(local, context.repositoryWorkspaceScopeKey());
        if (receipt == null || !receipt.replay().newHead().equals(candidate)
                || !context.branch().equals(receipt.branch()) || !context.repositoryId().equals(receipt.repository())
                || !context.workspaceId().equals(receipt.workspace()) || !context.username().equals(receipt.owner())
                || !Objects.equals(row.getOwnerPrincipalId(), receipt.ownerPrincipal())
                || !Objects.equals(transfer.forkPoint(), receipt.forkPoint())
                || !transfer.upstream().branch().equals(receipt.sourceBranch())) throw changed(context);
        if (expectedHead == null || !org.eclipse.jgit.lib.ObjectId.isId(expectedHead)) throw changed(context);
        local.verifyExpectedHead(context.branch(), expectedHead);
        if (receipt.acknowledgedBy(local, row.getCurrentCommit(), row.getLastIntegratedCommit())) {
            releaseAfterCommit(local, context.repositoryWorkspaceScopeKey(), receipt);
            return outcome(receipt, expectedHead);
        }
        if ((!Objects.equals(receipt.previousCurrent(), row.getCurrentCommit()) && !expectedHead.equals(row.getCurrentCommit()))
                || !Objects.equals(receipt.previousIntegrated(), row.getLastIntegratedCommit())) throw changed(context);
        var workspace = WorkspaceResolver.workspaceContext(context);
        var dsl = local.getDslAtCommit(expectedHead);
        if (!receipt.portfolioDigest().equals(portfolio.lockedPublicationDigest(context.username(), workspace)))
            requireCheckpointedPortfolio(dsl, workspace);
        portfolio.validatePortfolio(local.getDslAtCommit(receipt.replay().originalHead()), dsl, context.username(), workspace);
        editor.applyPreparedWorkspaceRebase(context, receipt.replay().originalHead(), expectedHead, "Adopt reviewed personal branch",
                () -> local.verifyExpectedHead(context.branch(), expectedHead));
        // Only a branch that actually includes the replay incorporates its upstream.
        String integrated = candidate.equals(expectedHead) || local.isAncestor(candidate, expectedHead)
                ? receipt.tracked() : row.getLastIntegratedCommit();
        return finish(copy, context, receipt, dsl, expectedHead, integrated);
    }

    private DslGitRepository.RebaseReplay finish(WorkspaceUpstreamService.WorkingCopy copy, RepositoryContext context,
            PersonalRebaseReceipt receipt, String candidateDsl, String head, String integrated) throws IOException {
        copy.transfer().reauthorize().run();
        copy.transfer().target().verifyExpectedHead(context.branch(), head);
        portfolio.materializePortfolio(candidateDsl, context.username(), WorkspaceResolver.workspaceContext(context));
        copy.workspace().setCurrentCommit(head);
        copy.workspace().setLastIntegratedCommit(integrated);
        releaseAfterCommit(copy.transfer().target(), context.repositoryWorkspaceScopeKey(), receipt);
        return outcome(receipt, head);
    }

    private static DslGitRepository.RebaseReplay outcome(PersonalRebaseReceipt receipt, String head) {
        var replay = receipt.replay();
        if (head.equals(replay.newHead())) return replay;
        return new DslGitRepository.RebaseReplay(replay.originalHead(), head, null, java.util.List.of(), java.util.List.of(), null);
    }

    private static void releaseAfterCommit(DslGitRepository local, String scope, PersonalRebaseReceipt receipt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { receipt.release(local, scope); }
                catch (IOException e) { LoggerFactory.getLogger(PersonalWorkspaceRebase.class).warn("Retained completed personal rebase", e); }
            }
        });
    }


    private void requireCheckpointedPortfolio(String dsl, WorkspaceContext workspace) throws BranchHeadConflictException {
        if (!portfolio.matchesCheckpoint(dsl, workspace.username(), workspace))
            throw new BranchHeadConflictException(workspace.currentBranch(), null, null,
                    "Commit the current portfolio changes and review the rebase again");
    }

    private static BranchHeadConflictException changed(RepositoryContext context) {
        return new BranchHeadConflictException(context.branch(), null, null, "Refresh the personal rebase plan and review its current state");
    }
}
