package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.jobs.*;
import com.taxonomy.editor.ArchitectureCommandPort.Metadata;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.provenance.model.*;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.workspace.model.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import jakarta.persistence.EntityManagerFactory;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** The real durable job composes original Git, SQL history, source bytes and owner reference closure. */
@SpringBootTest(properties={"taxonomy.backup.enabled=true","embedding.enabled=false","llm.mock=true",
        "spring.datasource.url=jdbc:hsqldb:mem:production-history-backup;hsqldb.tx=mvcc","spring.jpa.hibernate.ddl-auto=update",
        "taxonomy.backup.directory=./target/production-history-backup",
        "taxonomy.backup.source-content-directory=./target/production-history-sources"})
@DirtiesContext
class ProductionHistoryBackupIT {
    @Autowired ApplicationContext application;
    @Autowired DataSource database;
    @Autowired EntityManagerFactory entities;
    @Autowired DslGitRepositoryFactory repositories;
    @Autowired PrincipalIdentityService identities;
    private final Path root=Path.of("target/production-history-sources").toAbsolutePath();
    private final Instant now=Instant.parse("2026-10-11T00:00:00Z");

    @ParameterizedTest @EnumSource(value=GitRepresentation.class,names={"BUNDLE","BARE","WORKTREE"})
    void durableHistoryJobRetainsNativeCommitsEditorBodiesAndHistoricalSourceClosure(GitRepresentation representation)throws Exception {
        assertThat(application.getBeansOfType(BackupCaptureSource.class)).hasSize(1);
        application.getBean(BackupJobRunner.class).destroy();
        var fixture=fixture();var jdbc=new JdbcTemplate(database);
        var jobs=application.getBean(BackupJobService.class);var worker=application.getBean(BackupJobWorker.class);
        var request=history(fixture.repository(),fixture.workspace(),representation);
        long queued=jdbc.queryForObject("select count(*) from backup_job",Long.class);
        assertThatThrownBy(()->jobs.submit(request,fixture.foreignActor())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from backup_job",Long.class)).isEqualTo(queued);
        var id=jobs.submit(request,fixture.actor());
        assertThat(worker.runNext()).isTrue();
        // The worker deliberately hides source-private exception text. Re-capture a failed fixture
        // directly so the test identifies the rejected production dependency without changing that contract.
        if(jobs.status(id,fixture.actor()).state()!=BackupJobState.READY) {
            try(var diagnostic=application.getBean(BackupCaptureSource.class).capture(
                    application.getBean(BackupAuthorizationService.class).authorize(fixture.actor(),request),
                    root.resolve("diagnostic-"+UUID.randomUUID()),ArchiveProgress.NONE)) {
                assertThat(diagnostic.manifest().entries()).isNotEmpty();
            }
        }
        assertThat(jobs.status(id,fixture.actor()).state()).isEqualTo(BackupJobState.READY);
        Path archive=root.resolve(UUID.randomUUID()+".taxbackup");
        try(var download=jobs.download(id,fixture.actor());var output=Files.newOutputStream(archive)){download.writeTo(output);}
        try(var verified=reader().verify(archive)) {
            assertThat(verified.manifest().request().profile()).isEqualTo(BackupProfile.REPOSITORY_HISTORY);
            var captured=verified.manifest().repositories();
            assertThat(captured).singleElement().satisfies(repository->{
                assertThat(repository.id()).isEqualTo(new BackupRepositoryKey(fixture.repository(),fixture.workspace()));
                assertThat(repository.captured().requiredCommits()).contains(fixture.head(),fixture.orphan());
                assertThat(repository.exportedHead()).isEqualTo(fixture.head());
            });
            Set<String> codes=new TreeSet<>();
            for(var row:records(verified,"data/knowledge/node.ndjson"))codes.add(row.get("code").asText());
            assertThat(codes).containsAll(fixture.codes().subList(0,5)).doesNotContain(fixture.codes().get(5));
            var operations=records(verified,"data/workspace/operations.ndjson");
            assertThat(operations).hasSize(2);
            assertThat(operations).anySatisfy(row->{
                assertThat(row.get("beforeDsl").asText()).isEqualTo(fixture.headDsl());
                assertThat(row.get("afterDsl").asText()).isEqualTo(fixture.editorHistoryDsl());
            });
            assertThat(records(verified,"data/workspace/checkpoints.ndjson")).singleElement().satisfies(row->{
                assertThat(row.get("dsl").asText()).isEqualTo(fixture.editorHistoryDsl());
                assertThat(row.get("failureCode").asText()).isEqualTo("CHECKPOINT_CONFLICT");
            });
            assertThat(records(verified,"data/workspace/working-states.ndjson")).singleElement().satisfies(row->
                    assertThat(row.get("dsl").asText()).isEqualTo(fixture.savedDsl()));
            var document=records(verified,"data/workspace/git-commit-references.ndjson");
            assertThat(document).anySatisfy(row->{
                assertThat(row.get("field").asText()).isEqualTo("INDEX_COMMIT");
                assertThat(row.get("commit").asText()).isEqualTo(fixture.orphan());
            });
            StringBuilder ownerPayloads=new StringBuilder();
            for(var entry:verified.manifest().entries())if(entry.path().startsWith("data/") || entry.path().startsWith("files/")) {
                try(var input=verified.openEntry(entry.path())){ownerPayloads.append(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
            }
            assertThat(ownerPayloads.toString()).contains("HISTORICAL-REQUIREMENT","HISTORICAL-FRAGMENT",fixture.sourceContent())
                    .doesNotContain("FOREIGN-HISTORY-PRIVATE");
            Path extracted=root.resolve("native-"+UUID.randomUUID());Files.createDirectories(extracted);
            String prefix="repositories/"+captured.getFirst().archiveId()+"/";
            for(var entry:verified.manifest().entries())if(entry.path().startsWith(prefix)) {
                Path target=extracted.resolve(entry.path().substring(prefix.length())).normalize();
                assertThat(target.startsWith(extracted)).as("Normalized extraction target stays within fresh directory").isTrue();
                if(entry.path().endsWith("/"))Files.createDirectories(target);
                else {Files.createDirectories(target.getParent());try(var input=verified.openEntry(entry.path())){Files.copy(input,target);}}
            }
            Path nativeGit=representation==GitRepresentation.WORKTREE?extracted.resolve(".git"):extracted;
            if(representation==GitRepresentation.BUNDLE) {
                nativeGit=root.resolve("clone-"+UUID.randomUUID()+".git");
                git("clone","--bare",extracted.resolve("history.bundle").toString(),nativeGit.toString());
            }
            String directory="--git-dir="+nativeGit;
            git(directory,"fsck","--full");
            assertThat(new String(git(directory,"show",fixture.first()+":architecture.taxdsl"),StandardCharsets.UTF_8)).isEqualTo(fixture.firstDsl());
            assertThat(new String(git(directory,"show",fixture.orphan()+":architecture.taxdsl"),StandardCharsets.UTF_8)).isEqualTo(fixture.orphanDsl());
            assertThat(git(directory,"show",fixture.orphan()+":historical-evidence.bin")).isEqualTo(fixture.binary());
            assertThat(new String(git(directory,"rev-parse",fixture.head()+"^"),StandardCharsets.UTF_8).strip()).isEqualTo(fixture.first());
            if(representation==GitRepresentation.WORKTREE)assertThat(Files.readString(extracted.resolve("architecture.taxdsl"))).isEqualTo(fixture.savedDsl());
        }
        assertThatThrownBy(()->jobs.download(id,fixture.foreignActor())).isInstanceOf(AccessDeniedException.class);
        long beforeRevocation=jdbc.queryForObject("select count(*) from backup_job",Long.class);
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='EXPORT_HISTORY'",fixture.actor().value().toString());
        assertThatThrownBy(()->jobs.submit(request,fixture.actor())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->jobs.download(id,fixture.actor())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from backup_job",Long.class)).isEqualTo(beforeRevocation);
        jdbc.update("insert into backup_capability_grant values (?,'EXPORT_HISTORY')",fixture.actor().value().toString());
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='DOWNLOAD_BACKUP'",fixture.actor().value().toString());
        assertThatThrownBy(()->jobs.download(id,fixture.actor())).isInstanceOf(AccessDeniedException.class);
        jdbc.update("insert into backup_capability_grant values (?,'DOWNLOAD_BACKUP')",fixture.actor().value().toString());
        // Only an old requirement revision references these bytes. Their loss must still fail the actual history job.
        Files.delete(root.resolve(fixture.sourceFile()));
        var missing=jobs.submit(request,fixture.actor());assertThat(worker.runNext()).isTrue();
        assertThat(jobs.status(missing,fixture.actor()).state()).isEqualTo(BackupJobState.FAILED);
        assertThat(jobs.status(missing,fixture.actor()).downloadAvailable()).isFalse();
    }

    private Fixture fixture()throws Exception {
        Files.createDirectories(root);String suffix=UUID.randomUUID().toString();
        var alice=identities.oidc("https://history-fixture.example","alice-"+suffix);
        var bob=identities.oidc("https://history-fixture.example","bob-"+suffix);
        String repository="history-"+suffix,workspace="work-"+suffix;
        String foreignRepository="foreign-"+suffix,foreignWorkspace="foreign-work-"+suffix;
        route(repository,workspace,alice.scopeKey());route(foreignRepository,foreignWorkspace,bob.scopeKey());
        var jdbc=new JdbcTemplate(database);
        for(var actor:List.of(alice,bob))jdbc.update("insert into backup_capability_grant values (?,'EXPORT_HISTORY')",actor.id().value().toString());
        var codes=jdbc.queryForList("select n.code from taxonomy_node n where n.parent_code is not null and not exists (select 1 from taxonomy_node child where child.parent_code=n.code) order by n.code",String.class).stream().limit(6).toList();
        assertThat(codes).hasSize(6);
        String firstDsl=dsl(codes.get(0),"ORIGINAL-ANCESTOR"),headDsl=dsl(codes.get(1),"CURRENT-COMMIT");
        String orphanDsl=dsl(codes.get(2),"DATABASE-ONLY-HISTORY"),editorDsl=dsl(codes.get(3),"EDITOR-ONLY-HISTORY"),savedDsl=dsl(codes.get(4),"SAVED-CURRENT");
        var source=repositories.getWorkspaceRepository(workspace);
        String first=source.commitDsl("draft",firstDsl,alice.scopeKey(),"Original ancestor");
        String head=source.commitDsl("draft",headDsl,alice.scopeKey(),"Current commit");
        byte[] binary={0,1,2,3,10,13,0,127,(byte)128,(byte)255};
        String orphan=orphan(source.getGitRepository(),orphanDsl,binary);
        assertThat(source.getGitRepository().getRefDatabase().getRefs().stream().map(Ref::getObjectId).filter(Objects::nonNull).map(ObjectId::name)).doesNotContain(orphan);
        var update=source.getGitRepository().updateRef("HEAD");var previous=source.getGitRepository().exactRef("HEAD");
        update.setExpectedOldObjectId(previous==null || previous.getObjectId()==null?ObjectId.zeroId():previous.getObjectId());
        update.setRefLogIdent(new PersonIdent("History fixture","history@example.invalid"));
        update.setRefLogMessage("Select the existing draft",false);
        assertThat(update.link("refs/heads/draft")).isIn(RefUpdate.Result.NEW,RefUpdate.Result.FORCED,RefUpdate.Result.NO_CHANGE);
        repositories.getWorkspaceRepository(foreignWorkspace).commitDsl("draft",dsl(codes.get(5),"FOREIGN-HISTORY-PRIVATE"),bob.scopeKey(),"Foreign history");
        try(var em=entities.createEntityManager()) {
            var tx=em.getTransaction();tx.begin();var index=new ArchitectureCommitIndex();
            index.setRepositoryId(repository);index.setWorkspaceId(workspace);index.setCommitId(orphan);
            index.setAuthor(alice.scopeKey());index.setCommitTimestamp(now);index.setMessage("Retained database-only root");index.setBranch("draft");
            em.persist(index);tx.commit();
        }
        var context=RepositoryContext.workspace(repository,workspace,"draft",alice.scopeKey());
        var journal=new EditorJournal(entities,new JpaTransactionManager(entities));
        journal.locked(context,new EditorJournal.State(headDsl,0,head,0,null),session->session.append(metadata(),alice.scopeKey(),"SEMANTIC",null,StableIdentityHash.sha256(editorDsl),editorDsl,List.of(codes.get(3))));
        var checkpoint=metadata();
        journal.locked(context,journal.read(context).state(),session->{session.prepare(checkpoint,alice.scopeKey(),StableIdentityHash.sha256("checkpoint"));session.rejectCheckpoint(checkpoint.commandId());return null;});
        journal.locked(context,journal.read(context).state(),session->session.append(metadata(),alice.scopeKey(),"SEMANTIC",null,StableIdentityHash.sha256(savedDsl),savedDsl,List.of(codes.get(4))));
        String sourceFile="historical-source-"+suffix+".bin",sourceContent="HISTORICAL-SOURCE-BYTES-"+suffix;
        Files.writeString(root.resolve(sourceFile),sourceContent);
        requirement(repository,workspace,alice.scopeKey(),sourceFile,sourceContent);
        return new Fixture(repository,workspace,alice.id(),bob.id(),first,head,orphan,firstDsl,headDsl,orphanDsl,editorDsl,savedDsl,codes,binary,sourceFile,sourceContent);
    }
    private void route(String repository,String workspace,String owner) {
        try(var em=entities.createEntityManager()) {
            var tx=em.getTransaction();tx.begin();var repo=new SystemRepository();
            repo.setRepositoryId(repository);repo.setStorageRepositoryName("central-"+repository);repo.setSlug(repository);repo.setDisplayName(repository);
            repo.setOwnerId(owner);repo.setOwnerType(RepositoryOwnerType.USER);repo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);repo.setCreatedAt(now);em.persist(repo);
            var work=new UserWorkspace();work.setWorkspaceId(workspace);work.setUsername(owner);work.setSourceRepositoryId(repository);work.setDisplayName(workspace);work.setCreatedAt(now);em.persist(work);tx.commit();
        }
        repositories.createCentralRepository(repository,"central-"+repository);repositories.createWorkspaceRepository(workspace,repository,"draft");
    }
    private void requirement(String repository,String workspace,String owner,String file,String content) {
        try(var em=entities.createEntityManager()) {
            var tx=em.getTransaction();tx.begin();String tenant=new RepositoryTenantIdentity(repository,"WORKSPACE:"+workspace,"draft").scopeKey();
            var project=new ArchitectureProject(tenant,workspace,owner,"HISTORY-PROJECT","History project",null,ProjectStatus.ACTIVE,now);em.persist(project);em.flush();
            var requirement=new ProjectRequirement(project,"HISTORY-REQUIREMENT","History requirement",RequirementStatus.DRAFT,50,Criticality.MEDIUM,RequirementType.FUNCTIONAL,ReviewStatus.PROPOSED,owner,now);em.persist(requirement);em.flush();
            var artifact=new SourceArtifact(com.taxonomy.model.SourceType.REGULATION,"Historical source");em.persist(artifact);em.flush();
            var version=new SourceVersion(artifact);version.setMimeType("application/octet-stream");version.setStorageLocation(file);version.setContentHash(StableIdentityHash.sha256(content));em.persist(version);em.flush();
            var fragment=new SourceFragment(version,"HISTORICAL-FRAGMENT");em.persist(fragment);em.flush();
            var old=new ProjectRequirementVersion(requirement,1,"HISTORICAL-REQUIREMENT",StableIdentityHash.sha256("HISTORICAL-REQUIREMENT"),null,owner,now,artifact.getId(),version.getId(),"["+fragment.getId()+"]",null,null,null);em.persist(old);em.flush();
            var current=new ProjectRequirementVersion(requirement,2,"CURRENT-REQUIREMENT",StableIdentityHash.sha256("CURRENT-REQUIREMENT"),null,owner,now,null,null,null,null,null,null);em.persist(current);em.flush();requirement.pointToVersion(current.getId(),now);tx.commit();
        }
    }
    private String orphan(Repository repository,String dsl,byte[] binary)throws Exception {
        try(var insert=repository.newObjectInserter()) {
            var tree=new TreeFormatter();tree.append("architecture.taxdsl",FileMode.REGULAR_FILE,insert.insert(Constants.OBJ_BLOB,dsl.getBytes(StandardCharsets.UTF_8)));
            tree.append("historical-evidence.bin",FileMode.REGULAR_FILE,insert.insert(Constants.OBJ_BLOB,binary));
            var commit=new CommitBuilder();commit.setTreeId(insert.insert(tree));var author=new PersonIdent("History fixture","history@example.invalid",Date.from(now),TimeZone.getTimeZone("UTC"));
            commit.setAuthor(author);commit.setCommitter(author);commit.setMessage("Original database-only commit");
            var id=insert.insert(commit);insert.flush();return id.name();
        }
    }
    private BackupArchiveReader reader()throws IOException {
        var versions=new HashMap<BackupComponentId,Integer>();for(var owner:BackupCoverageInventory.load().categories())versions.put(owner.owner(),1);
        versions.put(new BackupComponentId("capture-proof"),1);
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),application.getEnvironment().getProperty("info.app.version"),versions,application.getBean(BackupFeaturePrerequisites.class));
    }
    private static List<com.fasterxml.jackson.databind.JsonNode> records(VerifiedBackup archive,String path)throws IOException {
        try(var input=new BufferedReader(new InputStreamReader(archive.openEntry(path),StandardCharsets.UTF_8))) {
            var result=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();assertThat(input.readLine()).isNotNull();
            for(String line;(line=input.readLine())!=null;)result.add(PortableRows.json().readTree(line));return result;
        }
    }
    private byte[] git(String... arguments)throws Exception {
        var command=new ArrayList<String>();command.add("git");command.addAll(List.of(arguments));
        Path log=root.resolve("native-"+UUID.randomUUID()+".log");
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertThat(process.waitFor(30,TimeUnit.SECONDS)).as(command.toString()).isTrue();
            var bytes=Files.readAllBytes(log);assertThat(process.exitValue()).as(new String(bytes,StandardCharsets.UTF_8)).isZero();return bytes;
        } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
    }
    private static BackupRequest history(String repository,String workspace,GitRepresentation representation){return new BackupRequest(BackupProfile.REPOSITORY_HISTORY,new BackupScope.Workspace(repository,workspace),new BackupTime.History(),representation,SecretsSelection.EXCLUDE);}
    private static Metadata metadata(){String id=UUID.randomUUID().toString();return new Metadata(id,id,id,"Production history fixture");}
    private static String dsl(String code,String title){return "element "+code+" type System {\n  title: \""+title+"\";\n}\n";}
    private record Fixture(String repository,String workspace,PrincipalId actor,PrincipalId foreignActor,String first,String head,String orphan,String firstDsl,String headDsl,String orphanDsl,String editorHistoryDsl,String savedDsl,List<String> codes,byte[] binary,String sourceFile,String sourceContent) { }
}
