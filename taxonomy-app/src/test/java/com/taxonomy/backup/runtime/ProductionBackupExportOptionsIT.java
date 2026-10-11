package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;
import com.taxonomy.backup.jobs.BackupJobService;
import com.taxonomy.security.model.PrincipalUserDetails;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.model.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual saved grants, scoped repository metadata and the rendered native form; no mocked capture authority. */
@SpringBootTest(properties={"taxonomy.backup.enabled=true","embedding.enabled=false","llm.mock=true",
        "spring.datasource.url=jdbc:hsqldb:mem:production-backup-options;hsqldb.tx=mvcc","spring.jpa.hibernate.ddl-auto=update",
        "taxonomy.backup.directory=./target/production-backup-options"})
@AutoConfigureMockMvc
@DirtiesContext
class ProductionBackupExportOptionsIT {
    @Autowired MockMvc mvc;
    @Autowired DataSource database;
    @Autowired EntityManagerFactory entities;
    @Autowired PrincipalIdentityService identities;
    @Autowired BackupJobService jobs;

    @Test void renderedHistoryChoiceUsesCurrentGrantsAndExcludesForeignWorkspacesInBothLanguages() throws Exception {
        var jdbc=new JdbcTemplate(database);String suffix=UUID.randomUUID().toString();
        var alice=identities.oidc("https://export-choice.example.invalid","alice-"+suffix);
        var bob=identities.oidc("https://export-choice.example.invalid","bob-"+suffix);
        String repository="choices-"+suffix,workspace="private-"+suffix,foreignRepository="foreign-"+suffix,foreignWorkspace="foreign-private-"+suffix;
        route(repository,workspace,alice.scopeKey());route(foreignRepository,foreignWorkspace,bob.scopeKey());
        var login=new PrincipalUserDetails(alice.id(),alice.scopeKey(),"fixture-password",true,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        long before=jdbc.queryForObject("select count(*) from backup_job",Long.class);
        jdbc.update("insert into backup_capability_grant values (?,'EXPORT_HISTORY')",alice.id().value().toString());
        for(var locale:List.of(Locale.ENGLISH,Locale.GERMAN)) {
            String html=mvc.perform(get("/backup-jobs").param("lang",locale.getLanguage()).with(user(login)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(workspaceOption(html,workspace)).contains("data-history-available=\"true\"");
            assertThat(html).contains("lang=\""+locale.getLanguage()+"\"",locale.equals(Locale.GERMAN)?"Sicherungsaufträge":"Backup jobs",
                    "id=\"backupExportHistoryOption\"","value=\"REPOSITORY_HISTORY\"","value=\"BARE\"","value=\"WORKTREE\"")
                    .doesNotContain(foreignRepository,foreignWorkspace,"value=\"INSTALLATION_FULL\"");
        }
        assertThat(jobs.exportOptions(alice.id()).selections()).extracting(com.taxonomy.backup.jobs.BackupExportOptions.Selection::workspaceId)
                .contains(workspace).doesNotContain(foreignWorkspace);
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='EXPORT_HISTORY'",alice.id().value().toString());
        for(var locale:List.of(Locale.ENGLISH,Locale.GERMAN)) {
            String html=mvc.perform(get("/backup-jobs").param("lang",locale.getLanguage()).with(user(login))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(workspaceOption(html,workspace)).contains("data-history-available=\"false\"");
            assertThat(html).contains("lang=\""+locale.getLanguage()+"\"",locale.equals(Locale.GERMAN)?"Sicherungsaufträge":"Backup jobs")
                    .doesNotContain("id=\"backupExportHistoryOption\"","value=\"BARE\"","value=\"WORKTREE\"",foreignWorkspace);
        }
        var request=new BackupRequest(BackupProfile.REPOSITORY_HISTORY,new BackupScope.Workspace(repository,workspace),
                new BackupTime.History(),GitRepresentation.BUNDLE,SecretsSelection.EXCLUDE);
        mvc.perform(post("/api/backups/jobs").with(user(login)).with(csrf()).contentType("application/json")
                .content(new BackupManifestCodec().writeRequest(request))).andExpect(status().isForbidden());
        jdbc.update("insert into backup_capability_grant values (?,'EXPORT_HISTORY')",alice.id().value().toString());
        jdbc.update("delete from backup_capability_grant where principal_id=? and capability='EXPORT_CURRENT'",alice.id().value().toString());
        String disabled=mvc.perform(get("/backup-jobs").with(user(login))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(disabled).doesNotContain("id=\"backupExportHistoryOption\"","data-workspace-id=\""+workspace+"\"",foreignWorkspace);
        assertThat(jdbc.queryForObject("select count(*) from backup_job",Long.class)).isEqualTo(before);
    }

    private static String workspaceOption(String html,String workspace) {
        var options=java.util.regex.Pattern.compile("<option\\b[^>]*>").matcher(html).results()
                .map(java.util.regex.MatchResult::group)
                .filter(tag->tag.contains("data-workspace-id=\""+workspace+"\"")).toList();
        assertThat(options).as("Exactly the authorized private workspace option").hasSize(1);
        return options.getFirst();
    }

    private void route(String repository,String workspace,String owner) {
        // Selection must use authorized metadata without opening or creating a Git repository.
        try(var em=entities.createEntityManager()) {
            var transaction=em.getTransaction();transaction.begin();var now=Instant.parse("2026-10-11T00:00:00Z");
            var repo=new SystemRepository();repo.setRepositoryId(repository);repo.setStorageRepositoryName("central-"+repository);
            repo.setSlug(repository);repo.setDisplayName(repository);repo.setOwnerId(owner);repo.setOwnerType(RepositoryOwnerType.USER);
            repo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);repo.setCreatedAt(now);em.persist(repo);
            var selected=new UserWorkspace();selected.setWorkspaceId(workspace);selected.setUsername(owner);
            selected.setSourceRepositoryId(repository);selected.setDisplayName(workspace);selected.setCreatedAt(now);em.persist(selected);
            transaction.commit();
        }
    }
}
