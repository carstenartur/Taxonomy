package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.provenance.backup.ApplicationBackupContributor;
import com.taxonomy.provenance.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ApplicationSourceExportIT {
    @TempDir Path files;

    @Test void scopedSourceClosureCopiesCurrentFileAndFragmentsWithoutGlobalBusinessKeyLookup() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD-REQUIREMENT","CURRENT-REQUIREMENT");
            fixture.requirement("repo-b","private-b","FOREIGN-OLD","FOREIGN-CURRENT");
            var current=source(fixture,"Selected source","CURRENT-SOURCE-CONTENT","current.bin");
            var old=source(fixture,"Old source","DELETED-HISTORICAL-FILE","old.bin");
            var foreign=source(fixture,"Foreign source","PRIVATE-SOURCE-CONTENT","foreign.bin");
            attach(fixture,"repo-a","private-a",2,current);
            attach(fixture,"repo-a","private-a",1,old);
            attach(fixture,"repo-b","private-b",2,foreign);
            // This legacy link has the SAME business requirement ID and deliberately no tenant identity.
            try (var em=fixture.factory.createEntityManager()) {
                var tx=em.getTransaction();tx.begin();
                var link=new RequirementSourceLink("R-SAME",em.find(SourceArtifact.class,foreign.artifact),LinkType.DERIVED_FROM);
                link.setSourceVersion(em.find(SourceVersion.class,foreign.version)); link.setNote("PRIVATE-LEGACY-NOTE");
                em.persist(link);tx.commit();
            }
            var snapshot=CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a"));
            var portfolio=new PortfolioBackupContributor(fixture.database);
            var output=new CurrentStateExportIT.Contents();
            new ApplicationBackupContributor(fixture.database,portfolio::sourceReferences,files).write(snapshot,output);
            assertThat(output.text()).contains("Selected source","CURRENT-SOURCE-CONTENT","application.source-fragment")
                    .doesNotContain("PRIVATE-SOURCE-CONTENT","DELETED-HISTORICAL-FILE","PRIVATE-LEGACY-NOTE",files.toString());
            assertThat(output.entries.entrySet().stream().filter(e->e.getKey().startsWith("files/")).map(Map.Entry::getValue))
                    .anySatisfy(bytes->assertThat(bytes).isEqualTo("CURRENT-SOURCE-CONTENT".getBytes(StandardCharsets.UTF_8)));
            var history=new CurrentStateExportIT.Contents();
            new ApplicationBackupContributor(fixture.database,portfolio::sourceReferences,files).write(
                    CurrentStateExportIT.snapshot(BackupProfile.REPOSITORY_HISTORY,new BackupScope.Workspace("repo-a","private-a")),history);
            assertThat(history.text()).contains("DELETED-HISTORICAL-FILE","CURRENT-SOURCE-CONTENT")
                    .doesNotContain("PRIVATE-SOURCE-CONTENT","PRIVATE-LEGACY-NOTE");
            var selected=new CurrentStateExportIT.Contents();
            new ApplicationBackupContributor(fixture.database,portfolio::sourceReferences,files).write(
                    CurrentStateExportIT.snapshot(BackupProfile.SELECTED_VERSION,new BackupScope.Workspace("repo-a","private-a")),selected);
            assertThat(selected.text()).doesNotContain("CURRENT-SOURCE-CONTENT","DELETED-HISTORICAL-FILE");
        }
    }

    @Test void referencedMissingOrModifiedFileFailsInsteadOfCreatingAnApparentlyCompleteArchive() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            var source=source(fixture,"Source","ORIGINAL-CONTENT","source.bin");
            attach(fixture,"repo-a","private-a",2,source);
            var snapshot=CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a"));
            var exporter=new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files);
            Files.writeString(files.resolve("source.bin"),"MODIFIED-CONTENT");
            assertThatThrownBy(()->exporter.write(snapshot,new CurrentStateExportIT.Contents())).isInstanceOf(java.io.IOException.class);
            Files.delete(files.resolve("source.bin"));
            assertThatThrownBy(()->exporter.write(snapshot,new CurrentStateExportIT.Contents())).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void sourcePathsCannotEscapeTheConfiguredContentRootOrFollowSymlinks() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            Path root=Files.createDirectory(files.resolve("allowed"));
            var source=source(fixture,"Source","UNAUTHORIZED-HOST-CONTENT","outside.bin");
            attach(fixture,"repo-a","private-a",2,source);
            var snapshot=CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a"));
            var exporter=new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,root);
            assertThatThrownBy(()->exporter.write(snapshot,new CurrentStateExportIT.Contents())).isInstanceOf(java.io.IOException.class);
            Path link=root.resolve("link.bin");Files.createSymbolicLink(link,files.resolve("outside.bin"));
            fixture.jdbc.update("update source_version set storage_location=? where id=?",link.toString(),source.version);
            assertThatThrownBy(()->exporter.write(snapshot,new CurrentStateExportIT.Contents())).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void aMissingReferencedFragmentIsAClosureFailure() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            var source=source(fixture,"Source","CONTENT","source.bin");
            attach(fixture,"repo-a","private-a",2,new SourceIds(source.artifact,source.version,999999));
            var snapshot=CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a"));
            var exporter=new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files);
            assertThatThrownBy(()->exporter.write(snapshot,new CurrentStateExportIT.Contents())).isInstanceOf(java.io.IOException.class);
        }
    }

    private static CurrentStateExportIT.Fixture fixture() {
        return new CurrentStateExportIT.Fixture(SourceArtifact.class,SourceVersion.class,SourceFragment.class,RequirementSourceLink.class);
    }
    @Test void filesDoNotReenterTheArchiveSinkWhileAnotherEntryIsOpen() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            var current=source(fixture,"Source","CURRENT","source.bin"); attach(fixture,"repo-a","private-a",2,current);
            var active=new java.util.concurrent.atomic.AtomicBoolean(); var output=new CurrentStateExportIT.Contents();
            ComponentSink serial=(path,input)->{
                if (!active.compareAndSet(false,true)) throw new java.io.IOException("Archive entry is already open");
                try { return output.write(path,input); } finally { active.set(false); }
            };
            new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files)
                    .write(CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a")),serial);
            assertThat(output.entries).containsKey("files/source-"+current.version+"-original");
        }
    }

    @Test void currentInstallationUsesLatestSourceVersionsAndKeepsLegacyLinksInTheirOwnNamespace() throws Exception {
        try (var fixture=fixture()) {
            var old=source(fixture,"Source","HISTORICAL-DOCUMENT","old.bin");
            Path latest=files.resolve("latest.bin"); Files.writeString(latest,"LATEST-DOCUMENT");
            try (var em=fixture.factory.createEntityManager()) {
                var tx=em.getTransaction();tx.begin();
                var artifact=em.find(SourceArtifact.class,old.artifact); var oldVersion=em.find(SourceVersion.class,old.version);
                var version=new SourceVersion(artifact); version.setRetrievedAt(oldVersion.getRetrievedAt().plusSeconds(1));
                version.setContentHash(com.taxonomy.identity.StableIdentityHash.sha256("LATEST-DOCUMENT"));version.setStorageLocation(latest.toString());em.persist(version);em.flush();
                var historicalLink=new RequirementSourceLink("R-SAME",artifact,LinkType.DERIVED_FROM);historicalLink.setSourceVersion(oldVersion);historicalLink.setNote("LEGACY-HISTORICAL-NOTE");em.persist(historicalLink);
                var currentLink=new RequirementSourceLink("R-SAME",artifact,LinkType.DERIVED_FROM);currentLink.setSourceVersion(version);currentLink.setNote("LEGACY-CURRENT-NOTE");em.persist(currentLink);
                tx.commit();
            }
            var exporter=new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files);
            var current=new CurrentStateExportIT.Contents();
            exporter.write(CurrentStateExportIT.snapshot(BackupProfile.INSTALLATION_CURRENT,new BackupScope.Installation()),current);
            assertThat(current.text()).contains("LATEST-DOCUMENT","LEGACY-CURRENT-NOTE","legacyRequirementKey")
                    .doesNotContain("HISTORICAL-DOCUMENT","LEGACY-HISTORICAL-NOTE");
            var history=new CurrentStateExportIT.Contents();
            exporter.write(CurrentStateExportIT.snapshot(BackupProfile.INSTALLATION_FULL,new BackupScope.Installation()),history);
            assertThat(history.text()).contains("LATEST-DOCUMENT","HISTORICAL-DOCUMENT","LEGACY-HISTORICAL-NOTE");
        }
    }
    @Test void inconsistentVersionOrCyclicFragmentReferencesFailBeforeAnyBytesAreWritten() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            var current=source(fixture,"Source","CURRENT","source.bin");
            var foreign=source(fixture,"Foreign","FOREIGN","foreign.bin");
            var snapshot=CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a"));
            var exporter=new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files);
            attach(fixture,"repo-a","private-a",2,new SourceIds(current.artifact,foreign.version,current.fragment));
            var output=new CurrentStateExportIT.Contents();
            assertThatThrownBy(()->exporter.write(snapshot,output)).isInstanceOf(java.io.IOException.class);
            assertThat(output.entries).isEmpty();
            attach(fixture,"repo-a","private-a",2,current);
            fixture.jdbc.update("update source_fragment set parent_fragment_id=? where id=?",current.fragment,current.fragment);
            assertThatThrownBy(()->exporter.write(snapshot,output)).isInstanceOf(java.io.IOException.class);
            assertThat(output.entries).isEmpty();
            fixture.jdbc.update("update source_fragment set parent_fragment_id=? where id=?",foreign.fragment,current.fragment);
            assertThatThrownBy(()->exporter.write(snapshot,output)).isInstanceOf(java.io.IOException.class);
            assertThat(output.entries).isEmpty();
        }
    }

    @Test void batchedFragmentExportIncludesEveryRowExactlyOnce() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            var current=source(fixture,"Source","CURRENT","source.bin");
            attach(fixture,"repo-a","private-a",2,current);
            for (int i=0;i<205;i++) fixture.jdbc.update("insert into source_fragment(source_version_id,fragment_text,parent_fragment_id) values(?,?,?)",current.version,"CHUNK-"+i,current.fragment);
            var output=new CurrentStateExportIT.Contents();
            new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files)
                    .write(CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a")),output);
            var lines=new String(output.entries.get("data/application/source-fragment.ndjson"),StandardCharsets.UTF_8).lines().skip(1).toList();
            assertThat(lines).hasSize(206).doesNotHaveDuplicates();
            for (int i=0;i<205;i++) {
                String expected="\"CHUNK-"+i+"\"";
                assertThat(lines).anyMatch(line->line.contains(expected));
            }
        }
    }

    @Test void retainedNestedRelativeFilesAndEmptyTextArePortableAndAbsentOriginalsAreExplicit() throws Exception {
        try (var fixture=fixture()) {
            fixture.requirement("repo-a","private-a","OLD","CURRENT");
            var current=source(fixture,"Source","CURRENT","source.bin");
            attach(fixture,"repo-a","private-a",2,current);
            Files.createDirectory(files.resolve("nested")); Files.move(files.resolve("source.bin"),files.resolve("nested/source.bin"));
            Files.writeString(files.resolve("empty.txt"),"");
            fixture.jdbc.update("update source_version set storage_location=?,raw_text_location=? where id=?","nested/source.bin","empty.txt",current.version);
            var snapshot=CurrentStateExportIT.snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a"));
            var exporter=new ApplicationBackupContributor(fixture.database,new PortfolioBackupContributor(fixture.database)::sourceReferences,files);
            var output=new CurrentStateExportIT.Contents(); exporter.write(snapshot,output);
            assertThat(output.entries.get("files/source-"+current.version+"-original")).isEqualTo("CURRENT".getBytes(StandardCharsets.UTF_8));
            assertThat(output.entries.get("files/source-"+current.version+"-text")).isEmpty();
            fixture.jdbc.update("update source_version set storage_location=null,raw_text_location=null where id=?",current.version);
            var absent=new CurrentStateExportIT.Contents(); exporter.write(snapshot,absent);
            assertThat(absent.text()).contains("\"originalAvailability\":\"NOT_RETAINED\"");
            assertThat(absent.entries.keySet()).noneMatch(key->key.startsWith("files/"));
        }
    }
    private SourceIds source(CurrentStateExportIT.Fixture fixture,String title,String content,String name) throws Exception {
        Path path=files.resolve(name);Files.writeString(path,content);
        try(var em=fixture.factory.createEntityManager()) {
            var tx=em.getTransaction();tx.begin();
            var artifact=new SourceArtifact(SourceType.REGULATION,title);em.persist(artifact);em.flush();
            var version=new SourceVersion(artifact);version.setVersionLabel("v1");version.setMimeType("application/octet-stream");
            version.setStorageLocation(path.toString());version.setContentHash(com.taxonomy.identity.StableIdentityHash.sha256(content));em.persist(version);em.flush();
            var fragment=new SourceFragment(version,content);fragment.setFragmentHash(com.taxonomy.identity.StableIdentityHash.sha256(content));em.persist(fragment);em.flush();
            tx.commit();return new SourceIds(artifact.getId(),version.getId(),fragment.getId());
        }
    }
    private static void attach(CurrentStateExportIT.Fixture fixture,String repository,String workspace,int number,SourceIds source) {
        String tenant=new com.taxonomy.workspace.model.RepositoryTenantIdentity(repository,"WORKSPACE:"+workspace,"draft").scopeKey();
        fixture.jdbc.update("update project_req_version set source_artifact_id=?,source_version_id=?,source_fragment_ids=? where scope_key=? and version_number=?",
                source.artifact,source.version,"["+source.fragment+"]",tenant,number);
    }
    private record SourceIds(long artifact,long version,long fragment) { }
}
