package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.jobs.BackupJobWorker;
import com.taxonomy.backup.jobs.BackupCaptureSource;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.provenance.model.*;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.identity.StableIdentityHash;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import com.taxonomy.backup.jobs.BackupJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;

/** Real application composition; a mock capture bean must not make this pass. */
@SpringBootTest(properties = {"taxonomy.backup.enabled=true", "embedding.enabled=false",
        "spring.datasource.url=jdbc:hsqldb:mem:production-backup;hsqldb.tx=mvcc", "spring.jpa.hibernate.ddl-auto=update",
        "taxonomy.backup.directory=./target/production-backup", "taxonomy.backup.source-content-directory=./target/production-backup-sources", "llm.mock=true"})
@DirtiesContext
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ProductionCurrentBackupIT {
    @Autowired ApplicationContext context;
    @Autowired DataSource database;
    @Autowired EntityManagerFactory entities;
    @Autowired DslGitRepositoryFactory repositories;
    @Autowired PrincipalIdentityService identities;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    private final Path root = Path.of("target/production-backup-sources").toAbsolutePath();
    private final Instant now = Instant.parse("2026-10-11T00:00:00Z");
    @Test void enabledApplicationProvidesItsCompleteCaptureSourceAndDurableJobPath() {
        assertThat(context.getBeansOfType(BackupCaptureSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(BackupJobService.class)).hasSize(1);
    }
    @Test void realJobExportsSavedStandAndSourceClosureWithoutForeignOrHistoricalPayloads() throws Exception {
        // Missing production composition is the first failure, before fixture or mock behavior.
        assertThat(context.getBeansOfType(BackupCaptureSource.class)).hasSize(1);
        var jdbc = new JdbcTemplate(database);
        String suffix = UUID.randomUUID().toString();
        String aliceName = "backup-alice-" + suffix, bobName = "backup-bob-" + suffix;
        var alice = identities.oidc("https://backup-test.example", aliceName);
        var bob = identities.oidc("https://backup-test.example", bobName);
        String repoA = "backup-a-" + suffix, repoB = "backup-b-" + suffix;
        String wsA = "private-a-" + suffix, wsB = "private-b-" + suffix;
        route(repoA, wsA, alice.scopeKey()); route(repoB, wsB, bob.scopeKey());
        String old = repositories.getWorkspaceRepository(wsA).commitDsl("draft", dsl("HISTORICAL-SECRET"),alice.scopeKey(),"old");
        String head = repositories.getWorkspaceRepository(wsA).commitDsl("draft", dsl("COMMITTED-CURRENT"),alice.scopeKey(),"current");
        repositories.getWorkspaceRepository(wsB).commitDsl("draft", dsl("FOREIGN-PRIVATE"),bob.scopeKey(),"foreign");
        String scope = com.taxonomy.workspace.service.RepositoryContext.workspace(repoA,wsA,"draft",alice.scopeKey()).repositoryWorkspaceScopeKey();
        jdbc.update("insert into editor_workspace(scope_id,repository_id,workspace_id,branch,dsl,semantic_revision,checkpoint_commit,checkpoint_revision,row_version) values(?,?,?,?,?,2,?,1,0)",scope,repoA,wsA,"draft","1:"+dsl("SAVED-CURRENT"),head);
        Files.createDirectories(root); String file = "source-"+suffix+".bin"; Files.writeString(root.resolve(file),"CURRENT-BINARY");
        requirement(repoA,wsA,alice.scopeKey(),"CURRENT-REQUIREMENT",file);
        requirement(repoB,wsB,bob.scopeKey(),"FOREIGN-REQUIREMENT",null);
        String catalogueCode = jdbc.queryForList("select code from taxonomy_node where parent_code is not null order by code", String.class).getFirst();
        draft(repoA, wsA, alice.scopeKey(), "CURRENT-ANALYSIS", catalogueCode);
        draft(repoB, wsB, bob.scopeKey(), "FOREIGN-ANALYSIS", catalogueCode);
        context.getBean(com.taxonomy.backup.jobs.BackupJobRunner.class).destroy();
        var jobs = context.getBean(BackupJobService.class); var worker = context.getBean(BackupJobWorker.class);
        var request = new BackupRequest(BackupProfile.CURRENT_STATE,new BackupScope.Workspace(repoA,wsA),new BackupTime.Current(),GitRepresentation.BUNDLE,SecretsSelection.EXCLUDE);
        var login=new com.taxonomy.security.model.PrincipalUserDetails(alice.id(),alice.scopeKey(),"fixture-password",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/backup-jobs").locale(Locale.ENGLISH).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(login)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("data-workspace-id=\""+wsA+"\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(wsB))));
        assertThat(jobs.exportOptions(alice.id()).selections()).extracting(com.taxonomy.backup.jobs.BackupExportOptions.Selection::workspaceId).contains(wsA).doesNotContain(wsB);
        long queuedBefore=jdbc.queryForObject("select count(*) from backup_job",Long.class);
        assertThatThrownBy(()->jobs.submit(new BackupRequest(BackupProfile.CURRENT_STATE,new BackupScope.Workspace(repoB,wsB),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE),alice.id())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from backup_job",Long.class)).isEqualTo(queuedBefore);
        // Verify the real capture directly first so a rejected dependency has a useful test failure;
        // the durable worker intentionally never logs source-private exception text.
        try (var captured = context.getBean(BackupCaptureSource.class).capture(
                context.getBean(BackupAuthorizationService.class).authorize(alice.id(), request),
                root.resolve("direct-capture-" + suffix), ArchiveProgress.NONE)) {
            assertThat(captured.manifest().entries()).isNotEmpty();
        }
        var id = jobs.submit(request,alice.id()); assertThat(worker.runNext()).isTrue();
        assertThat(jobs.status(id,alice.id()).state()).isEqualTo(com.taxonomy.backup.jobs.BackupJobState.READY);
        Path archive = root.resolve("download-"+suffix+".taxbackup");
        try (var download=jobs.download(id,alice.id()); var output=Files.newOutputStream(archive)) { download.writeTo(output); }
        var versions=new HashMap<BackupComponentId,Integer>(); for(var owner:BackupCoverageInventory.load().categories()) versions.put(owner.owner(),1);
        versions.put(new BackupComponentId("capture-proof"),1);
        try(var verified=new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),
                context.getEnvironment().getProperty("info.app.version"),versions,context.getBean(BackupFeaturePrerequisites.class)).verify(archive)) {
            var text=new StringBuilder();
            for(var entry:verified.manifest().entries()) if(!entry.path().endsWith(".bundle")) {
                try(var input=verified.openEntry(entry.path())) { text.append(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
            }
            assertThat(text.toString()).contains("CURRENT-REQUIREMENT","CURRENT-BINARY","application.source-fragment","CURRENT-ANALYSIS",catalogueCode)
                    .doesNotContain("FOREIGN-PRIVATE","FOREIGN-REQUIREMENT","FOREIGN-ANALYSIS","HISTORICAL-SECRET","STALE-ANALYSIS");
            assertThat(verified.manifest().components()).extracting(c->c.id().value())
                    .contains("application","workspace","portfolio","analysis","architecture","knowledge","templates","interop");
            String payload=verified.manifest().entries().stream().map(BackupEntry::path).filter(p->p.endsWith("stand.bundle")).findFirst().orElseThrow();
            Path bundle=root.resolve("stand-"+suffix+".bundle");try(var input=verified.openEntry(payload)){Files.copy(input,bundle);}
            Path clone=root.resolve("clone-"+suffix);
            var process=new ProcessBuilder("git","clone",bundle.toString(),clone.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); String log=new String(process.getInputStream().readAllBytes());assertThat(process.exitValue()).as(log).isZero();
            assertThat(Files.readString(clone.resolve("architecture.taxdsl"))).contains("SAVED-CURRENT").doesNotContain("HISTORICAL-SECRET","COMMITTED-CURRENT");
            var fsck=new ProcessBuilder("git","-C",clone.toString(),"fsck","--full").inheritIO().start(); assertThat(fsck.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); assertThat(fsck.exitValue()).isZero();
        }
        assertThatThrownBy(()->jobs.download(id,bob.id())).isInstanceOf(AccessDeniedException.class);
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='DOWNLOAD_BACKUP'",alice.id().value().toString());
        assertThatThrownBy(()->jobs.download(id,alice.id())).isInstanceOf(AccessDeniedException.class);
        jdbc.update("insert into backup_capability_grant values (?,'DOWNLOAD_BACKUP')",alice.id().value().toString());
        jdbc.update("insert into backup_version_grant values (?,?,?,?)",alice.id().value().toString(),repoA,"W:"+wsA,old);
        var selected=new BackupRequest(BackupProfile.SELECTED_VERSION,new BackupScope.Workspace(repoA,wsA),new BackupTime.SelectedVersion(Map.of(new BackupRepositoryKey(repoA,wsA),old)),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        var selectedId=jobs.submit(selected,alice.id());assertThat(worker.runNext()).isTrue();assertThat(jobs.status(selectedId,alice.id()).state()).isEqualTo(com.taxonomy.backup.jobs.BackupJobState.READY);
        Path selectedArchive=root.resolve("selected-"+suffix+".taxbackup");
        try(var download=jobs.download(selectedId,alice.id());var output=Files.newOutputStream(selectedArchive)){download.writeTo(output);}
        try(var verified=new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),context.getEnvironment().getProperty("info.app.version"),versions,context.getBean(BackupFeaturePrerequisites.class)).verify(selectedArchive)) {
            var text=new StringBuilder();for(var entry:verified.manifest().entries())try(var input=verified.openEntry(entry.path())){text.append(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            assertThat(text.toString()).contains("HISTORICAL-SECRET").doesNotContain("SAVED-CURRENT","CURRENT-REQUIREMENT","FOREIGN-PRIVATE","STALE-ANALYSIS");
        }
        jdbc.update("insert into backup_capability_grant values (?,'EXPORT_INSTALLATION')",alice.id().value().toString());
        var installationRequest=new BackupRequest(BackupProfile.INSTALLATION_CURRENT,new BackupScope.Installation(),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        var installationId=jobs.submit(installationRequest,alice.id());assertThat(worker.runNext()).isTrue();
        assertThat(jobs.status(installationId,alice.id()).state()).isEqualTo(com.taxonomy.backup.jobs.BackupJobState.READY);
        Path installationArchive=root.resolve("installation-"+suffix+".taxbackup");
        try(var download=jobs.download(installationId,alice.id());var output=Files.newOutputStream(installationArchive)){download.writeTo(output);}
        try(var verified=new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),context.getEnvironment().getProperty("info.app.version"),versions,context.getBean(BackupFeaturePrerequisites.class)).verify(installationArchive)) {
            assertThat(verified.manifest().repositories()).extracting(BackupManifest.Repository::id).contains(new BackupRepositoryKey(repoA,wsA),new BackupRepositoryKey(repoB,wsB),new BackupRepositoryKey(repoA,null),new BackupRepositoryKey(repoB,null));
            assertThat(verified.manifest().dependencies()).containsAll(context.getBean(BackupFeaturePrerequisites.class).dependencies());
            var text=new StringBuilder();for(var entry:verified.manifest().entries())if(entry.path().endsWith(".ndjson") || entry.path().endsWith(".taxdsl") || entry.path().startsWith("files/workspace-git/"))try(var input=verified.openEntry(entry.path())){text.append(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
            assertThat(text.toString()).contains("CURRENT-REQUIREMENT","FOREIGN-REQUIREMENT","SAVED-CURRENT","FOREIGN-PRIVATE").doesNotContain("HISTORICAL-SECRET","STALE-ANALYSIS");
        }
        // Missing retained bytes must fail the durable job, rather than produce an apparently complete archive.
        Files.delete(root.resolve(file));var broken=jobs.submit(request,alice.id());assertThat(worker.runNext()).isTrue();
        assertThat(jobs.status(broken,alice.id()).state()).isEqualTo(com.taxonomy.backup.jobs.BackupJobState.FAILED);
        assertThat(jobs.status(broken,alice.id()).downloadAvailable()).isFalse();
    }
    @Test void unsupportedHistoryAndGitLayoutsAreRejectedBeforeQueueAdmission() {
        assertThat(context.getBeansOfType(BackupCaptureSource.class)).hasSize(1);
        var actor=identities.oidc("https://backup-test.example","admission-"+UUID.randomUUID());
        var jobs=context.getBean(BackupJobService.class);var jdbc=new JdbcTemplate(database);
        long before=jdbc.queryForObject("select count(*) from backup_job",Long.class);
        for(var representation:List.of(GitRepresentation.BARE,GitRepresentation.WORKTREE)) {
            var request=new BackupRequest(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("fixture-admission","fixture-private"),new BackupTime.Current(),representation,SecretsSelection.EXCLUDE);
            assertThatThrownBy(()->jobs.submit(request,actor.id())).isInstanceOf(IllegalArgumentException.class);
        }
        var history=new BackupRequest(BackupProfile.REPOSITORY_HISTORY,new BackupScope.Workspace("fixture-admission","fixture-private"),new BackupTime.History(),GitRepresentation.BUNDLE,SecretsSelection.EXCLUDE);
        assertThatThrownBy(()->jobs.submit(history,actor.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from backup_job",Long.class)).isEqualTo(before);
    }
    private void route(String repository,String workspace,String owner) {
        try(var em=entities.createEntityManager()) {var tx=em.getTransaction();tx.begin();
            var repo=new SystemRepository();repo.setRepositoryId(repository);repo.setStorageRepositoryName("central-"+repository);repo.setSlug(repository);repo.setDisplayName(repository);repo.setOwnerId(owner);repo.setOwnerType(RepositoryOwnerType.USER);repo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);repo.setCreatedAt(now);em.persist(repo);
            var ws=new UserWorkspace();ws.setWorkspaceId(workspace);ws.setUsername(owner);ws.setSourceRepositoryId(repository);ws.setDisplayName(workspace);ws.setCreatedAt(now);em.persist(ws);tx.commit();
        }
        repositories.createCentralRepository(repository,"central-"+repository);
        repositories.createWorkspaceRepository(workspace,repository,"draft");
    }
    private void requirement(String repository,String workspace,String owner,String text,String file) {
        try(var em=entities.createEntityManager()) {var tx=em.getTransaction();tx.begin();
            String tenant=new RepositoryTenantIdentity(repository,"WORKSPACE:"+workspace,"draft").scopeKey();
            var project=new ArchitectureProject(tenant,workspace,owner,"P-SAME","Project",null,ProjectStatus.ACTIVE,now);em.persist(project);em.flush();
            var requirement=new ProjectRequirement(project,"R-SAME","Requirement",RequirementStatus.DRAFT,50,Criticality.MEDIUM,RequirementType.FUNCTIONAL,ReviewStatus.PROPOSED,owner,now);em.persist(requirement);em.flush();
            var old=new ProjectRequirementVersion(requirement,1,"HISTORICAL-SECRET",StableIdentityHash.sha256("HISTORICAL-SECRET"),null,owner,now,null,null,null,null,null,null);em.persist(old);em.flush();
            var job=new RequirementAnalysisJob(UUID.randomUUID().toString(),project,null,"LOCAL",20,owner,workspace,1,now); em.persist(job);em.flush();
            var stale=new RequirementAnalysisSnapshot(UUID.randomUUID().toString(),project,requirement,old,job,AnalysisStatus.SUCCESS,"stale-session","LOCAL","fixture-model",null,null,workspace,"draft",null,owner,now,1,0,null,"STALE-ANALYSIS",null,null,null);em.persist(stale);em.flush();requirement.pointToAnalysis(stale.getId(),now);
            Long artifactId=null,versionId=null;String fragments=null;
            if(file!=null){var artifact=new SourceArtifact(com.taxonomy.model.SourceType.REGULATION,"Selected source");em.persist(artifact);em.flush();artifactId=artifact.getId();
                var version=new SourceVersion(artifact);version.setMimeType("application/octet-stream");version.setStorageLocation(file);version.setContentHash(StableIdentityHash.sha256("CURRENT-BINARY"));em.persist(version);em.flush();versionId=version.getId();
                var fragment=new SourceFragment(version,"CURRENT-FRAGMENT");em.persist(fragment);em.flush();fragments="["+fragment.getId()+"]";}
            var current=new ProjectRequirementVersion(requirement,2,text,StableIdentityHash.sha256(text),null,owner,now,artifactId,versionId,fragments,null,null,null);em.persist(current);em.flush();requirement.pointToVersion(current.getId(),now);tx.commit();
        }
    }
    private void draft(String repository, String workspace, String owner, String businessText, String code) throws Exception {
        String tenant = new RepositoryTenantIdentity(repository, "WORKSPACE:" + workspace, "draft").scopeKey();
        String payload = com.taxonomy.exchange.backup.PortableRows.json().writeValueAsString(Map.of(
                "businessText", businessText, "lastAnalyzedText", businessText, "draftState", "ANALYZED",
                "scores", Map.of(code, 70), "reasons", Map.of(code, "CURRENT-REASON"), "evaluatedNodes", List.of(code)));
        try (var em = entities.createEntityManager()) {
            var tx = em.getTransaction(); tx.begin();
            em.persist(new com.taxonomy.analysis.session.AnalysisWorkingDraft(tenant, workspace, owner, payload, now));
            tx.commit();
        }
    }
    private static String dsl(String text){return "element backup-current type System {\n  title: \""+text+"\";\n}\n";}

}
