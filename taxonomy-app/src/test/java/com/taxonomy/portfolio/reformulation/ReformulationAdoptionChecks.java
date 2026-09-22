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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated real-server adoption contract; only stored analysis/model outputs are authored fixtures. */
public final class ReformulationAdoptionChecks {
    static final String ORIGINAL = "Arbeitszeiterfassung. Keine automatische Freigabe.";
    static final String TEXT = "Arbeitszeiten erfassen. Die Erfassungsart bleibt zu entscheiden.";
    static final String PASSWORD = "Adoption-Test-Only-653!";
    final ObjectMapper json;
    final ProjectPortfolioService projects;
    final ReformulationService proposals;
    final WorkspaceManager workspaces;
    final ConfigurableApplicationContext app;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final String base;
    record Fixture(long project, long requirement, String proposal, String snapshot, long version, WorkspaceContext scope) {
        String path() { return "/api/projects/"+project+"/requirements/"+requirement+"/reformulations/"+proposal; }
    }
    private ReformulationAdoptionChecks(ConfigurableApplicationContext app) {
        this.app=app; json=app.getBean(ObjectMapper.class); projects=app.getBean(ProjectPortfolioService.class);
        proposals=app.getBean(ReformulationService.class); workspaces=app.getBean(WorkspaceManager.class);
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port");
    }
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]); Files.createDirectories(dir);
        try (var app=new SpringApplicationBuilder(TaxonomyApplication.class).run(
                "--server.port=0", "--spring.datasource.url=jdbc:hsqldb:file:"+dir.resolve("db").toAbsolutePath()+";shutdown=true",
                "--spring.datasource.username=SA", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.hsqldb.jdbc.JDBCDriver",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
                "--embedding.enabled=false", "--taxonomy.init.async=false", "--llm.mock=true",
                "--taxonomy.admin-password="+PASSWORD,"--taxonomy.security.require-password-change=false")) {
            ReformulationAdoptionSchemaContract.verify(app.getBean(javax.sql.DataSource.class));
            var checks=new ReformulationAdoptionChecks(app);
            if(args.length>1 && args[1].equals("read")) checks.restart(dir);
            else checks.write(dir);
        }
    }
    Fixture fixture() {
        var ws=workspaces.createWorkspace("admin","Adoption "+UUID.randomUUID(),"Explicit version transition");
        ws=workspaces.provisionWorkspaceRepository("admin",ws.getWorkspaceId()); workspaces.switchWorkspace("admin",ws.getWorkspaceId());
        var scope=new WorkspaceContext("admin",ws.getWorkspaceId(),ws.getCurrentBranch(),ws.getSourceRepositoryId());
        var p=projects.createProject(new CreateProjectRequest("P","Adoption","Fixture",ProjectStatus.ACTIVE,null,null,null,null),"admin",scope);
        var r=projects.createRequirement(p.id(),new CreateRequirementRequest("R","Time",ORIGINAL,RequirementStatus.APPROVED,50,
                Criticality.HIGH,RequirementType.FUNCTIONAL,ReviewStatus.CONFIRMED,"admin","Original",null),"admin",scope);
        var a=app.getBean(PortfolioAnalysisPersistenceService.class);
        var job=a.createOrReuseJob(p.id(),List.of(r.id()),null,25,UUID.randomUUID().toString(),"admin",scope);
        var root=app.getBean(TaxonomyService.class).getFullTree().stream().filter(n->n.getCode().equals("BP")).findFirst().orElseThrow();
        var analysis=new AnalysisResult(Map.of("BP",50),List.of(root)); analysis.setStatus("SUCCESS");
        String snapshot=UUID.randomUUID().toString();
        a.persistSnapshot(job.items().getFirst().id(),job.id(),p.id(),PortfolioScope.key("admin",scope),snapshot,"fixture",analysis,
                null,null,null,null,null,"p","t","admin",scope,1);
        var offer=proposals.create(p.id(),r.id(),new ReformulationDtos.CreateRequest(r.currentVersionId(),snapshot,"de"),"admin",scope);
        var run=proposals.beginRun(p.id(),r.id(),offer.id(),1,"TEST","fixture","v1","v1","frozen","admin",scope);
        var statement=new Statement("s-1",TEXT,List.of(),Statement.Provenance.MODEL_ADDITION,List.of("BP"),List.of("q-1"),
                "Erfassungsart offen",Statement.EditingOrigin.MODEL,"UNREVIEWED");
        var q=new DecisionQuestion("q-1",new DecisionQuestion.Key("time","channel","BP"),"Browser oder Terminal?",
                List.of(new DecisionQuestion.Discovery("BP","Erfassung","Eingabe ist unvollständig",List.of(),List.of("BP"),List.of())),
                List.of("s-1"),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.SINGLE_CHOICE,List.of("Browser","Terminal"),null,null,null),
                List.of(),List.of(),"Erfassungsweg auswählen",DecisionQuestion.State.OPEN);
        var section=new Section("BP","BP","Erfassung",TEXT,List.of(),List.of("s-1"),List.of("q-1"));
        proposals.finishRun(p.id(),r.id(),offer.id(),run.id(),new ReformulationDocument(TEXT,List.of(section),List.of(statement),
                List.of(q),new ValidationReport(List.of()),List.of()),null,"admin",scope);
        return new Fixture(p.id(),r.id(),offer.id(),snapshot,r.currentVersionId(),scope);
    }
    void write(Path dir) throws Exception {
        Fixture f=fixture(); var before=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
        var beforeOffer=proposals.get(f.project,f.requirement,f.proposal,"admin",f.scope);
        JsonNode preview=request("POST",f.path()+"/adoption-previews",Map.of(),2,201);
        check(preview.path("content").path("originalText").asText().equals(ORIGINAL),"Preview lost original");
        check(preview.path("content").path("finalText").asText().equals(TEXT),"Preview lost saved proposal");
        check(preview.path("content").path("revision").path("questions").size()==1,"Preview lost questions");
        check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Preview changed active requirement");
        check(beforeOffer.equals(proposals.get(f.project,f.requirement,f.proposal,"admin",f.scope)),"Preview changed offer");
        request("POST",f.path()+"/adoption-previews",Map.of(),null,428);
        request("POST",f.path()+"/adoption-previews",Map.of(),1,412);
        var command=command(preview); String confirm=f.path()+"/adoptions";
        var unconfirmed=new LinkedHashMap<>(command);unconfirmed.put("confirmed",false);request("POST",confirm,unconfirmed,2,422);
        var unacknowledged=new LinkedHashMap<>(command);unacknowledged.put("acknowledgeWarnings",false);request("POST",confirm,unacknowledged,2,422);
        var tampered=new LinkedHashMap<>(command);tampered.put("previewHash","0".repeat(64));request("POST",confirm,tampered,2,409);
        check(projects.listRequirementVersions(f.project,f.requirement,"admin",f.scope).size()==1,"Rejected confirmation created a version");
        proposals.saveDraft(f.project,f.requirement,f.proposal,2,new ReformulationDtos.SaveDraftRequest("  "+TEXT+"\n","Manual edit"),"admin",f.scope);
        request("POST",confirm,command,2,412);
        preview=request("POST",f.path()+"/adoption-previews",Map.of(),3,201); command=command(preview);
        check(preview.path("content").path("finalText").asText().equals(TEXT),"Preview must show exact normalized version text");
        var result=request("POST",confirm,command,3,200);
        long target=result.path("targetVersionId").asLong(); check(target!=f.version,"No new version after confirmation");
        var after=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
        check(after.currentVersionId()==target && after.currentVersion().text().equals(TEXT),"Wrong active text");
        check(after.status()==RequirementStatus.DRAFT && after.reviewStatus()==ReviewStatus.PROPOSED,"Old approval transferred");
        check(Objects.equals(after.currentAnalysisSnapshotId(),before.currentAnalysisSnapshotId()),"Old analysis was deleted or replaced");
        check(result.path("analysisNeedsRefresh").asBoolean(),"New text needs a new analysis");
        check(projects.listRequirementVersions(f.project,f.requirement,"admin",f.scope).stream().anyMatch(v->v.id()==f.version && v.text().equals(ORIGINAL)),"Original version was overwritten");
        check(request("POST",confirm,command,3,200).equals(result),"Repeated confirmation changed receipt");
        var changed=new LinkedHashMap<>(command);changed.put("rationale","Different command");request("POST",confirm,changed,3,409);
        var duplicate=new LinkedHashMap<>(command);duplicate.put("commandId",UUID.randomUUID().toString());request("POST",confirm,duplicate,3,409);
        // A retried old command must not select its old result after a later independent edit.
        var later=projects.addRequirementVersion(f.project,f.requirement,new CreateRequirementVersionRequest("Later independent text","Later",null),"admin",f.scope);
        check(request("POST",confirm,command,3,200).equals(result),"Historical replay changed receipt");
        check(projects.getRequirement(f.project,f.requirement,"admin",f.scope).currentVersionId().equals(later.id()),"Retry reverted later active text");
        var stale=request("POST",f.path()+"/adoption-previews",Map.of(),3,201);
        projects.updateRequirement(f.project,f.requirement,new UpdateRequirementRequest("New title",null,null,null,null,null,null),"admin",f.scope);
        request("POST",confirm,command(stale),3,409);
        // Reuse of an identical old version is explicit, not a duplicate version.
        var reuse=request("POST",f.path()+"/adoption-previews",Map.of(),3,201);
        var reused=request("POST",confirm,command(reuse),3,200);
        check(reused.path("targetVersionId").asLong()==target && projects.listRequirementVersions(f.project,f.requirement,"admin",f.scope).size()==3,"Identical historical text duplicated");
        String previewPath=f.path()+"/adoption-previews/"+preview.path("content").path("id").asText();
        check(request("GET",previewPath,null,null,200).equals(preview),"Historical preview changed");
        Files.writeString(dir.resolve("saved.json"),json.writeValueAsString(Map.of("scope",f.scope,"fixture",f,"command",command,"result",result,"preview",preview)));
        var foreign=fixture();request("GET",previewPath,null,null,404);
        workspaces.switchWorkspace("admin",f.scope.workspaceId());
        var anonymous=http.send(HttpRequest.newBuilder(URI.create(base+previewPath)).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
        check(anonymous.statusCode()==401,"Anonymous preview leaked");
        concurrent(); rollback(); decisionsAndStructuralErrors();
        System.out.println("REFORMULATION_ADOPTION_HTTP_OK");
    }
    void concurrent() throws Exception {
        var f=fixture();var preview=request("POST",f.path()+"/adoption-previews",Map.of(),2,201);var command=command(preview);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->request("POST",f.path()+"/adoptions",command,2,200));
            var b=pool.submit(()->request("POST",f.path()+"/adoptions",command,2,200));
            check(a.get(30,TimeUnit.SECONDS).equals(b.get(30,TimeUnit.SECONDS)),"Concurrent retries differ");
        }
        check(projects.listRequirementVersions(f.project,f.requirement,"admin",f.scope).size()==2,"Concurrent duplicate versions");
    }
    void rollback() throws Exception {
        var f=fixture();var preview=request("POST",f.path()+"/adoption-previews",Map.of(),2,201);var command=command(preview);
        var jdbc=app.getBean(JdbcTemplate.class);var before=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
        jdbc.execute("alter table reformulation_adoption add constraint test_fail_receipt check (requirement_id <> "+f.requirement+" or target_version_id < 0)");
        try { request("POST",f.path()+"/adoptions",command,2,409); }
        finally { jdbc.execute("alter table reformulation_adoption drop constraint test_fail_receipt"); }
        check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Receipt failure did not roll back active state");
        check(projects.listRequirementVersions(f.project,f.requirement,"admin",f.scope).size()==1,"Receipt failure retained new version");
        request("POST",f.path()+"/adoptions",command,2,200);
    }
    void decisionsAndStructuralErrors() throws Exception {
        var f=fixture();var preview=request("POST",f.path()+"/adoption-previews",Map.of(),2,201);
        proposals.answer(f.project,f.requirement,f.proposal,2,new ReformulationDtos.AnswerRequest("q-1","ANSWER",List.of("Terminal"),"","Chosen terminal"),"admin",f.scope);
        request("POST",f.path()+"/adoptions",command(preview),2,412);
        var answered=request("POST",f.path()+"/adoption-previews",Map.of(),3,201);
        check(answered.path("content").path("revision").path("answers").get(0).path("values").get(0).asText().equals("Terminal"),"Preview lost saved answer");
        var adopted=request("POST",f.path()+"/adoptions",command(answered),3,200);
        check(adopted.path("rationale").asText().equals("Explicit draft adoption") && adopted.path("warningsAcknowledged").asBoolean(),"Receipt lost explicit decision rationale");
        projects.updateRequirement(f.project,f.requirement,new UpdateRequirementRequest(null,RequirementStatus.APPROVED,null,null,null,ReviewStatus.CONFIRMED,null),"admin",f.scope);
        var before=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
        var same=request("POST",f.path()+"/adoption-previews",Map.of(),3,201);
        check(!request("POST",f.path()+"/adoptions",command(same),3,200).path("textChanged").asBoolean(),"Same text counted as edit");
        check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Same current text reset approval");
        // Deliberately corrupt only a private test revision to check the independent adoption guard.
        var jdbc=app.getBean(JdbcTemplate.class);
        String raw=jdbc.queryForObject("select revision_payload from reformulation_revision where proposal_id=? and revision_number=3",String.class,f.proposal);
        var payload=(tools.jackson.databind.node.ObjectNode)json.readTree(raw);
        payload.set("validation",json.valueToTree(new ValidationReport(List.of(new ValidationReport.Finding(ValidationReport.Kind.STRUCTURAL_LOSS,"TEST_LOSS","Unaccounted original",List.of(),List.of())))));
        jdbc.update("update reformulation_revision set revision_payload=? where proposal_id=? and revision_number=3",json.writeValueAsString(payload),f.proposal);
        var invalid=request("POST",f.path()+"/adoption-previews",Map.of(),3,201);
        check(invalid.path("content").path("blockingReasons").size()>0,"Structural problem was hidden");
        request("POST",f.path()+"/adoptions",command(invalid),3,422);
        check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Invalid proposal changed requirement");
        check(request("GET",f.path()+"/adoptions",null,null,200).size()==2,"History omitted adoption receipts");
    }
    void restart(Path dir) throws Exception {
        var saved=json.readTree(Files.readString(dir.resolve("saved.json")));
        var f=json.treeToValue(saved.path("fixture"),Fixture.class);workspaces.switchWorkspace("admin",f.scope.workspaceId());
        var before=projects.getRequirement(f.project,f.requirement,"admin",f.scope);
        var result=request("POST",f.path()+"/adoptions",saved.path("command"),3,200);
        check(result.equals(saved.path("result")),"Receipt changed after restart");
        check(before.equals(projects.getRequirement(f.project,f.requirement,"admin",f.scope)),"Restart retry changed active state");
        String id=saved.path("preview").path("content").path("id").asText();
        check(request("GET",f.path()+"/adoption-previews/"+id,null,null,200).equals(saved.path("preview")),"Preview evidence changed after restart");
        System.out.println("REFORMULATION_ADOPTION_RESTART_OK");
    }
    Map<String,Object> command(JsonNode preview) {
        return Map.of("commandId",UUID.randomUUID().toString(),"previewId",preview.path("content").path("id").asText(),
                "previewHash",preview.path("hash").asText(),"confirmed",true,"acknowledgeWarnings",true,"rationale","Explicit draft adoption");
    }
    JsonNode request(String method,String path,Object body,Integer revision,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30))
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString(("admin:"+PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Accept","application/json");
        if(revision!=null)builder.header("If-Match","\""+revision+"\"");
        if(method.equals("GET"))builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==expected,"Expected "+expected+" but got "+response.statusCode()+" "+path+" "+response.body());
        if(expected<300)check(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),"Private adoption response cached");
        return json.readTree(response.body());
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
