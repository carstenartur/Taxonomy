package com.taxonomy.portfolio.reformulation;

import com.taxonomy.TaxonomyApplication;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.reformulation.*;
import com.taxonomy.workspace.service.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Real authenticated HTTP and file-database export contract; saved model output is authored data. */
public final class ReformulationReportChecks {
    static final String ORIGINAL = "Arbeitszeiterfassung. Keine automatische Freigabe.";
    static final String TEXT = "Arbeitszeiten erfassen.\n```\n<script>alert('unsafe')</script>\nDie Erfassungsart bleibt offen. ÄÖÜ.";
    static final String PASSWORD = "Report-Test-Only-719!";
    record Fixture(long project, long requirement, String proposal, String snapshot, long version, WorkspaceContext scope) {
        String path() { return "/api/projects/"+project+"/requirements/"+requirement+"/reformulations/"+proposal; }
    }
    private ReformulationReportChecks() {}
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]); Files.createDirectories(dir);
        try (var app=new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:"+dir.resolve("db").toAbsolutePath()+";shutdown=true",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=true",
                "--taxonomy.admin-password="+PASSWORD,"--taxonomy.security.require-password-change=false")) {
            ObjectMapper json=app.getBean(ObjectMapper.class);
            var proposals=app.getBean(ReformulationService.class); var projects=app.getBean(ProjectPortfolioService.class);
            var adoption=app.getBean(ReformulationAdoptionService.class); var workspaces=app.getBean(WorkspaceManager.class);
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port");
            if(args.length>1 && args[1].equals("read")) {
                var data=json.readTree(Files.readString(dir.resolve("identity.json")));
                var f=json.treeToValue(data.path("fixture"),Fixture.class); workspaces.switchWorkspace("admin",f.scope.workspaceId());
                var before=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
                for(String format:List.of("json","md","html")) {
                    check(get(http,base+f.path()+"/revisions/2/export?format="+format,200,true).body().equals(Files.readString(dir.resolve("revision."+format))),"Historical revision changed across restart: "+format);
                    check(get(http,base+f.path()+"/adoptions/"+data.path("commandId").asText()+"/export?format="+format,200,true).body().equals(Files.readString(dir.resolve("adoption."+format))),"Receipt changed across restart: "+format);
                }
                check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Export after restart changed requirement");
                System.out.println("REFORMULATION_REPORT_RESTART_OK"); return;
            }
            Fixture f=fixture(app); var before=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
            var saved=proposals.get(f.project,f.requirement,f.proposal,"admin",f.scope);
            String path=base+f.path()+"/revisions/2/export";
            for(String format:List.of("json","md","html")) {
                var response=get(http,path+"?format="+format,200,true);
                check(response.body().contains(ORIGINAL),"Original missing: "+format);
                check(response.body().contains("Browser oder Terminal?"),"Question missing: "+format);
                check(response.body().contains("MODEL_ADDITION"),"Provenance missing: "+format);
                if(format.equals("json")) {
                    var report=json.readTree(response.body());
                    check(report.path("schemaVersion").asText().equals("reformulation-report-v1"),"Published report schema contract differs");
                    check(report.path("proposal").path("revision").asLong()==2,"Published proposal revision contract differs");
                    check(report.path("kind").asText().equals("PROPOSAL_REVISION"),"Wrong report kind");
                    check(json.treeToValue(report.path("revision"),ReformulationDtos.Revision.class).equals(saved.currentRevision()),"Export changed saved revision");
                    check(!report.toString().contains("frozenContext") && !report.toString().contains("snapshotPayload"),"Internal archive leaked");
                }
                if(format.equals("html")) check(!response.body().contains("<script>") && response.body().contains("&lt;script&gt;"),"Unsafe HTML export");
                Files.writeString(dir.resolve("revision."+format),response.body());
            }
            check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Export activated original");
            check(saved.equals(proposals.get(f.project,f.requirement,f.proposal,"admin",f.scope)),"Export edited proposal");
            get(http,path+"?format=pdf",400,true); get(http,path.replace("/2/","/9999/"),404,true); get(http,path,401,false);
            var mutation=http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization",basic()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            check(mutation.statusCode()==405,"Export must be GET-only: "+mutation.statusCode());
            get(http,base+f.path()+"/adoptions/"+UUID.randomUUID()+"/export",404,true);
            proposals.answer(f.project,f.requirement,f.proposal,2,new ReformulationDtos.AnswerRequest("q-1","ANSWER",List.of("Terminal"),"","Bewusste Terminalwahl"),"admin",f.scope);
            var preview=adoption.preview(f.project,f.requirement,f.proposal,3,"admin",f.scope);
            String command=UUID.randomUUID().toString();
            var receipt=adoption.adopt(f.project,f.requirement,f.proposal,3,new ReformulationAdoptionDtos.ConfirmRequest(command,preview.content().id(),preview.hash(),true,true,"Reviewed draft"),"admin",f.scope);
            for(String format:List.of("json","md","html")) {
                check(get(http,path+"?format="+format,200,true).body().equals(Files.readString(dir.resolve("revision."+format))),"Historical proposal export changed after adoption");
                var response=get(http,base+f.path()+"/adoptions/"+command+"/export?format="+format,200,true);
                check(response.body().contains("Terminal") && response.body().contains("Bewusste Terminalwahl"),"Human answer omitted");
                if(format.equals("json")) {
                    var report=json.readTree(response.body()); check(report.path("kind").asText().equals("ADOPTION_RECEIPT"),"Receipt presented as draft-only");
                    check(json.treeToValue(report.path("adoption"),ReformulationAdoptionDtos.Result.class).equals(receipt),"Changed receipt");
                    check(json.treeToValue(report.path("preview"),ReformulationAdoptionDtos.Preview.class).equals(preview),"Lost immutable preview");
                }
                Files.writeString(dir.resolve("adoption."+format),response.body());
            }
            proposals.saveDraft(f.project,f.requirement,f.proposal,3,new ReformulationDtos.SaveDraftRequest("Later offer text","Later draft"),"admin",f.scope);
            projects.addRequirementVersion(f.project,f.requirement,new CreateRequirementVersionRequest("Independent later version","Later",null),"admin",f.scope);
            var after=projects.getRequirement(f.project,f.requirement,"admin",f.scope); var offerAfter=proposals.get(f.project,f.requirement,f.proposal,"admin",f.scope);
            for(String format:List.of("json","md","html")) check(get(http,base+f.path()+"/adoptions/"+command+"/export?format="+format,200,true).body().equals(Files.readString(dir.resolve("adoption."+format))),"Receipt export used current mutable data");
            check(after.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)) && offerAfter.equals(proposals.get(f.project,f.requirement,f.proposal,"admin",f.scope)),"Report read mutated later work");
            check(app.getBean(JdbcTemplate.class).queryForObject("select count(*) from reformulation_usage_attempt",Long.class)==0L,"Report invoked model");
            Files.writeString(dir.resolve("identity.json"),json.writeValueAsString(Map.of("fixture",f,"commandId",command)));
            var other=fixture(app); get(http,path,404,true); get(http,base+f.path()+"/adoptions/"+command+"/export",404,true);
            get(http,base+other.path()+"/adoptions/"+command+"/export",404,true);
            System.out.println("REFORMULATION_REPORT_HTTP_OK");
        }
    }
    static Fixture fixture(ConfigurableApplicationContext app) {
        var wsManager=app.getBean(WorkspaceManager.class); var projects=app.getBean(ProjectPortfolioService.class); var proposals=app.getBean(ReformulationService.class);
        var ws=wsManager.createWorkspace("admin","Report "+UUID.randomUUID(),"Read-only history"); ws=wsManager.provisionWorkspaceRepository("admin",ws.getWorkspaceId());wsManager.switchWorkspace("admin",ws.getWorkspaceId());
        var scope=new WorkspaceContext("admin",ws.getWorkspaceId(),ws.getCurrentBranch(),ws.getSourceRepositoryId());
        var p=projects.createProject(new CreateProjectRequest("P","Report","Fixture",ProjectStatus.ACTIVE,null,null,null,null),"admin",scope);
        var r=projects.createRequirement(p.id(),new CreateRequirementRequest("R","Time",ORIGINAL,RequirementStatus.APPROVED,50,Criticality.HIGH,RequirementType.FUNCTIONAL,ReviewStatus.CONFIRMED,"admin","Original",null),"admin",scope);
        var analyses=app.getBean(PortfolioAnalysisPersistenceService.class);var job=analyses.createOrReuseJob(p.id(),List.of(r.id()),null,25,UUID.randomUUID().toString(),"admin",scope);
        var root=app.getBean(TaxonomyService.class).getFullTree().stream().filter(n->n.getCode().equals("BP")).findFirst().orElseThrow();
        var analysis=new AnalysisResult(Map.of("BP",50),List.of(root));analysis.setStatus("SUCCESS");String snapshot=UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(),job.id(),p.id(),PortfolioScope.key("admin",scope),snapshot,"fixture",analysis,null,null,null,null,null,"p","t","admin",scope,1);
        var offer=proposals.create(p.id(),r.id(),new ReformulationDtos.CreateRequest(r.currentVersionId(),snapshot,"de"),"admin",scope);
        var run=proposals.beginRun(p.id(),r.id(),offer.id(),1,"TEST","fixture","v1","v1","frozen","admin",scope);
        var statement=new Statement("s-1",TEXT,List.of(),Statement.Provenance.MODEL_ADDITION,List.of("BP"),List.of("q-1"),"Nur bei bestätigter Erfassungsart",Statement.EditingOrigin.MODEL,"UNREVIEWED");
        var q=new DecisionQuestion("q-1",new DecisionQuestion.Key("time","channel","BP"),"Browser oder Terminal?",List.of(new DecisionQuestion.Discovery("BP","Erfassung","Eingabe ist unvollständig",List.of(),List.of("BP"),List.of())),List.of("s-1"),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE,List.of("Browser","Terminal"),null,null,null),List.of(),List.of(),"Erfassungsweg auswählen",DecisionQuestion.State.OPEN);
        var section=new Section("BP","BP","Erfassung",TEXT,List.of(),List.of("s-1"),List.of("q-1"));
        proposals.finishRun(p.id(),r.id(),offer.id(),run.id(),new ReformulationDocument(TEXT,List.of(section),List.of(statement),List.of(q),new ValidationReport(List.of()),List.of()),null,"admin",scope);
        return new Fixture(p.id(),r.id(),offer.id(),snapshot,r.currentVersionId(),scope);
    }
    static HttpResponse<String> get(HttpClient http,String url,int expected,boolean authenticated) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));if(authenticated)builder.header("Authorization",basic());
        var response=http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(response.statusCode()==expected,"Expected "+expected+" but got "+response.statusCode()+" "+url+" "+response.body());
        if(expected==200) {
            check(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),"Private report cached");
            check(response.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"),"Report is not a download");
            check(response.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"),"Missing nosniff");
        }
        return response;
    }
    private static String basic(){return "Basic "+Base64.getEncoder().encodeToString(("admin:"+PASSWORD).getBytes(StandardCharsets.UTF_8));}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
