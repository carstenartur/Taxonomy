package com.taxonomy.portfolio.reformulation;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

public final class ReformulationRestartDriver {
    public static void main(String[] args) throws Exception {
        try(var app=new SpringApplicationBuilder(TaxonomyApplication.class).run("--server.port=0","--spring.datasource.url="+args[0],
                "--spring.datasource.username=SA","--spring.datasource.password=","--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update","--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false","--taxonomy.init.async=false","--gemini.api.key=","--openai.api.key=","--deepseek.api.key=","--qwen.api.key=","--llama.api.key=","--mistral.api.key=")) {
            var service=app.getBean(ReformulationService.class);var projects=app.getBean(ProjectPortfolioService.class);
            var workspaces=app.getBean(WorkspaceManager.class);
            Path identity=Path.of(args[2]);
            if(args[1].equals("write")) {
                var workspace=workspaces.createWorkspace("architect","Restart reformulation","Persistence proof");
                workspace=workspaces.provisionWorkspaceRepository("architect",workspace.getWorkspaceId());
                var scope=new WorkspaceContext("architect",workspace.getWorkspaceId(),workspace.getCurrentBranch(),workspace.getSourceRepositoryId());
                var project=projects.createProject(new CreateProjectRequest("P","Restart","Context",ProjectStatus.ACTIVE,null,null,null,null),"architect",scope);
                var requirement=projects.createRequirement(project.id(),new CreateRequirementRequest("R","Original","Only terminal. 2 seconds.",
                        RequirementStatus.APPROVED,50,Criticality.HIGH,RequirementType.FUNCTIONAL,ReviewStatus.CONFIRMED,"architect","Original",null),"architect",scope);
                var analysis=app.getBean(PortfolioAnalysisPersistenceService.class);
                var job=analysis.createOrReuseJob(project.id(),List.of(requirement.id()),null,25,"restart", "architect",scope);
                var result=new AnalysisResult(Map.of(),List.of());result.setStatus("SUCCESS");String snapshot=UUID.randomUUID().toString();
                analysis.persistSnapshot(job.items().getFirst().id(),job.id(),project.id(),PortfolioScope.key("architect",scope),snapshot,"session",result,null,null,null,null,null,"p","t","architect",scope,1);
                var proposal=service.create(project.id(),requirement.id(),new ReformulationDtos.CreateRequest(requirement.currentVersionId(),snapshot,"en"),"architect",scope);
                service.saveDraft(project.id(),requirement.id(),proposal.id(),1,new ReformulationDtos.SaveDraftRequest("Draft two","Human edit"),"architect",scope);
                service.saveDraft(project.id(),requirement.id(),proposal.id(),2,new ReformulationDtos.SaveDraftRequest("Draft three","Human edit"),"architect",scope);
                Files.write(identity,List.of(scope.repositoryId(),scope.workspaceId(),scope.currentBranch(),project.id().toString(),requirement.id().toString(),proposal.id(),requirement.currentVersionId().toString(),snapshot));
            } else {
                var values=Files.readAllLines(identity);var scope=new WorkspaceContext("architect",values.get(1),values.get(2),values.get(0));
                long project=Long.parseLong(values.get(3)),requirement=Long.parseLong(values.get(4));String proposalId=values.get(5);
                var proposal=service.get(project,requirement,proposalId,"architect",scope);
                assertThat(proposal.baseline().originalText()).isEqualTo("Only terminal. 2 seconds.");
                assertThat(proposal.baseline().sourceVersionId()).isEqualTo(Long.parseLong(values.get(6)));
                assertThat(proposal.baseline().snapshotId()).isEqualTo(values.get(7));
                assertThat(proposal.currentRevision().text()).isEqualTo("Draft three");
                assertThat(service.revision(project,requirement,proposalId,2,"architect",scope).text()).isEqualTo("Draft two");
                assertThat(projects.listRequirementVersions(project,requirement,"architect",scope)).hasSize(1);
                assertThat(projects.getRequirement(project,requirement,"architect",scope).currentVersionId()).isEqualTo(Long.parseLong(values.get(6)));
                assertThat(projects.getRequirement(project,requirement,"architect",scope).currentAnalysisSnapshotId()).isEqualTo(values.get(7));
            }
        }
        System.out.println("REFORMULATION_RESTART_OK "+args[1]);
    }
}
