package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.RequirementIntakeDtos.*;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.repository.*;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.SliceImpl;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequirementIntakeCandidatesTest {
    private static final String HEAD = "a".repeat(40);
    private static final String NEXT_HEAD = "b".repeat(40);
    private static final String CANONICAL_REQUIREMENT = """
            requirement CONTROL {
              title: "Control";
              text: "Stop the pump";
              x-version: "3";
              x-content-hash: "untrusted-imported-hash";
            }
            """;
    private final ArchitectureProjectRepository projects=mock(ArchitectureProjectRepository.class);
    private final ProjectRequirementRepository requirements=mock(ProjectRequirementRepository.class);
    private final ProjectPortfolioService portfolio=mock(ProjectPortfolioService.class);
    private final SystemRepositoryRepository repositories=mock(SystemRepositoryRepository.class);
    private final RepositoryMembershipService membership=mock(RepositoryMembershipService.class);
    private final UserWorkspaceRepository workspaces=mock(UserWorkspaceRepository.class);
    private final WorkspacePortfolioDocumentPort documents=mock(WorkspacePortfolioDocumentPort.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<RequirementIntakePolicy> policies=mock(ObjectProvider.class);
    private final RequirementIntakeCandidates candidates=new RequirementIntakeCandidates(
            projects,requirements,portfolio,new WorkspaceSourceDiscoveryService(repositories,membership,workspaces),documents,policies);

    @Test void canonicalCatalogueSizedDocumentRetainsItsRequirementEvidence() throws Exception {
        readable("#"+"x".repeat(1_500_000)+"\n"+CANONICAL_REQUIREMENT);
        var result=candidates.discover(frozen(),"alice");
        assertThat(result.coverage().truncated()).isFalse();
        assertThat(result.candidates()).anySatisfy(candidate->{
            assertThat(candidate.kind()).isEqualTo("ARCHITECTURE_REQUIREMENT");
            assertThat(candidate.text()).isEqualTo("Stop the pump");
            assertThat(candidate.projectId()).isNull();
        });
    }

    @Test void oversizedCanonicalDocumentIsPartialRatherThanEvidenceOfNoMatch() throws Exception {
        readable("#"+"x".repeat(4_100_000)+"\n"+CANONICAL_REQUIREMENT);
        var result=candidates.discover(frozen(),"alice");
        assertThat(result.coverage().truncated()).isTrue();
        assertThat(result.candidates()).noneMatch(candidate->candidate.kind().equals("ARCHITECTURE_REQUIREMENT"));
    }

    @Test void revokedRepositoryReadNeverOpensItsDocument() {
        when(repositories.findByRepositoryId("repo")).thenReturn(Optional.empty());
        assertThatThrownBy(()->candidates.discover(frozen(),"alice")).isInstanceOf(PortfolioException.class);
        verifyNoInteractions(documents);
    }

    @Test void canonicalCandidateResponsePreservesCommitVersionAndActualTextHash() throws Exception {
        readable(CANONICAL_REQUIREMENT);
        var candidate=candidates.discover(frozen(),"alice").candidates().stream()
                .filter(value->value.kind().equals("ARCHITECTURE_REQUIREMENT")).findFirst().orElseThrow();
        var json=new ObjectMapper().valueToTree(candidate.assessed(Classification.CHANGE,"Different control",List.of("Stop the pump")));
        assertThat(json.path("sourceCommit").asText()).isEqualTo(HEAD);
        assertThat(json.path("sourceVersion").asText()).isEqualTo("3");
        assertThat(candidate.contentHash()).isEqualTo(RequirementIntakeService.inputHash("Stop the pump"));
        assertThat(candidate.requirementVersionId()).isNull();
    }

    @Test void centralAdvanceDuringProviderCannotReturnCompleteAdviceForCurrentHead() throws Exception {
        var document=readable(CANONICAL_REQUIREMENT);
        assertThatThrownBy(()->reviewCentral(()->when(document.getHeadCommit("draft")).thenReturn(NEXT_HEAD)))
                .isInstanceOfSatisfying(PortfolioException.class,error->{
                    assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.CONFLICT);
                    assertThat(error.getCode()).isEqualTo("INTAKE_SOURCE_CHANGED");
                });
        verify(document,never()).getDslAtHead(anyString());
        verify(document,never()).getDslAtCommit(NEXT_HEAD);
    }

    @Test void unchangedCanonicalSourceCanReturnCompleteAdviceWithImmutableEvidence() throws Exception {
        readable(CANONICAL_REQUIREMENT);
        var response=reviewCentral(()->{});
        assertThat(response.status()).isEqualTo(Status.COMPLETE);
        assertThat(response.candidates()).filteredOn(candidate->candidate.kind().equals("ARCHITECTURE_REQUIREMENT"))
                .singleElement().satisfies(candidate->{
                    assertThat(candidate.sourceCommit()).isEqualTo(HEAD);
                    assertThat(candidate.sourceVersion()).isEqualTo("3");
                    assertThat(candidate.contentHash()).isEqualTo(RequirementIntakeService.inputHash("Stop the pump"));
                });
    }

    @Test void initiallyEmptyCentralBranchIsAlsoRecheckedAfterProviderWork() throws Exception {
        var document=readable(null);
        when(document.getHeadCommit("draft")).thenReturn(null);
        assertThatThrownBy(()->reviewCentral(()->when(document.getHeadCommit("draft")).thenReturn(NEXT_HEAD)))
                .isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getCode()).isEqualTo("INTAKE_SOURCE_CHANGED"));
    }

    @Test void unavailableCentralHeadAfterProviderIsNotACompleteNoMatch() throws Exception {
        var document=readable(CANONICAL_REQUIREMENT);
        assertThatThrownBy(()->reviewCentral(()->when(document.getHeadCommit("draft")).thenThrow(new java.io.IOException("Offline"))))
                .isInstanceOfSatisfying(PortfolioException.class,error->{
                    assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.UNAVAILABLE);
                    assertThat(error.getCode()).isEqualTo("INTAKE_SOURCE_UNAVAILABLE");
                });
    }

    @Test void sourceAuthorityIsRecheckedAfterItsHeadRead() throws Exception {
        var document=readable(CANONICAL_REQUIREMENT);
        assertThatThrownBy(()->reviewCentral(()->when(document.getHeadCommit("draft")).thenAnswer(call->{
            when(membership.canRead(any(),eq("alice"))).thenReturn(false);
            return HEAD;
        }))).isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
    }

    @Test void privateWorkspaceWithRevokedSourceMembershipNeverSendsCachedTextToProvider() {
        var setup=privateWorkspace();
        when(membership.canRead(setup.repository(),"alice")).thenReturn(false);
        assertThatThrownBy(()->setup.service().review(new Request(7L,null,"Incoming text",null),"alice",setup.context()))
                .isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        verifyNoInteractions(setup.matcher(),requirements,portfolio);
    }

    @Test void privateWorkspaceWithArchivedSourceNeverSendsCachedTextToProvider() {
        var setup=privateWorkspace();
        setup.repository().setLifecycleState(RepositoryLifecycleState.ARCHIVED);
        assertThatThrownBy(()->setup.service().review(new Request(7L,null,"Incoming text",null),"alice",setup.context()))
                .isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        verifyNoInteractions(setup.matcher(),requirements,portfolio);
    }

    @Test void sourceRevocationAfterCaptureStillStopsBeforeProviderSubmission() {
        var setup=privateWorkspace();
        var guarded=spy(candidates);
        doAnswer(call->{
            var captured=call.callRealMethod();
            when(membership.canRead(setup.repository(),"alice")).thenReturn(false);
            return captured;
        }).when(guarded).discover(any(),eq("alice"));
        var service=new RequirementIntakeService(guarded,setup.matcher());
        assertThatThrownBy(()->service.review(new Request(7L,null,"Incoming text",null),"alice",setup.context()))
                .isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        verifyNoInteractions(setup.matcher());
    }

    @Test void sourceMembershipRevokedDuringProviderWorkPreventsDisclosure() {
        var setup=privateWorkspace();
        when(setup.matcher().review(anyString(),anyList(),any())).thenAnswer(call->{
            List<Candidate> evidence=call.getArgument(1);
            assertThat(evidence).singleElement().satisfies(candidate->assertThat(candidate.text()).isEqualTo("Stored text"));
            when(membership.canRead(setup.repository(),"alice")).thenReturn(false);
            return new RequirementIntakeMatcher.Result(Status.COMPLETE,null,"fixture",call.getArgument(1));
        });
        assertThatThrownBy(()->setup.service().review(new Request(7L,null,"Incoming text",null),"alice",setup.context()))
                .isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        verify(setup.matcher()).review(anyString(),anyList(),any());
    }

    @Test void sourceArchivedDuringProviderWorkPreventsDisclosure() {
        var setup=privateWorkspace();
        when(setup.matcher().review(anyString(),anyList(),any())).thenAnswer(call->{
            setup.repository().setLifecycleState(RepositoryLifecycleState.ARCHIVED);
            return new RequirementIntakeMatcher.Result(Status.COMPLETE,null,"fixture",call.getArgument(1));
        });
        assertThatThrownBy(()->setup.service().review(new Request(7L,null,"Incoming text",null),"alice",setup.context()))
                .isInstanceOfSatisfying(PortfolioException.class,error->assertThat(error.getKind()).isEqualTo(PortfolioException.Kind.NOT_FOUND));
        verify(setup.matcher()).review(anyString(),anyList(),any());
    }

    private WorkspacePortfolioDocumentPort.DocumentHandle readable(String dsl) throws Exception {
        var repository=new SystemRepository();repository.setRepositoryId("repo");
        when(repositories.findByRepositoryId("repo")).thenReturn(Optional.of(repository));
        when(membership.canRead(repository,"alice")).thenReturn(true);
        var document=mock(WorkspacePortfolioDocumentPort.DocumentHandle.class);
        when(documents.resolveRepository(any())).thenReturn(document);
        when(document.getHeadCommit("draft")).thenReturn(HEAD);
        when(document.getDslAtCommit(HEAD)).thenReturn(dsl);
        return document;
    }

    @FunctionalInterface private interface ProviderAction { void run() throws Exception; }
    private Response reviewCentral(ProviderAction providerAction) {
        var context=new WorkspaceContext("alice",null,"draft","repo");
        var guarded=spy(candidates);
        doReturn(frozen()).when(guarded).freeze(null,"alice",context);
        var matcher=mock(RequirementIntakeMatcher.class);
        when(matcher.review(anyString(),anyList(),any())).thenAnswer(call->{
            providerAction.run();
            return new RequirementIntakeMatcher.Result(Status.COMPLETE,null,"fixture",call.getArgument(1));
        });
        return new RequirementIntakeService(guarded,matcher).review(new Request(null,null,"Incoming text",null),"alice",context);
    }

    private record PrivateSetup(SystemRepository repository,WorkspaceContext context,
                                RequirementIntakeMatcher matcher,RequirementIntakeService service) { }
    private PrivateSetup privateWorkspace() {
        var repository=new SystemRepository();repository.setRepositoryId("repo");
        when(repositories.findByRepositoryId("repo")).thenReturn(Optional.of(repository));
        when(membership.canRead(repository,"alice")).thenReturn(true);
        var context=new WorkspaceContext("alice","work","draft","repo");
        var workspace=new UserWorkspace();workspace.setWorkspaceId("work");workspace.setUsername("alice");
        workspace.setSourceRepositoryId("repo");workspace.setCurrentBranch("draft");
        when(workspaces.findByWorkspaceId("work")).thenReturn(Optional.of(workspace));
        var project=mock(ArchitectureProject.class);when(project.getId()).thenReturn(7L);when(project.getProjectKey()).thenReturn("PUMP");
        when(portfolio.requireProject(7L,"alice",context)).thenReturn(project);
        when(policies.orderedStream()).thenAnswer(call->Stream.empty());
        var row=mock(ProjectRequirementReadRow.class);
        when(row.id()).thenReturn(2L);when(row.projectId()).thenReturn(7L);when(row.versionId()).thenReturn(12L);
        when(row.requirementKey()).thenReturn("STOP");when(row.title()).thenReturn("Stop pump");when(row.text()).thenReturn("Stored text");
        when(row.contentHash()).thenReturn(RequirementIntakeService.inputHash("Stored text"));
        when(requirements.findIntakeRows(anyString(),eq(7L),any())).thenReturn(new SliceImpl<>(List.of(row)));
        when(requirements.findReadRowByIdAndProjectIdAndScopeKey(eq(2L),eq(7L),anyString())).thenReturn(Optional.of(row));
        var matcher=mock(RequirementIntakeMatcher.class);
        when(matcher.review(anyString(),anyList(),any())).thenAnswer(call->
                new RequirementIntakeMatcher.Result(Status.COMPLETE,null,"fixture",call.getArgument(1)));
        return new PrivateSetup(repository,context,matcher,new RequirementIntakeService(candidates,matcher));
    }
    private RequirementIntakeCandidates.Frozen frozen() {
        return new RequirementIntakeCandidates.Frozen(new Scope("repo",null,"__shared__","draft"),null,List.of(),
                List.of(new RequirementIntakeCandidates.Repository("repo","draft","Control architecture","Pump controls")),
                new Coverage(0,0,false),true);
    }
}
