package com.taxonomy.workspace.service;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.merge.TaxDslMergeResult;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.CommitProvenance;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.versioning.service.SemanticGitMergeService;
import com.taxonomy.workspace.model.WorkspaceSyncScope;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

/**
 * Portfolio-aware replacement for the legacy copy-based synchronization path.
 *
 * <p>Before pull or push, durable project decisions are projected into the
 * branch DSL through a workspace-owned port. Isolated repositories use a
 * tracked three-way semantic base instead of replacing the complete
 * architecture file. Every synchronization path carries the exact logical
 * repository, workspace/central scope and branch into both Git and portfolio
 * materialisation.</p>
 */
@Service
@Primary
public class GitNativeSyncIntegrationService extends SyncIntegrationService {

    private static final String WORKSPACE_BRANCH = "main";
    private static final String SEEDED_BRANCH = "draft";
    private static final String TRACKING_BRANCH = "sync-base";

    private final SyncStateRepository syncStateRepository;
    private final UserWorkspaceRepository workspaceRepository;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final SemanticGitMergeService semanticMergeService;
    private final WorkspacePortfolioGitPort portfolioGitPort;
    private final WorkspaceContextResolver contextResolver;
    private final WorkspaceArchitectureVersionPort editorVersions;
    private final RepositoryMembershipService sourceMemberships;
    private final ArchitectureDslCommands architectureCommands = new ArchitectureDslCommands();

    /** Compatibility constructor: source operations fail closed without a current policy. */
    public GitNativeSyncIntegrationService(SyncStateRepository syncStateRepository,
                                           UserWorkspaceRepository workspaceRepository,
                                           SystemRepositoryService systemRepositoryService,
                                           DslGitRepositoryFactory repositoryFactory,
                                           SemanticGitMergeService semanticMergeService,
                                           WorkspacePortfolioGitPort portfolioGitPort,
                                           WorkspaceContextResolver contextResolver, WorkspaceArchitectureVersionPort editorVersions) {
        this(syncStateRepository, workspaceRepository, systemRepositoryService, repositoryFactory,
                semanticMergeService, portfolioGitPort, contextResolver, editorVersions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GitNativeSyncIntegrationService(SyncStateRepository syncStateRepository,
                                           UserWorkspaceRepository workspaceRepository,
                                           SystemRepositoryService systemRepositoryService,
                                           DslGitRepositoryFactory repositoryFactory,
                                           SemanticGitMergeService semanticMergeService,
                                           WorkspacePortfolioGitPort portfolioGitPort,
                                           WorkspaceContextResolver contextResolver, WorkspaceArchitectureVersionPort editorVersions,
                                           RepositoryMembershipService sourceMemberships) {
        super(syncStateRepository, workspaceRepository, systemRepositoryService, repositoryFactory);
        this.syncStateRepository = syncStateRepository;
        this.workspaceRepository = workspaceRepository;
        this.systemRepositoryService = systemRepositoryService;
        this.repositoryFactory = repositoryFactory;
        this.semanticMergeService = semanticMergeService;
        this.portfolioGitPort = portfolioGitPort;
        this.contextResolver = contextResolver;
        this.editorVersions = editorVersions;
        this.sourceMemberships = sourceMemberships;
    }

    private WorkspacePublicationRecovery publications;

    @org.springframework.beans.factory.annotation.Autowired
    void configurePublications(WorkspacePublicationRecovery publications) {
        this.publications = java.util.Objects.requireNonNull(publications);
    }

    @Override
    public Publication publishWithReceipt(String username,String branch,String key,String cause) throws IOException {
        portfolioGitPort.requireAvailable();
        var context = resolveWorkspaceContext(username,branch);
        if (context.workspaceId() == null || publications == null) {
            return new Publication(publishToShared(username,branch),null);
        }
        return submitPublication(context,false,key,cause,false);
    }

    @Override
    public Publication pullWithReceipt(String username,String branch,String key,String cause) throws IOException {
        portfolioGitPort.requireAvailable();
        var context = resolveWorkspaceContext(username,branch);
        if (context.workspaceId() == null || publications == null) return new Publication(syncFromShared(username,branch),null);
        return submitPull(context,false,key,cause,false);
    }

    private Publication submitPull(WorkspaceContext context,boolean takeSource,String key,String cause,boolean commandErrors) throws IOException {
        var selected=RepositoryContext.workspace(context.repositoryId(),context.workspaceId(),context.currentBranch(),context.username());
        var current=publicationMetadata(selected,false,false);
        var operation=publications.submitPull(selected,takeSource,key,cause,() -> {
            var target=publicationTarget(selected,false,false);
            return new WorkspacePublicationJournal.Source(target.branch(),target.owner().getBaseCommit(),
                    freshHead(target.central(),target.branch()),freshHead(target.local(),selected.branch()),
                    freshHead(target.local(),TRACKING_BRANCH),target.owner().getUsername());
        },this::applyPublicationPhase,commandErrors);
        if (!current.owner().getUsername().equals(operation.workspaceOwner())) throw unavailable();
        return new Publication(operation.resultCommit(),operation);
    }

    private Publication submitPublication(WorkspaceContext context,boolean keep,String key,String cause,boolean commandErrors) throws IOException {
        var selected=RepositoryContext.workspace(context.repositoryId(),context.workspaceId(),context.currentBranch(),context.username());
        requireSourceAuthority(context.username(),context,true);
        var current=publicationMetadata(selected,false,true);
        var operation=publications.submit(selected,keep,key,cause,() -> {
            var target=publicationTarget(selected,false,true);
            return new WorkspacePublicationJournal.Source(target.branch(),target.owner().getBaseCommit(),
                    freshHead(target.central(),target.branch()),freshHead(target.local(),selected.branch()),
                    freshHead(target.local(),TRACKING_BRANCH),target.owner().getUsername());
        },this::applyPublicationPhase,commandErrors);
        if (!current.owner().getUsername().equals(operation.workspaceOwner())) throw unavailable();
        return new Publication(operation.resultCommit(),operation);
    }

    private static String completedPublication(Publication publication) {
        String status=publication.operation().status();
        if ("COMPLETED".equals(status) || "SUPERSEDED".equals(status)) return publication.commit();
        var response="DENIED".equals(status) ? org.springframework.http.HttpStatus.FORBIDDEN :
                "MANUAL_REVIEW".equals(status) ? org.springframework.http.HttpStatus.CONFLICT : org.springframework.http.HttpStatus.ACCEPTED;
        throw new org.springframework.web.server.ResponseStatusException(response,"Publication " + publication.operation().operationId() + ": " + status);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${taxonomy.workspace.publication-recovery-poll:30000}")
    public void recoverDuePublications() {
        publications.recoverDue(this::applyPublicationPhase);
        publications.cleanDue(this::releasePublicationPins);
    }

    public WorkspacePublicationJournal.View publicationStatus(RepositoryContext selected,String id) {
        var receipt=publications.get(selected,id);
        var metadata=publicationMetadata(selected,false,!receipt.pull());
        if (!receipt.workspaceOwner().equals(metadata.owner().getUsername())) throw unavailable();
        return receipt;
    }

    public WorkspacePublicationJournal.View retryPublication(RepositoryContext selected,String id) {
        publicationStatus(selected,id);
        return publications.retry(selected,id,this::applyPublicationPhase);
    }

    public WorkspacePublicationJournal.View archivePublication(RepositoryContext selected,String id) {
        publicationStatus(selected,id);
        publications.archive(selected,id);
        publications.cleanArchived(selected,id,this::releasePublicationPins);
        return publications.get(selected,id);
    }

    private record PublicationTarget(UserWorkspace owner,SystemRepository source,String branch,
            DslGitRepository central,DslGitRepository local,WorkspaceContext workspace,WorkspaceContext centralContext) {}

    private record PublicationMetadata(UserWorkspace owner,SystemRepository source,String branch) {}

    private PublicationMetadata publicationMetadata(RepositoryContext selected,boolean lockWorkspace,boolean publish) {
        if (selected.scope()!=RepositoryScope.WORKSPACE) throw unavailable();
        var owner=(lockWorkspace ? workspaceRepository.findByWorkspaceIdForUpdate(selected.workspaceId())
                : workspaceRepository.findByWorkspaceId(selected.workspaceId())).orElseThrow(GitNativeSyncIntegrationService::unavailable);
        if (owner.isShared()
                || owner.isArchived() || !selected.repositoryId().equals(owner.getSourceRepositoryId())
                || owner.getProvisioningStatus()!=com.taxonomy.workspace.model.WorkspaceProvisioningStatus.READY) throw unavailable();
        var source=requireSourceRepository(owner);
        requireWorkspaceAuthority(selected.username(), source, owner, publish);
        String branch=owner.resolveUpstreamBranch(source);
        return new PublicationMetadata(owner,source,branch);
    }

    private void requireWorkspaceAuthority(String actor, SystemRepository source, UserWorkspace workspace, boolean publish) {
        systemRepositoryService.readCurrentAuthorization(requireRepositoryId(source), current -> {
            if (!sourceMemberships.canUseWorkspace(current, workspace, actor)
                    || !sourceMemberships.canRead(current, actor)
                    || publish && !sourceMemberships.canContribute(current, actor)) throw unavailable();
        });
    }

    private PublicationTarget publicationTarget(RepositoryContext selected,boolean lockWorkspace,boolean publish) {
        var metadata=publicationMetadata(selected,lockWorkspace,publish);
        var owner=metadata.owner();
        var source=metadata.source();
        String branch=metadata.branch();
        var workspace=new WorkspaceContext(selected.username(),selected.workspaceId(),selected.branch(),selected.repositoryId());
        var central=new WorkspaceContext("shared",null,branch,selected.repositoryId());
        return new PublicationTarget(owner,source,branch,repositoryFactory.getCentralRepository(selected.repositoryId()),
                repositoryFactory.openWorkspaceRepository(selected.workspaceId()),workspace,central);
    }

    void applyPublicationPhase(WorkspacePublicationOperation row,Runnable actor) throws IOException {
        var selected=row.context();
        var target=publicationTarget(selected,row.step!=WorkspacePublicationOperation.Step.PROGRESS,!row.isPull());
        if (!row.workspaceOwner().equals(target.owner().getUsername())) throw unavailable();
        if (!java.util.Objects.equals(row.sourceBranch,target.branch())
                || !java.util.Objects.equals(row.forkPoint,target.owner().getBaseCommit())) {
            throw new BranchHeadConflictException(selected.branch(),row.forkPoint,target.owner().getBaseCommit(),"Working copy upstream changed");
        }
        if (row.isPull()) {
            applyPullPhase(row,target,actor);
            return;
        }
        Runnable grant=() -> { actor.run(); requireWorkspaceAuthority(row.username,target.source(),target.owner(),true); };
        if (row.step==WorkspacePublicationOperation.Step.PREPARE) {
            target.central().verifyExpectedHead(target.branch(),row.inputSource);
            target.local().verifyExpectedHead(row.localBranch,row.inputLocal);
            target.local().verifyExpectedHead(TRACKING_BRANCH,row.inputTracking);
            if (row.attempts>1) {
                throw new BranchHeadConflictException(row.localBranch,row.inputLocal,row.inputLocal,
                        "Interrupted preparation requires review of the original editor and portfolio projections");
            }
            editorVersions.version(selected,"Publish architecture workspace",() -> { preparePublication(row,target,grant); return null; });
            return;
        }
        if (row.step==WorkspacePublicationOperation.Step.CANDIDATES) {
            target.central().verifyExpectedHead(row.sourceBranch,row.inputSource);
            target.local().verifyExpectedHead(row.localBranch,row.expectedLocal);
            target.local().verifyExpectedHead(TRACKING_BRANCH,row.expectedTracking);
            prepareCandidates(row,target,grant);
            return;
        }
        grant.run();
        exactPublicationSource(target.central(),row,row.step==WorkspacePublicationOperation.Step.SOURCE_REF);
        exactPublicationRef(target.local(),row.localBranch,row.expectedLocal,row.preparedLocal,
                row.step.ordinal()<=WorkspacePublicationOperation.Step.WORKSPACE_REF.ordinal(),false);
        if (!row.localBranch.equals(TRACKING_BRANCH)) {
            exactPublicationRef(target.local(),TRACKING_BRANCH,row.expectedTracking,row.preparedTracking,
                    row.step.ordinal()<=WorkspacePublicationOperation.Step.TRACKING_REF.ordinal(),false);
        }
        grant.run();
        switch (row.step) {
            case SOURCE_REF -> {
                boolean alreadyPublished=java.util.Objects.equals(row.preparedSource,freshHead(target.central(),row.sourceBranch));
                if (!alreadyPublished) {
                    requireReviewedPortfolio(row.centralPortfolioDigest,"shared",target.centralContext());
                    requireReviewedPortfolio(row.localPortfolioDigest,row.username,target.workspace());
                }
                requireLockedPublicationAuthority(row, actor);
                target.central().publishPreparedDslCommit(row.id,row.sourceBranch,row.inputSource,row.preparedSource);
                var proof=target.central().preparedPublicationTime(row.id,row.sourceBranch,row.inputSource,row.preparedSource);
                if (proof!=null) row.publishedAt=proof.toEpochMilli();
                else if (!alreadyPublished) row.publishedAt=Instant.now().toEpochMilli();
            }
            case WORKSPACE_REF -> editorVersions.applyPreparedVersion(selected, row.expectedLocal, row.preparedLocal,
                    "Publish prepared architecture workspace", () -> {
                        if (!java.util.Objects.equals(row.preparedLocal,freshHead(target.local(),row.localBranch)))
                            requireReviewedPortfolio(row.localPortfolioDigest,row.username,target.workspace());
                        requireLockedPublicationAuthority(row, actor);
                        return target.local().publishPreparedDslCommit(row.id,row.localBranch,row.expectedLocal,row.preparedLocal);
                    });
            case TRACKING_REF -> {
                if (!row.localBranch.equals(TRACKING_BRANCH)) target.local().publishPreparedDslCommit(row.id,TRACKING_BRANCH,row.expectedTracking,row.preparedTracking);
            }
            case CENTRAL_PORTFOLIO, LOCAL_PORTFOLIO -> {
                boolean central=row.step==WorkspacePublicationOperation.Step.CENTRAL_PORTFOLIO;
                String content=target.central().getDslAtCommit(row.preparedSource);
                String owner=central ? "shared" : row.username;
                var scope=central ? target.centralContext() : target.workspace();
                requireReviewedPortfolio(central ? row.centralPortfolioDigest : row.localPortfolioDigest,owner,scope);
                portfolioGitPort.materializePortfolio(content,owner,scope);
                requireLockedPublicationAuthority(row, actor);
                exactPublicationSource(target.central(),row,false);
            }
            case WORKSPACE_METADATA -> {
                var owner=target.owner();
                var tracked=target.local().recordIntegratedUpstream(row.repository,row.sourceBranch,row.preparedSource,row.username);
                owner.setLastFetchedCommit(tracked);
                owner.setLastIntegratedCommit(row.preparedSource);
                owner.setCurrentCommit(row.preparedLocal);
                workspaceRepository.save(owner);
            }
            case SOURCE_METADATA -> {
                if (row.publishedAt!=null) systemRepositoryService.recordLastPush(row.repository,Instant.ofEpochMilli(row.publishedAt));
            }
            case PROGRESS -> syncStateRepository.recordPublication(new WorkspaceSyncScope(row.username,row.repository,row.workspace,row.localBranch),
                    row.preparedSource,row.publishedAt==null ? null : Instant.ofEpochMilli(row.publishedAt),Instant.ofEpochMilli(row.sourceObservedAt),Instant.now());
            default -> throw new IllegalStateException("Unsupported publication phase: " + row.step);
        }
    }

    private void applyPullPhase(WorkspacePublicationOperation row,PublicationTarget target,Runnable actor) throws IOException {
        var local=target.local();
        var source=target.central();
        String current=freshHead(source,row.sourceBranch);
        if (row.inputSource==null || current==null || !source.isAncestor(row.inputSource,current)) {
            throw new BranchHeadConflictException(row.sourceBranch,row.inputSource,current,"The recorded pull source is no longer in this branch");
        }
        Runnable authorize=() -> { actor.run(); requireWorkspaceAuthority(row.username,target.source(),target.owner(),false); };
        if (row.step==WorkspacePublicationOperation.Step.PREPARE) {
            local.verifyExpectedHead(row.localBranch,row.inputLocal);
            local.verifyExpectedHead(TRACKING_BRANCH,row.inputTracking);
            if (row.attempts>1) throw new WorkspacePublicationRecovery.SemanticConflict("Review the interrupted editor preparation");
            source.verifySnapshot(row.inputSource);
            local.verifyUpstreamLineage(source,row.repository,row.sourceBranch,row.inputSource);
            var incoming=requiredSnapshot(new BranchSnapshot(row.inputSource,source.getDslAtCommit(row.inputSource)),"Empty pull source");
            editorVersions.version(row.context(),"Integrate architecture versions from source",() -> {
                authorize.run();
                var base=row.keepWorkspace ? trackingState(local) : initialiseWorkspaceMergeBase(local,source,incoming,
                        row.username,row.localBranch,row.forkPoint);
                portfolioGitPort.commitPortfolio(row.localBranch,"Project requirements before pull",row.username,target.workspace());
                var working=snapshot(local,row.localBranch);
                String ours=requiredSnapshot(working,"Empty working branch");
                String merged=pullContent(row.keepWorkspace,base.baseDsl(),ours,incoming);
                architectureCommands.validateReconciledSource(merged);
                portfolioGitPort.validatePortfolio(ours,merged,row.username,target.workspace());
                local.verifyExpectedHead(row.localBranch,working.head());
                local.verifyExpectedHead(TRACKING_BRANCH,base.baseHead());
                row.localPortfolioDigest=portfolioGitPort.lockedPublicationDigest(row.username,target.workspace());
                row.expectedLocal=working.head();
                row.expectedTracking=base.baseHead();
                row.mergedDigest=com.taxonomy.identity.StableIdentityHash.sha256(merged);
                return null;
            });
            return;
        }
        if (row.step==WorkspacePublicationOperation.Step.CANDIDATES) {
            local.verifyExpectedHead(row.localBranch,row.expectedLocal);
            local.verifyExpectedHead(TRACKING_BRANCH,row.expectedTracking);
            String base=row.expectedTracking==null ? "" : local.getDslAtCommit(row.expectedTracking);
            String ours=local.getDslAtCommit(row.expectedLocal);
            String incoming=source.getDslAtCommit(row.inputSource);
            String merged=pullContent(row.keepWorkspace,base,ours,incoming);
            if (!com.taxonomy.identity.StableIdentityHash.sha256(merged).equals(row.mergedDigest))
                throw new WorkspacePublicationRecovery.SemanticConflict("The recorded pull preview changed");
            var provenance=CommitProvenance.capture("pull",source,row.repository,row.sourceBranch,row.inputSource,
                    integrationBase(target.owner()));
            row.preparedSource=row.inputSource;
            row.preparedLocal=prepareIntegration(row.id,source,row.inputSource,local,row.localBranch,row.expectedLocal,
                    merged,ours,incoming,row.username,"Semantic pull from " + target.source().getSlug(),provenance,authorize);
            if (row.localBranch.equals(TRACKING_BRANCH)) row.preparedTracking=row.preparedLocal;
            else if (row.expectedTracking!=null && valueOrEmpty(merged).equals(valueOrEmpty(base)))
                row.preparedTracking=local.pinExistingDslCommit(row.id,TRACKING_BRANCH,row.expectedTracking,row.expectedTracking);
            else row.preparedTracking=local.prepareDslCommit(row.id,TRACKING_BRANCH,row.expectedTracking,null,merged,row.username,
                        "Update semantic synchronization base",null);
            return;
        }
        authorize.run();
        exactPublicationRef(local,row.localBranch,row.expectedLocal,row.preparedLocal,
                row.step==WorkspacePublicationOperation.Step.WORKSPACE_REF,false);
        if (!row.localBranch.equals(TRACKING_BRANCH))
            exactPublicationRef(local,TRACKING_BRANCH,row.expectedTracking,row.preparedTracking,
                    row.step==WorkspacePublicationOperation.Step.WORKSPACE_REF || row.step==WorkspacePublicationOperation.Step.TRACKING_REF,false);
        switch (row.step) {
            case WORKSPACE_REF -> editorVersions.applyPreparedVersion(row.context(),row.expectedLocal,row.preparedLocal,
                    "Integrate prepared architecture source",() -> {
                        boolean alreadyPublished=row.preparedLocal.equals(freshHead(local,row.localBranch));
                        if (!alreadyPublished) requireReviewedPortfolio(row.localPortfolioDigest,row.username,target.workspace());
                        requireLockedPublicationAuthority(row,actor);
                        String commit=local.publishPreparedDslCommit(row.id,row.localBranch,row.expectedLocal,row.preparedLocal);
                        var when=local.preparedPublicationTime(row.id,row.localBranch,row.expectedLocal,row.preparedLocal);
                        if (when!=null) row.publishedAt=when.toEpochMilli();
                        else if (!alreadyPublished || row.preparedLocal.equals(row.expectedLocal)) row.publishedAt=Instant.now().toEpochMilli();
                        return commit;
                    });
            case TRACKING_REF -> {
                if (!row.localBranch.equals(TRACKING_BRANCH)) {
                    requireLockedPublicationAuthority(row,actor);
                    local.publishPreparedDslCommit(row.id,TRACKING_BRANCH,row.expectedTracking,row.preparedTracking);
                }
            }
            case LOCAL_PORTFOLIO -> {
                requireReviewedPortfolio(row.localPortfolioDigest,row.username,target.workspace());
                requireLockedPublicationAuthority(row,actor);
                portfolioGitPort.materializePortfolio(local.getDslAtCommit(row.preparedLocal),row.username,target.workspace());
            }
            case WORKSPACE_METADATA -> {
                requireLockedPublicationAuthority(row,actor);
                var owner=target.owner();
                String tracked=local.recordIntegratedUpstream(row.repository,row.sourceBranch,row.inputSource,row.username);
                owner.setLastFetchedCommit(tracked);
                owner.setLastIntegratedCommit(row.inputSource);
                owner.setCurrentCommit(row.preparedLocal);
                workspaceRepository.save(owner);
            }
            case PROGRESS -> syncStateRepository.recordPull(new WorkspaceSyncScope(row.username,row.repository,row.workspace,row.localBranch),
                    row.inputSource,row.publishedAt==null ? null : Instant.ofEpochMilli(row.publishedAt),
                    Instant.ofEpochMilli(row.sourceObservedAt),Instant.now());
            default -> throw new IllegalStateException("Unsupported pull phase: " + row.step);
        }
    }

    private String pullContent(boolean takeSource,String base,String ours,String incoming) throws IOException {
        if (takeSource) return incoming;
        var merge=semanticMergeService.mergeContent(base,ours,incoming);
        requireSuccess("Pull from source",merge);
        return merge.mergedText();
    }

    private void requireLockedPublicationAuthority(WorkspacePublicationOperation operation, Runnable actor) {
        actor.run();
        // Portfolio materialization fences the catalogue in this transaction. Suspending it
        // to read the same row on a second connection deadlocks on locking databases.
        var source = systemRepositoryService.lockCurrentPublicationSource(operation.repository);
        boolean owner = operation.workspaceOwner().equals(operation.username);
        boolean authorized = owner
                ? operation.isPull() ? sourceMemberships.canPullLockedSource(source,operation.username)
                        : sourceMemberships.canPublishLockedSource(source,operation.username)
                : sourceMemberships.canCollaborateOnLockedSource(source,operation.username);
        if (!authorized) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Current source repository authority is required");
        }
    }

    private void releasePublicationPins(WorkspacePublicationOperation row,Runnable actor) throws IOException {
        if (row.step==WorkspacePublicationOperation.Step.PREPARE) return;
        var source=systemRepositoryService.getRepository(row.repository);
        var owner=workspaceRepository.findByWorkspaceIdForUpdate(row.workspace);
        if (owner.isPresent() && (!row.workspaceOwner().equals(owner.get().getUsername())
                || !row.repository.equals(owner.get().getSourceRepositoryId()))) throw unavailable();
        Runnable authorize=() -> {
            actor.run();
            if (owner.isPresent()) requireWorkspaceAuthority(row.username,source,owner.get(),!row.isPull());
            else {
                if (!row.workspaceOwner().equals(row.username)) throw unavailable();
                requireSourceAuthority(row.username,source,!row.isPull());
            }
        };
        authorize.run();
        if (!row.isPull()) repositoryFactory.releasePublicationPin(row.repository,null,row.id,row.sourceBranch,row.preparedSource,authorize);
        if (owner.isPresent()) {
            repositoryFactory.releasePublicationPin(row.repository,row.workspace,row.id,row.localBranch,row.preparedLocal,authorize);
            if (!row.localBranch.equals(TRACKING_BRANCH)) {
                repositoryFactory.releasePublicationPin(row.repository,row.workspace,row.id,TRACKING_BRANCH,row.preparedTracking,authorize);
            }
        } else {
            repositoryFactory.requireRemovedWorkspaceStorage(row.workspace,authorize);
        }
    }

    private static void exactPublicationSource(DslGitRepository source,WorkspacePublicationOperation row,boolean allowExpected)
            throws IOException {
        try {
            exactPublicationRef(source,row.sourceBranch,row.inputSource,row.preparedSource,allowExpected,true);
        } catch (WorkspacePublicationRecovery.Superseded later) {
            var when=source.preparedPublicationTime(row.id,row.sourceBranch,row.inputSource,row.preparedSource);
            throw new WorkspacePublicationRecovery.Superseded(true,when);
        }
    }

    private static void exactPublicationRef(DslGitRepository repository,String branch,String expected,String candidate,boolean allowExpected,boolean source)
            throws IOException {
        String head=freshHead(repository,branch);
        if (java.util.Objects.equals(candidate,head) || allowExpected && java.util.Objects.equals(expected,head)) return;
        if (head!=null && repository.isAncestor(candidate,head)) throw new WorkspacePublicationRecovery.Superseded(source);
        throw new BranchHeadConflictException(branch,candidate,head,"Branch differs from recorded publication");
    }

    private static String freshHead(DslGitRepository repository,String branch) throws IOException {
        var ref=repository.readFreshRef(org.eclipse.jgit.lib.Constants.R_HEADS + branch);
        return ref==null || ref.getObjectId()==null ? null : ref.getObjectId().name();
    }

    private void requireReviewedPortfolio(String reviewed,String owner,WorkspaceContext context) throws IOException {
        if (reviewed==null || !reviewed.equals(portfolioGitPort.lockedPublicationDigest(owner,context)))
            throw new WorkspacePublicationRecovery.SemanticConflict("The reviewed portfolio changed before publication");
    }

    private void preparePublication(WorkspacePublicationOperation row, PublicationTarget target,Runnable grant) throws IOException {
        String user=row.username;
        var central=target.central();
        var local=target.local();
        var source=snapshot(central,row.sourceBranch);
        central.verifyExpectedHead(row.sourceBranch,row.inputSource);
        local.verifyUpstreamLineage(central,row.repository,row.sourceBranch,source.head());
        snapshot(local,row.localBranch);
        grant.run();
        WorkspaceMergeState state=row.keepWorkspace ? trackingState(local) : initialiseWorkspaceMergeBase(local,central,
                source.dsl(),user,row.localBranch,row.forkPoint);
        grant.run();
        portfolioGitPort.commitPortfolio(row.localBranch,"Project requirements before push",user,target.workspace());
        var working=snapshot(local,row.localBranch);
        String localDsl=requiredSnapshot(working,"Workspace branch has no content: " + row.localBranch);
        var merge=row.keepWorkspace ? new TaxDslMergeResult(localDsl,java.util.List.of())
                : semanticMergeService.mergeContent(state.baseDsl(),source.dsl(),localDsl);
        requireSuccess("Publish to source",merge);
        String merged=merge.mergedText();
        architectureCommands.validateReconciledSource(merged);
        portfolioGitPort.validatePortfolio(source.dsl(),merged,"shared",target.centralContext());
        portfolioGitPort.validatePortfolio(localDsl,merged,user,target.workspace());
        grant.run();
        local.verifyExpectedHead(TRACKING_BRANCH,state.baseHead());
        local.verifyExpectedHead(row.localBranch,working.head());
        central.verifyExpectedHead(row.sourceBranch,source.head());
        row.centralPortfolioDigest=portfolioGitPort.lockedPublicationDigest("shared",target.centralContext());
        row.localPortfolioDigest=portfolioGitPort.lockedPublicationDigest(user,target.workspace());
        row.expectedLocal=working.head();
        row.expectedTracking=state.baseHead();
        row.mergedDigest=com.taxonomy.identity.StableIdentityHash.sha256(merged);
    }

    private void prepareCandidates(WorkspacePublicationOperation row,PublicationTarget target,Runnable grant) throws IOException {
        var local=target.local();
        var central=target.central();
        String user=row.username;
        String localDsl=local.getDslAtCommit(row.expectedLocal);
        String sourceDsl=row.inputSource==null ? "" : central.getDslAtCommit(row.inputSource);
        String baseDsl=row.expectedTracking==null ? "" : local.getDslAtCommit(row.expectedTracking);
        var merge=row.keepWorkspace ? new TaxDslMergeResult(localDsl,java.util.List.of())
                : semanticMergeService.mergeContent(baseDsl,sourceDsl,localDsl);
        requireSuccess("Recover reviewed publication",merge);
        String merged=merge.mergedText();
        if (!row.mergedDigest.equals(com.taxonomy.identity.StableIdentityHash.sha256(merged))) {
            throw new BranchHeadConflictException(row.localBranch,row.expectedLocal,row.expectedLocal,"Reviewed merge evidence changed");
        }
        var provenance=CommitProvenance.capture("publish",local,"workspace:"+row.workspace,row.localBranch,row.expectedLocal,integrationBase(target.owner()));
        row.preparedSource=prepareIntegration(row.id,local,row.expectedLocal,central,row.sourceBranch,row.inputSource,
                merged,sourceDsl,localDsl,user,"Semantic publish from workspace " + row.workspace,provenance,grant);
        var published=CommitProvenance.capture("pull",central,row.repository,row.sourceBranch,row.preparedSource,integrationBase(target.owner()));
        row.preparedLocal=prepareIntegration(row.id,central,row.preparedSource,local,row.localBranch,row.expectedLocal,
                merged,localDsl,merged,user,"Integrate source changes after publish",published,grant);
        grant.run();
        if (row.localBranch.equals(TRACKING_BRANCH)) row.preparedTracking=row.preparedLocal;
        else if (row.expectedTracking!=null && valueOrEmpty(merged).equals(valueOrEmpty(baseDsl))) {
            row.preparedTracking=local.pinExistingDslCommit(row.id,TRACKING_BRANCH,row.expectedTracking,row.expectedTracking);
        } else {
            row.preparedTracking=local.prepareDslCommit(row.id,TRACKING_BRANCH,row.expectedTracking,null,merged,user,
                    "Update semantic synchronization base",null);
        }
    }

    private static String prepareIntegration(String operation,DslGitRepository incoming,String incomingHead,DslGitRepository destination,
            String branch,String expected,String merged,String prior,String incomingText,String user,String message,
            CommitProvenance provenance,Runnable authorize) throws IOException {
        GitHistoryTransfer.copyCommitClosure(incoming,destination,incomingHead,expected==null ? java.util.List.of() : java.util.List.of(expected));
        authorize.run();
        if (expected!=null && destination.isAncestor(incomingHead,expected)) {
            if (valueOrEmpty(merged).equals(valueOrEmpty(prior))) return destination.pinExistingDslCommit(operation,branch,expected,expected);
            return destination.prepareDslCommit(operation,branch,expected,null,merged,user,message,provenance);
        }
        if ((expected==null || destination.isAncestor(expected,incomingHead)) && valueOrEmpty(merged).equals(valueOrEmpty(incomingText))) {
            return destination.pinExistingDslCommit(operation,branch,expected,incomingHead);
        }
        return destination.prepareDslCommit(operation,branch,expected,incomingHead,merged,user,message,provenance);
    }

    private static org.springframework.security.access.AccessDeniedException unavailable() {
        return new org.springframework.security.access.AccessDeniedException("The selected publication source or working copy is unavailable");
    }

    @Override
    public String syncFromShared(String username, String userBranch) throws IOException {
        portfolioGitPort.requireAvailable();
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() == null) {
            return mergeWithinRepository(
                    username, context, context.currentBranch(), true);
        }
        return pullAcrossRepositories(username, context, context.currentBranch());
    }

    @Override
    public String publishToShared(String username, String userBranch) throws IOException {
        portfolioGitPort.requireAvailable();
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        if (context.workspaceId() == null) {
            return mergeWithinRepository(
                    username, context, context.currentBranch(), false);
        }
        return publishAcrossRepositories(username, context, context.currentBranch());
    }

    @Override
    public String syncFromSharedToWorkspace(String username, String workspaceId)
            throws IOException {
        portfolioGitPort.requireAvailable();
        WorkspaceContext context = resolveExplicitWorkspaceContext(
                username, workspaceId, WORKSPACE_BRANCH);
        return pullAcrossRepositories(username, context, WORKSPACE_BRANCH);
    }

    @Override
    public String publishFromWorkspaceToShared(String username, String workspaceId)
            throws IOException {
        portfolioGitPort.requireAvailable();
        WorkspaceContext context = resolveExplicitWorkspaceContext(
                username, workspaceId, WORKSPACE_BRANCH);
        return publishAcrossRepositories(username, context, WORKSPACE_BRANCH);
    }

    @Override
    public String resolveDiverged(String username,
                                  String userBranch,
                                  DivergedStrategy strategy) throws IOException {
        portfolioGitPort.requireAvailable();
        if (strategy == DivergedStrategy.MERGE) {
            String commit = syncFromShared(username, userBranch);
            return "Semantically merged source into your branch: " + abbreviate(commit);
        }
        WorkspaceContext context = resolveWorkspaceContext(username, userBranch);
        boolean keepMine = strategy == DivergedStrategy.KEEP_MINE;
        String commit = context.workspaceId() == null
                ? chooseWithinRepository(username, context, context.currentBranch(), keepMine)
                : keepMine ? publishAcrossRepositories(username, context, context.currentBranch(), true)
                : pullAcrossRepositories(username, context, context.currentBranch(), true);
        return (keepMine ? "Published your version to the selected source: "
                : "Adopted the selected source version: ") + abbreviate(commit);
    }

    private String pullAcrossRepositories(String username,
                                            WorkspaceContext context,
                                            String userBranch) throws IOException {
        return pullAcrossRepositories(username, context, userBranch, false);
    }

    private String pullAcrossRepositories(String username,
                                            WorkspaceContext context,
                                            String userBranch,
                                            boolean takeSource) throws IOException {
        if (publications!=null) return completedPublication(submitPull(context,takeSource,null,null,true));
        requireSourceAuthority(username, context, false);
        RepositoryContext selected = RepositoryContext.workspace(
                context.repositoryId(), context.workspaceId(), userBranch, username);
        return editorVersions.version(selected, "Integrate architecture versions from source",
                () -> pullAcrossRepositoriesVersion(username, context, userBranch, takeSource));
    }

    private String pullAcrossRepositoriesVersion(String username,
                                          WorkspaceContext context,
                                          String userBranch,
                                          boolean takeSource) throws IOException {
        UserWorkspace workspaceMetadata = requireWorkspace(context.workspaceId());
        SystemRepository sourceMetadata = requireSourceRepository(workspaceMetadata);
        requireMatchingRepository(context, sourceMetadata);
        requireSourceAuthority(username, sourceMetadata, false);
        String sourceBranch = workspaceMetadata.resolveUpstreamBranch(sourceMetadata);
        DslGitRepository sourceRepository =
                repositoryFactory.getCentralRepository(sourceMetadata.getRepositoryId());
        DslGitRepository workspaceGit =
                repositoryFactory.openWorkspaceRepository(context.workspaceId());
        BranchSnapshot sourceSnapshot = snapshot(sourceRepository, sourceBranch);
        workspaceGit.verifyUpstreamLineage(sourceRepository, sourceMetadata.getRepositoryId(), sourceBranch, sourceSnapshot.head());
        requiredSnapshot(sourceSnapshot, "Source branch has no content: " + sourceBranch);
        CommitProvenance provenance = CommitProvenance.capture("pull", sourceRepository,
                sourceMetadata.getRepositoryId(), sourceBranch, sourceSnapshot.head(), integrationBase(workspaceMetadata));
        WorkspaceMergeState state = takeSource ? trackingState(workspaceGit) : initialiseWorkspaceMergeBase(
                workspaceGit, sourceRepository, sourceSnapshot.dsl(), username, userBranch,
                workspaceMetadata.getBaseCommit());

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before pull", username, context);

        BranchSnapshot localSnapshot = snapshot(workspaceGit, userBranch);
        String ours = requiredSnapshot(localSnapshot, "Workspace branch has no content: " + userBranch);
        String theirs = requiredSnapshot(sourceSnapshot, "Source branch has no content: " + sourceBranch);

        TaxDslMergeResult merge = takeSource
                ? new TaxDslMergeResult(theirs, java.util.List.of())
                : semanticMergeService.mergeContent(state.baseDsl(), ours, theirs);
        requireSuccess("Pull from source", merge);
        architectureCommands.validateReconciledSource(merge.mergedText());
        // A deterministic portfolio rejection must precede model and tracking ref publication.
        portfolioGitPort.validatePortfolio(ours, merge.mergedText(), username, context);
        workspaceGit.verifyExpectedHead(TRACKING_BRANCH, state.baseHead());
        requireSourceAuthority(username, sourceMetadata, false);
        sourceRepository.verifyExpectedHead(sourceBranch, sourceSnapshot.head());
        String localCommit = integrateHistory(sourceRepository, sourceBranch, sourceSnapshot.head(),
                workspaceGit, userBranch, localSnapshot.head(), merge.mergedText(), ours, theirs,
                username, "Semantic pull from " + sourceMetadata.getSlug(), provenance, sourceMetadata, false);
        trackBase(workspaceGit, state, merge.mergedText(), username);
        portfolioGitPort.materializePortfolio(merge.mergedText(), username, context);

        // Movement after the checked read remains a future change, never integrated metadata.
        String sourceHead = sourceSnapshot.head();
        workspaceMetadata.setLastFetchedCommit(workspaceGit.recordIntegratedUpstream(
                sourceMetadata.getRepositoryId(), sourceBranch, sourceHead, username));
        workspaceMetadata.setLastIntegratedCommit(sourceHead);
        workspaceMetadata.setCurrentCommit(localCommit);
        workspaceMetadata.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspaceMetadata);
        recordSync(context, userBranch, sourceHead != null ? sourceHead : localCommit, false);
        return localCommit;
    }

    private String publishAcrossRepositories(String username,
                                            WorkspaceContext context,
                                            String userBranch) throws IOException {
        return publishAcrossRepositories(username, context, userBranch, false);
    }

    private String publishAcrossRepositories(String username,
                                            WorkspaceContext context,
                                            String userBranch,
                                            boolean keepWorkspace) throws IOException {
        if (publications!=null) return completedPublication(submitPublication(context,keepWorkspace,null,null,true));
        requireSourceAuthority(username, context, true);
        RepositoryContext selected = RepositoryContext.workspace(
                context.repositoryId(), context.workspaceId(), userBranch, username);
        return editorVersions.version(selected, "Publish architecture workspace",
                () -> publishAcrossRepositoriesVersion(username, context, userBranch, keepWorkspace));
    }

    private String publishAcrossRepositoriesVersion(String username,
                                             WorkspaceContext context,
                                             String userBranch,
                                          boolean keepWorkspace) throws IOException {
        UserWorkspace workspaceMetadata = requireWorkspace(context.workspaceId());
        SystemRepository sourceMetadata = requireSourceRepository(workspaceMetadata);
        requireMatchingRepository(context, sourceMetadata);
        requireSourceAuthority(username, sourceMetadata, true);
        String sourceBranch = workspaceMetadata.resolveUpstreamBranch(sourceMetadata);
        DslGitRepository sourceRepository =
                repositoryFactory.getCentralRepository(sourceMetadata.getRepositoryId());
        DslGitRepository workspaceGit =
                repositoryFactory.openWorkspaceRepository(context.workspaceId());
        BranchSnapshot sourceSnapshot = snapshot(sourceRepository, sourceBranch);
        workspaceGit.verifyUpstreamLineage(sourceRepository, sourceMetadata.getRepositoryId(), sourceBranch, sourceSnapshot.head());
        // Reject an invalid incoming workspace checkpoint before creating synchronization bases.
        snapshot(workspaceGit, userBranch);
        WorkspaceMergeState state = keepWorkspace ? trackingState(workspaceGit) : initialiseWorkspaceMergeBase(
                workspaceGit, sourceRepository, sourceSnapshot.dsl(), username, userBranch,
                workspaceMetadata.getBaseCommit());

        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before push", username, context);

        String sourceDsl = sourceSnapshot.dsl();
        BranchSnapshot localSnapshot = snapshot(workspaceGit, userBranch);
        String workspaceDsl = requiredSnapshot(localSnapshot, "Workspace branch has no content: " + userBranch);
        CommitProvenance provenance = CommitProvenance.capture("publish", workspaceGit,
                "workspace:" + context.workspaceId(), userBranch, localSnapshot.head(), integrationBase(workspaceMetadata));

        TaxDslMergeResult merge = keepWorkspace
                ? new TaxDslMergeResult(workspaceDsl, java.util.List.of())
                : semanticMergeService.mergeContent(state.baseDsl(), sourceDsl, workspaceDsl);
        requireSuccess("Publish to source", merge);
        architectureCommands.validateReconciledSource(merge.mergedText());
        WorkspaceContext centralContext = new WorkspaceContext(
                "shared", null, sourceBranch, requireRepositoryId(sourceMetadata));
        // Check both target databases before either repository accepts the merged history.
        portfolioGitPort.validatePortfolio(sourceDsl, merge.mergedText(), "shared", centralContext);
        portfolioGitPort.validatePortfolio(workspaceDsl, merge.mergedText(), username, context);
        workspaceGit.verifyExpectedHead(TRACKING_BRANCH, state.baseHead());
        // Detect a changed local input before updating the central repository.
        workspaceGit.verifyExpectedHead(userBranch, localSnapshot.head());

        requireSourceAuthority(username, sourceMetadata, true);
        String sourceCommit = integrateHistory(workspaceGit, userBranch, localSnapshot.head(),
                sourceRepository, sourceBranch, sourceSnapshot.head(), merge.mergedText(), sourceDsl, workspaceDsl,
                username, "Semantic publish from workspace " + context.workspaceId(), provenance, sourceMetadata, true);
        CommitProvenance published = CommitProvenance.capture("pull", sourceRepository,
                sourceMetadata.getRepositoryId(), sourceBranch, sourceCommit, integrationBase(workspaceMetadata));
        String workspaceCommit = integrateHistory(sourceRepository, sourceBranch, sourceCommit,
                workspaceGit, userBranch, localSnapshot.head(), merge.mergedText(), workspaceDsl, merge.mergedText(),
                username, "Integrate source changes after publish", published, sourceMetadata, true);
        trackBase(workspaceGit, state, merge.mergedText(), username);

        requireSourceAuthority(username, sourceMetadata, true);
        portfolioGitPort.materializePortfolio(
                merge.mergedText(), "shared", centralContext);
        portfolioGitPort.materializePortfolio(
                merge.mergedText(), username, context);

        workspaceMetadata.setLastFetchedCommit(workspaceGit.recordIntegratedUpstream(
                sourceMetadata.getRepositoryId(), sourceBranch, sourceCommit, username));
        workspaceMetadata.setLastIntegratedCommit(sourceCommit);
        workspaceMetadata.setCurrentCommit(workspaceCommit);
        workspaceMetadata.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspaceMetadata);
        requireSourceAuthority(username, sourceMetadata, true);
        systemRepositoryService.recordLastPush(sourceMetadata.getRepositoryId(), Instant.now());
        recordSync(context, userBranch, sourceCommit, true);
        return sourceCommit;
    }

    /** Explicit conflict choices retain the selected central repository and checked snapshots. */
    private String chooseWithinRepository(String username, WorkspaceContext context,
                                          String userBranch, boolean keepMine) throws IOException {
        SystemRepository metadata = systemRepositoryService.getRepository(context.repositoryId());
        requireMatchingRepository(context, metadata);
        requireSourceAuthority(username, metadata, true);
        String centralBranch = metadata.getDefaultBranch();
        if (centralBranch == null || centralBranch.isBlank()) {
            throw new IllegalStateException("Selected repository has no default branch");
        }
        DslGitRepository repository = repositoryFactory.getCentralRepository(context.repositoryId());
        portfolioGitPort.commitPortfolio(userBranch,
                "Project requirements before explicit conflict choice", username, context);
        String from = keepMine ? userBranch : centralBranch;
        String into = keepMine ? centralBranch : userBranch;
        BranchSnapshot chosen = snapshot(repository, from);
        BranchSnapshot destination = snapshot(repository, into);
        String content = requiredSnapshot(chosen, "Selected conflict side has no content: " + from);
        WorkspaceContext target = new WorkspaceContext(username, null, into, context.repositoryId());
        architectureCommands.validateReconciledSource(content);
        portfolioGitPort.validatePortfolio(destination.dsl(), content, username, target);
        repository.verifyExpectedHead(from, chosen.head());
        requireSourceAuthority(username, metadata, true);
        String commit = commitIfChanged(repository, into, content, destination.dsl(),
                destination.head(), username, "Resolve conflict using " + from);
        portfolioGitPort.materializePortfolio(content, username, target);
        if (keepMine) {
            recordSync(context, userBranch, commit, true);
        } else {
            recordSync(context, userBranch, chosen.head(), false);
        }
        return commit;
    }

    private String mergeWithinRepository(String username,
                                         WorkspaceContext context,
                                         String userBranch,
                                         boolean pull) throws IOException {
        if (context.workspaceId() != null) {
            throw new IllegalArgumentException(
                    "Central synchronization must not carry a workspaceId");
        }
        requireSourceAuthority(username, context, true);
        DslGitRepository repository =
                repositoryFactory.getCentralRepository(context.repositoryId());
        portfolioGitPort.commitPortfolio(userBranch,
                pull ? "Project requirements before sync from shared"
                        : "Project requirements before publish",
                username,
                context);
        String from = pull ? sharedBranch() : userBranch;
        String into = pull ? userBranch : sharedBranch();
        WorkspaceContext target = new WorkspaceContext(username, null, into, context.repositoryId());
        SemanticGitMergeService.MergeOutcome outcome = semanticMergeService.mergeBranches(
                repository, from, into, username, null, candidate -> {
                    portfolioGitPort.validatePortfolio(null, candidate, username, target);
                    requireSourceAuthority(username, context, true);
                });
        requireSuccess(pull ? "Sync" : "Publish", outcome);
        portfolioGitPort.materializePortfolioHead(into, username, target);
        if (pull) {
            recordSync(context, userBranch, repository.getHeadCommit(sharedBranch()), false);
        } else {
            recordSync(context, userBranch, outcome.commitId(), true);
        }
        return outcome.commitId();
    }

    /** Establish the common ancestor before any local portfolio projection is committed. */
    private WorkspaceMergeState initialiseWorkspaceMergeBase(
            DslGitRepository workspaceGit,
            DslGitRepository sourceRepository,
            String capturedSourceDsl,
            String username,
            String userBranch,
            String baseCommit) throws IOException {
        WorkspaceMergeState tracking = trackingState(workspaceGit);
        String existingBase = tracking.baseDsl();
        String userHead = workspaceGit.getHeadCommit(userBranch);
        String existingUserBranch = valueOrEmpty(
                workspaceGit.getDslAtHead(userBranch));
        workspaceGit.verifyExpectedHead(userBranch, userHead);
        if (!existingBase.isBlank() && !existingUserBranch.isBlank()) {
            return tracking;
        }

        String commonBase;
        if (baseCommit != null && !baseCommit.isBlank()) {
            // Initial provisioning records the exact fork point. A newer source
            // HEAD is not the common ancestor of independent local/remote edits.
            sourceRepository.verifySnapshot(baseCommit);
            commonBase = valueOrEmpty(sourceRepository.getDslAtCommit(baseCommit));
        } else {
            // Preserve the compatibility path for metadata without a fork point.
            String source = capturedSourceDsl;
            String seeded = valueOrEmpty(workspaceGit.getDslAtHead(SEEDED_BRANCH));
            commonBase = !seeded.isBlank() ? seeded : source;
        }
        if (commonBase.isBlank()) {
            throw new IOException("Neither source nor workspace seed contains architecture DSL");
        }

        if (existingUserBranch.isBlank()) {
            workspaceGit.commitDslIfHeadMatches(
                    userBranch,
                    null,
                    commonBase,
                    username,
                    "Initialize workspace branch from source architecture");
        }
        String trackingHead = tracking.baseHead();
        if (existingBase.isBlank()) {
            trackingHead = workspaceGit.commitDslIfHeadMatches(
                    TRACKING_BRANCH,
                    null,
                    commonBase,
                    username,
                    "Initialize semantic synchronization base");
        }
        return new WorkspaceMergeState(commonBase, trackingHead);
    }

    /** Resolve the exact persistent repository and active branch for one user. */
    private WorkspaceContext resolveWorkspaceContext(String username, String requestedBranch) {
        RepositoryContext persistent =
                contextResolver.resolveRepositoryContextForUser(username);
        // Preserve the explicit operation scope before adapting to legacy WorkspaceContext.
        // This shared entry covers pull, publish and every conflict-resolution strategy.
        if (persistent.scope() == RepositoryScope.CENTRAL_READ) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Synchronization requires a writable repository context");
        }
        if (persistent.workspaceId() == null) {
            return new WorkspaceContext(
                    persistent.username(),
                    null,
                    persistent.branch(),
                    persistent.repositoryId());
        }
        String branch = requestedBranch;
        if (branch == null || branch.isBlank()
                || (SEEDED_BRANCH.equals(branch)
                && !SEEDED_BRANCH.equals(persistent.branch()))) {
            branch = persistent.branch();
        }
        if (branch == null || branch.isBlank()) {
            branch = WORKSPACE_BRANCH;
        }
        return new WorkspaceContext(
                persistent.username(),
                persistent.workspaceId(),
                branch,
                persistent.repositoryId());
    }

    /** Resolve an explicitly addressed workspace without inventing repository provenance. */
    private WorkspaceContext resolveExplicitWorkspaceContext(
            String username, String workspaceId, String branch) {
        UserWorkspace workspace = requireWorkspace(workspaceId);
        SystemRepository source = requireSourceRepository(workspace);
        return new WorkspaceContext(
                username,
                workspace.getWorkspaceId(),
                branch,
                requireRepositoryId(source));
    }

    private void requireSourceAuthority(String username, WorkspaceContext context, boolean publish) {
        SystemRepository source = context.workspaceId() == null
                ? systemRepositoryService.getRepository(context.repositoryId())
                : requireSourceRepository(requireWorkspace(context.workspaceId()));
        requireMatchingRepository(context, source);
        requireSourceAuthority(username, source, publish);
    }

    private void requireSourceAuthority(String username, SystemRepository source, boolean publish) {
        if (sourceMemberships == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Current source repository read access is required");
        }
        // A repeated lookup in the surrounding journal transaction can return the captured entity.
        // Evaluate metadata and membership together in the owner's fresh, read-only transaction.
        systemRepositoryService.readCurrentAuthorization(requireRepositoryId(source), current -> {
            if (!sourceMemberships.canRead(current, username))
                throw new org.springframework.security.access.AccessDeniedException(
                        "Current source repository read access is required");
            if (publish && !sourceMemberships.canContribute(current, username))
                throw new org.springframework.security.access.AccessDeniedException(
                        "Current source repository CONTRIBUTOR role is required");
        });
    }

    private UserWorkspace requireWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return workspaceRepository.findByWorkspaceId(workspaceId.strip())
                .orElseThrow(() -> new IllegalStateException(
                        "Workspace metadata not found: " + workspaceId));
    }

    private SystemRepository requireSourceRepository(UserWorkspace workspace) {
        String repositoryId = workspace.getSourceRepositoryId();
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalStateException(
                    "Workspace has no sourceRepositoryId: " + workspace.getWorkspaceId());
        }
        return systemRepositoryService.getRepository(repositoryId.strip());
    }

    private static void requireMatchingRepository(
            WorkspaceContext context, SystemRepository sourceRepository) {
        String sourceRepositoryId = requireRepositoryId(sourceRepository);
        String requestRepositoryId = context.repositoryId();
        if (!sourceRepositoryId.equals(requestRepositoryId)) {
            throw new IllegalStateException(
                    "Workspace repository context does not match persisted source provenance: "
                            + "request repositoryId=" + requestRepositoryId
                            + ", persisted sourceRepositoryId=" + sourceRepositoryId);
        }
    }

    private static String requireRepositoryId(SystemRepository repository) {
        if (repository == null || repository.getRepositoryId() == null
                || repository.getRepositoryId().isBlank()) {
            throw new IllegalStateException("Source repository has no repositoryId");
        }
        return repository.getRepositoryId().strip();
    }

    private String sharedBranch() {
        return systemRepositoryService.getSharedBranch();
    }

    private String commitIfChanged(DslGitRepository repository,
                                   String branch,
                                   String merged,
                                   String previous,
                                   String expectedHead,
                                   String username,
                                   String message) throws IOException {
        return commitIfChanged(repository, branch, merged, previous, expectedHead, username, message, null);
    }

    /** Keep original objects and every parent, even when two independent histories have equal content. */
    private String integrateHistory(DslGitRepository incomingRepository, String incomingBranch, String incomingHead,
                                    DslGitRepository destination, String branch, String expectedHead,
                                    String merged, String previous, String incomingContent, String username,
                                    String message, CommitProvenance provenance,
                                    SystemRepository sourceMetadata, boolean publish) throws IOException {
        GitHistoryTransfer.copyCommitClosure(incomingRepository, destination, incomingHead,
                expectedHead == null ? java.util.List.of() : java.util.List.of(expectedHead));
        // Copying never changes refs. Recheck both captured inputs before the destination CAS.
        incomingRepository.verifyExpectedHead(incomingBranch, incomingHead);
        destination.verifyExpectedHead(branch, expectedHead);
        requireSourceAuthority(username, sourceMetadata, publish);
        if (expectedHead != null && destination.isAncestor(incomingHead, expectedHead)) {
            return commitIfChanged(destination, branch, merged, previous, expectedHead, username, message, provenance);
        }
        if ((expectedHead == null || destination.isAncestor(expectedHead, incomingHead))
                && valueOrEmpty(merged).equals(valueOrEmpty(incomingContent))) {
            return destination.fastForwardIfHeadMatches(branch, expectedHead, incomingHead, username, message);
        }
        return destination.commitMergeDslIfHeadMatches(branch, expectedHead, incomingHead,
                merged, username, message, provenance);
    }

    private String commitIfChanged(DslGitRepository repository, String branch, String merged,
                                   String previous, String expectedHead, String username,
                                   String message, CommitProvenance provenance) throws IOException {
        if (expectedHead != null && valueOrEmpty(previous).strip().equals(valueOrEmpty(merged).strip())) {
            return repository.verifyExpectedHead(branch, expectedHead);
        }
        return provenance == null ? repository.commitDslIfHeadMatches(branch, expectedHead, merged, username, message)
                : repository.commitDslIfHeadMatches(branch, expectedHead, merged, username, message, provenance);
    }

    /** Capture the ref before its content and reject an inconsistent observation. */
    private WorkspaceMergeState trackingState(DslGitRepository repository) throws IOException {
        String head = repository.getHeadCommit(TRACKING_BRANCH);
        if (head != null) {
            // This is accepted local bookkeeping, not a new incoming model snapshot.
            // Keep legacy unsigned bases usable while still rejecting bad signatures.
            SignatureStatus status = repository.verifyCommit(head).status();
            if (status != SignatureStatus.UNSIGNED && status != SignatureStatus.VERIFIED) {
                throw new IOException("Synchronization base rejected by signature validation: " + status);
            }
        }
        String dsl = valueOrEmpty(repository.getDslAtHead(TRACKING_BRANCH));
        repository.verifyExpectedHead(TRACKING_BRANCH, head);
        return new WorkspaceMergeState(dsl, head);
    }

    private void trackBase(DslGitRepository workspaceRepository,
                           WorkspaceMergeState expected,
                           String mergedDsl,
                           String username) throws IOException {
        commitIfChanged(workspaceRepository, TRACKING_BRANCH, mergedDsl,
                expected.baseDsl(), expected.baseHead(), username,
                "Update semantic synchronization base");
    }

    @Override
    public SyncState getSyncState(String username) {
        var observation = contextResolver.resolveSyncObservation(username);
        var selected = observation.source();
        var scope = new WorkspaceSyncScope(selected.username(), selected.repositoryId(), selected.workspaceId(), selected.branch());
        requireSourceAuthority(username, systemRepositoryService.getRepository(selected.repositoryId()), false);
        if (observation.lifecycle() != com.taxonomy.workspace.model.WorkspaceProvisioningStatus.READY) {
            var unavailable = new com.taxonomy.workspace.model.WorkspaceBranchSyncState(scope).view();
            unavailable.setSyncStatus(observation.lifecycle().name());
            return unavailable;
        }
        // Older username-wide observations cannot prove which working branch produced them.
        return syncStateRepository.readScope(scope);
    }

    @Override
    public int getLocalChanges(String username, String userBranch) throws IOException {
        var selected = contextResolver.resolveRepositoryContextForRead(username);
        String branch = userBranch == null || userBranch.isBlank() ? selected.branch() : userBranch;
        var source = systemRepositoryService.getRepository(selected.repositoryId());
        requireSourceAuthority(username, source, false);
        var central = repositoryFactory.getCentralRepository(selected.repositoryId());
        int[] counts;
        if (selected.workspaceId() == null) {
            counts = central.getAheadBehindCounts(branch, source.getDefaultBranch());
        } else {
            var workspace = requireWorkspace(selected.workspaceId());
            if (!selected.repositoryId().equals(workspace.getSourceRepositoryId())) {
                throw new IllegalStateException("Selected workspace source changed");
            }
            var local = repositoryFactory.openWorkspaceRepository(selected.workspaceId());
            counts = local.getAheadBehindCounts(branch, central, workspace.resolveUpstreamBranch(source));
        }
        var scope = new WorkspaceSyncScope(selected.username(), selected.repositoryId(), selected.workspaceId(), branch);
        syncStateRepository.recordLocalChanges(scope, counts[0], counts[1], Instant.now());
        return counts[0];
    }

    private void recordSync(WorkspaceContext context, String branch, String commit, boolean published) {
        var scope = new WorkspaceSyncScope(context.username(), context.repositoryId(), context.workspaceId(), branch);
        syncStateRepository.recordSync(scope, commit, Instant.now(), published);
    }

    private BranchSnapshot snapshot(DslGitRepository repository, String branch) throws IOException {
        String head = repository.getHeadCommit(branch);
        if (head != null) repository.verifySnapshot(head);
        return new BranchSnapshot(head, head == null ? "" : valueOrEmpty(repository.getDslAtCommit(head)));
    }

    private String requiredSnapshot(BranchSnapshot snapshot, String message) throws IOException {
        if (snapshot.dsl().isBlank()) throw new IOException(message);
        return snapshot.dsl();
    }

    private record BranchSnapshot(String head, String dsl) { }

    private static String integrationBase(UserWorkspace workspace) {
        String integrated = workspace.getLastIntegratedCommit();
        if (integrated != null && !integrated.isBlank()) return integrated;
        String forkPoint = workspace.getBaseCommit();
        return forkPoint == null || forkPoint.isBlank() ? null : forkPoint;
    }

    private static void requireSuccess(String operation,
                                       SemanticGitMergeService.MergeOutcome outcome)
            throws IOException {
        if (!outcome.success()) {
            throw new WorkspacePublicationRecovery.SemanticConflict(operation + " has semantic conflicts: "
                    + String.join(", ", outcome.conflicts()));
        }
    }

    private static void requireSuccess(String operation,
                                       TaxDslMergeResult result) throws IOException {
        if (!result.isSuccessful()) {
            throw new WorkspacePublicationRecovery.SemanticConflict(operation + " has semantic conflicts: "
                    + String.join(", ", result.conflictIdentifiers()));
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String commit) {
        return commit == null || commit.length() <= 8 ? commit : commit.substring(0, 8);
    }

    private record WorkspaceMergeState(String baseDsl, String baseHead) {
    }
}
