package com.taxonomy.composition.backup;

import com.taxonomy.backup.*;
import com.taxonomy.backup.jobs.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.workspace.backup.*;
import com.taxonomy.portfolio.backup.*;
import com.taxonomy.catalog.backup.*;
import com.taxonomy.analysis.backup.AnalysisBackupContributor;
import com.taxonomy.architecture.backup.ArchitectureBackupContributor;
import com.taxonomy.interop.backup.IntegrationBackupContributor;
import com.taxonomy.templates.backup.TemplateBackupContributor;
import com.taxonomy.templates.DocumentTemplateGitRepository;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import com.taxonomy.security.backup.PrincipalScopeBackupSelector;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.exchange.backup.*;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.ast.*;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.access.AccessDeniedException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Complete production portable stand assembly. Every read runs under the shared installation capture fence. */
public final class ProductionCurrentBackupSource implements BackupCaptureSource {
    private final DataSource database;private final EntityManagerFactory entities;
    private final BackupMaintenanceLease fence;private final BackupAuthorizationService authorization;private final BackupAccessPolicy access;
    private final PrincipalIdentityService identities;private final PreferencesGitRepository preferences;private final DocumentTemplateGitRepository templates;
    private final ConfigurableEnvironment environment;private final BackupFeaturePrerequisites prerequisites;
    private final List<BackupDataContributor> additional;
    private final JdbcTemplate jdbc;private final BoundedBackupMetadata metadata;private final String buildCommit;
    public ProductionCurrentBackupSource(DataSource database,EntityManagerFactory entities,BackupMaintenanceLease fence,
            BackupAuthorizationService authorization,BackupAccessPolicy access,PrincipalIdentityService identities,
            PreferencesGitRepository preferences,DocumentTemplateGitRepository templates,ConfigurableEnvironment environment,
            BackupFeaturePrerequisites prerequisites,List<BackupDataContributor> additional,String buildCommit) {
        this.database=database;this.entities=entities;this.fence=fence;this.authorization=authorization;this.access=access;
        this.identities=identities;this.preferences=preferences;this.templates=templates;this.environment=environment;this.prerequisites=prerequisites;
        this.additional=List.copyOf(additional);this.buildCommit=Objects.requireNonNull(buildCommit);jdbc=new JdbcTemplate(database);metadata=new BoundedBackupMetadata(database);
    }
    @Override public void validateRequest(BackupRequest request) {
        if(!Set.of(BackupProfile.CURRENT_STATE,BackupProfile.SELECTED_VERSION,BackupProfile.INSTALLATION_CURRENT).contains(request.profile())
                || !Set.of(GitRepresentation.NONE,GitRepresentation.BUNDLE).contains(request.gitRepresentation())
                || request.secrets()!=SecretsSelection.EXCLUDE || request.scope().selectedRepositories().size()>200)
            throw new IllegalArgumentException("This portable export selection is unsupported");
        prerequisites.requireCapture();
    }
    @Override public BackupExportOptions exportOptions(PrincipalId actor) {
        var choices=new ArrayList<BackupExportOptions.Selection>();
        if(!access.isEnabled(actor) || !access.hasCapability(actor,BackupCapability.EXPORT_CURRENT,new BackupScope.Installation()))return new BackupExportOptions(List.of());
        // Metadata is filtered by the same current policy as enqueue, status and native download.
        try {
            var repositories = metadata.read("select repository_id,display_name from system_repository order by repository_id",
                    List.of(), BackupLimits.MAX_ITEMS, () -> {}, r -> new Choice(new BackupRepositoryKey(r.getString(1), null), r.getString(2)));
            for (var row : repositories) if (access.canRead(actor, row.key())) choices.add(choice(row));
            var workspaces = metadata.read("select source_repository_id,workspace_id,display_name from user_workspace order by source_repository_id,workspace_id",
                    List.of(), BackupLimits.MAX_ITEMS - repositories.size(), () -> {}, r -> new Choice(new BackupRepositoryKey(r.getString(1), r.getString(2)), r.getString(3)));
            for (var row : workspaces) if (access.canRead(actor, row.key())) choices.add(choice(row));
        } catch (IOException unavailable) { throw new IllegalStateException("Backup selection inventory is unavailable"); }
        if(access.hasCapability(actor,BackupCapability.EXPORT_INSTALLATION,new BackupScope.Installation()))
            choices.add(new BackupExportOptions.Selection("installation",null,null,true,""));
        if(choices.size()>BackupLimits.MAX_ITEMS)throw new IllegalStateException("Backup selection inventory is too large");
        return new BackupExportOptions(choices);
    }
    private static BackupExportOptions.Selection choice(Choice choice) {
        var key=choice.key();String stable=com.taxonomy.identity.StableIdentityHash.sha256(key.repositoryId()+"\u0000"+Objects.toString(key.workspaceId(),""));
        return new BackupExportOptions.Selection(stable,key.repositoryId(),key.workspaceId(),false,
                choice.label()+" · "+key.repositoryId()+(key.workspaceId()==null?"":" / "+key.workspaceId()));
    }
    private record Choice(BackupRepositoryKey key,String label) { }
    @Override public CapturedBackup capture(AuthorizedBackupRequest request,Path spool,ArchiveProgress progress)throws IOException {
        validateRequest(request.request());authorization.requireJobAccess(request.principalId(),request);
        var actor=identities.find(request.principalId()).filter(p->p.enabled()).orElseThrow(()->new AccessDeniedException("Backup access denied"));
        var inventory=BackupCoverageInventory.load();
        var projector=(BackupDocumentProjector) PortfolioStandDocument::project;
        var gitRepositories=new ExistingGitBackupRepositories(database,entities.unwrap(org.hibernate.SessionFactory.class));
        var stand=new GitStandBackupSource(database,gitRepositories,projector);
        var limits=new GitStandBackupSource.Limits(new GitTreeCapture.Limits(BackupLimits.MAX_ITEMS,2L<<30,10L<<30),PortableRows.MAX_RECORD_BYTES);
        var git=new GitStandBackupContributor(stand,limits);var workspace=new WorkspaceBackupContributor(database,projector);
        var portfolio=new PortfolioBackupContributor(database);var analysis=new AnalysisBackupContributor(database,actor.scopeKey());
        var closure=new ReferenceClosure(stand,limits,portfolio,analysis,gitRepositories);
        var delegates=new ArrayList<BackupDataContributor>();
        delegates.add(workspace);delegates.add(git);delegates.add(new StandOwnership("workspace","external.git-servers","external-git",true));
        delegates.add(portfolio);delegates.add(new StandOwnership("portfolio","storage.git.portfolio","git-ownership",false));
        delegates.add(analysis);delegates.add(new ArchitectureBackupContributor(database,projector,git::documents));
        delegates.add(new KnowledgeBackupContributor(database,portfolio::analysisReferences,closure::knowledge));
        delegates.add(new CatalogueSourceBackupContributor(database,snapshot->Set.of()));
        delegates.add(new TemplateBackupContributor(templates));
        delegates.add(new IntegrationBackupContributor(database,closure::integrations));
        delegates.add(ApplicationBackupComponent.create(database,portfolio::sourceReferences,
                new PrincipalScopeBackupSelector(database,List.of(workspace::principalScopes,portfolio::principalScopes)),
                Path.of(environment.getProperty("taxonomy.backup.source-content-directory","./data/source-content")),preferences,environment));
        delegates.addAll(additional);
        var contributors=new ArrayList<BackupContributor>();var components=new TreeMap<BackupComponentId,BackupSnapshotCoordinator.CaptureComponent>(Comparator.comparing(BackupComponentId::value));
        var owners = inventory.categories().stream().map(BackupInventory.Category::owner).distinct()
                .sorted(Comparator.comparing(BackupComponentId::value)).toList();
        for (var delegate : delegates) if (!owners.contains(delegate.componentId()))
            throw new IllegalArgumentException("Backup contributor has no reviewed inventory owner");
        for(var owner:owners) {
            // ApplicationBackupComponent has already verified its exact owned coverage.
            var owned=delegates.stream().filter(d->d.componentId().equals(owner)).toList();
            var contributor=new CompositeBackupDataContributor(owner,1,inventory,owned);contributors.add(contributor);
            components.put(owner,new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,
                    owner.value().equals("portfolio")?Set.of(new BackupComponentId("workspace")):Set.of()));
        }
        BackupSnapshotCoordinator.Inventory discovery=new BackupSnapshotCoordinator.Inventory() {
            @Override public BackupSnapshotCoordinator.CapturePlan inspect(AuthorizedBackupRequest authorized)throws IOException {return inspect(authorized,()->{});}
            @Override public BackupSnapshotCoordinator.CapturePlan inspect(AuthorizedBackupRequest authorized,BackupCheckpoint checkpoint)throws IOException {
                checkpoint.check();closure.checkpoint=checkpoint;var categories=new HashSet<String>();
                for(var entity:entities.getMetamodel().getEntities())if(entity.getJavaType().getName().startsWith("com.taxonomy."))categories.add(entity.getJavaType().getName());
                inventory.requireClassified(categories);
                var keys=authorized.request().scope() instanceof BackupScope.Installation?installationKeys(checkpoint):authorized.request().scope().selectedRepositories();
                var repositories=git.inspect(authorized,keys,checkpoint);
                if(authorized.request().profile()==BackupProfile.SELECTED_VERSION)closure.selectedDocuments(authorized,repositories,checkpoint);
                String installation=jdbc.queryForObject("select installation_id from principal_installation where registry_key='local'",String.class);
                return new BackupSnapshotCoordinator.CapturePlan(environment.getRequiredProperty("info.app.version"),
                        buildCommit,installation,repositories,components,
                        List.of("external Git services and deployment credentials remain operator-owned; captured local stands do not back up remote servers"),List.of());
            }
        };
        return new BackupSnapshotCoordinator(fence,authorization,discovery,contributors,spool,
                BackupSnapshotCoordinator.CaptureLimits.defaults(),Clock.systemUTC(),prerequisites).capture(request,progress);
    }
    private Set<BackupRepositoryKey> installationKeys(BackupCheckpoint checkpoint)throws IOException {
        checkpoint.check();var keys=new HashSet<BackupRepositoryKey>();
        for (String repository : metadata.read("select repository_id from system_repository order by repository_id", List.of(),
                BackupLimits.MAX_ITEMS, checkpoint, r -> r.getString(1))) keys.add(new BackupRepositoryKey(repository, null));
        for (var key : metadata.read("select source_repository_id,workspace_id from user_workspace order by source_repository_id,workspace_id", List.of(),
                BackupLimits.MAX_ITEMS - keys.size(), checkpoint, r -> new BackupRepositoryKey(r.getString(1), r.getString(2)))) {
            if (!keys.contains(new BackupRepositoryKey(key.repositoryId(), null))) throw new IOException("Workspace repository inventory is incomplete");
            keys.add(key);
        }
        if(keys.size()>BackupLimits.MAX_ITEMS)throw new IOException("Repository inventory limit exceeded");return Set.copyOf(keys);
    }
    private final class ReferenceClosure {
        private final GitStandBackupSource source;private final GitStandBackupSource.Limits limits;private final PortfolioBackupContributor portfolio;private final AnalysisBackupContributor analysis;private final ExistingGitBackupRepositories repositories;private BackupCheckpoint checkpoint;
        ReferenceClosure(GitStandBackupSource source,GitStandBackupSource.Limits limits,PortfolioBackupContributor portfolio,AnalysisBackupContributor analysis,ExistingGitBackupRepositories repositories){this.source=source;this.limits=limits;this.portfolio=portfolio;this.analysis=analysis;this.repositories=repositories;}
        KnowledgeBackupContributor.Selection knowledge(SnapshotContext snapshot)throws IOException {
            var references=new PortableKnowledgeReferences();var checkpoint=Objects.requireNonNull(this.checkpoint,"Capture reference fence");
            ProjectedBackupRecords.inspect(portfolio,snapshot,checkpoint,(dataset,record)->{
                for(String field:List.of("nodeCode","taxonomyRoot","sourceCode","targetCode"))references.value(record.get(field));
                for(String field:List.of("analysisPayload","gapAnalysisPayload","patternDetectionPayload","recommendationPayload"))references.encoded(record,field);
            });
            ProjectedBackupRecords.inspect(analysis,snapshot,checkpoint,(dataset,record)->{
                switch(dataset) {
                    case "drafts"->references.payload(record.get("payload"));
                    case "continuations","cluster-runs","cluster-work"->{references.encoded(record,"resultJson");references.encoded(record,"viewJson");}
                    case "questions"->{var value=record.get("nodeCodesJson");if(value!=null && !value.isNull())references.array(PortableRows.json().readTree(value.asText()));}
                    default->{ /* Frozen inputs own their complete snapshot; diagnostics contain display labels. */ }
                }
            });
            for(var key:snapshot.repositories().keySet())try(var stand=source.open(snapshot.authorization(),key,limits,checkpoint)) {
                if(!stand.state().equals(snapshot.repositories().get(key)))throw new IOException("Git reference evidence changed during capture");
                for(var file:stand.files())if(file.path().toLowerCase(Locale.ROOT).endsWith(".taxdsl")) {
                    var bytes=new ByteArrayOutputStream();stand.copy(file,bytes,checkpoint);
                    document(new TaxDslParser().parse(bytes.toString(StandardCharsets.UTF_8),file.path()),references,false);
                }
            }
            return new KnowledgeBackupContributor.Selection(references.codes,references.hypotheses);
        }
        void selectedDocuments(AuthorizedBackupRequest authorization,List<BackupManifest.Repository> repositories,BackupCheckpoint checkpoint)throws IOException {
            for(var repository:repositories)try(var stand=source.open(authorization,repository.id(),limits,checkpoint)) {
                if(!stand.state().equals(repository.captured()))throw new IOException("Selected Git evidence changed during capture");
                for(var file:stand.files())if(file.path().toLowerCase(Locale.ROOT).endsWith(".taxdsl")) {
                    var bytes=new ByteArrayOutputStream();stand.copy(file,bytes,checkpoint);
                    document(new TaxDslParser().parse(bytes.toString(StandardCharsets.UTF_8),file.path()),new PortableKnowledgeReferences(),true);
                }
            }
        }
        private void document(DocumentAst document,PortableKnowledgeReferences references,boolean selected)throws IOException {
            PortableDocumentSourceReferences.requireClosed(document);
            var elements=new HashSet<String>();
            for(var block:document.getBlocks()) {
                if(block.getHeaderTokens().isEmpty())continue;String id=block.getHeaderTokens().getFirst();
                Set<String> ids=block.getKind().equals("element")?elements:null;
                if(ids!=null && !ids.add(id))throw new IOException("Duplicate selected document identity");
                if(!selected && block.getKind().equals("element")) {
                    var actual=jdbc.query("select code,taxonomy_root from taxonomy_node where code=?",(r,n)->List.of(r.getString(1),r.getString(2)),id);
                    if(block.property("taxonomy")!=null && (actual.size()!=1 || !id.equals(actual.getFirst().getFirst()) || !block.property("taxonomy").equals(actual.getFirst().get(1))))throw new IOException("Document catalogue identity is missing or inconsistent");
                    if(actual.size()==1 && id.equals(actual.getFirst().getFirst()))references.code(id);
                }
                for(String property:List.of("nodeCode","sourceCode","targetCode","taxonomyRoot"))references.code(block.property(property));
                String hypothesis=block.getExtensions().get("x-hypothesis-id");
                if(hypothesis!=null){if(!hypothesis.matches("[1-9][0-9]*"))throw new IOException("Invalid document hypothesis identity");references.hypotheses.add(new SourceRecordId("knowledge.hypothesis",hypothesis));}
            }
            var requirements = new HashSet<String>();
            for (var block : document.getBlocks()) if (block.getKind().equals("requirement") && block.getHeaderTokens().size() == 1)
                requirements.add(block.getHeaderTokens().getFirst());
            for(var block:document.getBlocks())switch(block.getKind()) {
                case "relation" -> {
                    var tokens = block.getHeaderTokens();
                    if (tokens.size() != 3) throw new IOException("Incomplete selected document relation");
                    endpoint(elements, tokens.getFirst(), references, selected);
                    endpoint(elements, tokens.get(2), references, selected);
                }
                case "mapping" -> {
                    var tokens = block.getHeaderTokens();
                    if (tokens.size() != 3 || !tokens.get(1).equals("->") || !requirements.contains(tokens.getFirst()))
                        throw new IOException("Missing selected document mapping requirement");
                    endpoint(elements, tokens.get(2), references, selected);
                }
                case "view" -> {
                    for (String id : block.propertyValues("include")) endpoint(elements, id, references, selected);
                }
                case "evidence" -> {
                    String relation = block.property("for-relation");
                    if (relation != null) {
                        var tokens = relation.split(" ");
                        if (tokens.length != 3) throw new IOException("Invalid document relation evidence");
                        endpoint(elements, tokens[0], references, selected);
                        endpoint(elements, tokens[2], references, selected);
                    }
                }
                default->{ }
            }
        }
        private void endpoint(Set<String> elements, String id, PortableKnowledgeReferences references, boolean selected) throws IOException {
            if (elements.contains(id)) return;
            if (selected) throw new IOException("Missing selected document element reference");
            var exact = jdbc.queryForList("select code from taxonomy_node where code=?", String.class, id);
            if (!exact.equals(List.of(id))) throw new IOException("Missing selected document element reference");
            references.code(id);
        }
        List<BackupIntegrationScope> integrations(SnapshotContext snapshot,BackupCheckpoint checkpoint)throws IOException {
            var selected=new ArrayList<BackupIntegrationScope>();
            for(var key:snapshot.repositories().keySet())if(key.workspaceId()!=null) {
                checkpoint.check();var branches=new TreeSet<String>();
                try(var session=repositories.open(snapshot.authorization(),key,checkpoint)){branches.addAll(session.branchNames(checkpoint));}
                branches.addAll(metadata.read("select distinct branch_name from arch_project where repository_id=? and workspace_id=? order by branch_name",
                        List.of(key.repositoryId(), key.workspaceId()), BackupLimits.MAX_ITEMS, checkpoint, r -> r.getString(1)));
                for(String branch:branches) {
                    checkpoint.check();var scope=new BackupIntegrationScope(key,branch);validateIntegrationReferences(scope,checkpoint);selected.add(scope);
                    if(selected.size()>BackupLimits.MAX_ITEMS)throw new IOException("Integration routing limit exceeded");
                }
            }
            return List.copyOf(selected);
        }
        private void validateIntegrationReferences(BackupIntegrationScope scope,BackupCheckpoint checkpoint)throws IOException {
            String tenant=new com.taxonomy.workspace.model.RepositoryTenantIdentity(scope.repository().repositoryId(),"WORKSPACE:"+scope.repository().workspaceId(),scope.branch()).scopeKey();
            var connections=jdbc.query("select id,repository_id,project_id from interop_connection where scope_id=? order by id",(r,n)->new IntegrationProject(r.getString(1),r.getString(2),r.getObject(3)==null?null:((Number)r.getObject(3)).longValue()),scope.scopeId());
            for(var connection:connections) {
                checkpoint.check();if(!scope.repository().repositoryId().equals(connection.repository()))throw new IOException("Foreign integration repository reference");
                if(connection.project()!=null) {
                    var actual=jdbc.queryForList("select scope_key from arch_project where id=?",String.class,connection.project());
                    if(!actual.equals(List.of(tenant)))throw new IOException("Missing or foreign integration project reference");
                }
                for(var requirement:jdbc.query("select requirement_id from interop_identity where connection_id=? and scope_id=? and removed=? and requirement_id is not null",(r,n)->r.getLong(1),connection.id(),scope.scopeId(),false)) {
                    checkpoint.check();var actual=jdbc.query("select scope_key,project_id from project_requirement where id=?",(r,n)->new RequirementOwner(r.getString(1),r.getLong(2)),requirement);
                    if(actual.size()!=1 || !tenant.equals(actual.getFirst().tenant()) || connection.project()!=null && !connection.project().equals(actual.getFirst().project()))throw new IOException("Missing or foreign integration requirement reference");
                }
            }
        }
        private record IntegrationProject(String id,String repository,Long project) { }
        private record RequirementOwner(String tenant,Long project) { }
    }
    private static final class StandOwnership implements BackupDataContributor {
        private final BackupComponentId owner;private final String category,dataset;private final boolean external;
        StandOwnership(String owner,String category,String dataset,boolean external){this.owner=new BackupComponentId(owner);this.category=category;this.dataset=dataset;this.external=external;}
        public BackupComponentId componentId(){return owner;}public int schemaVersion(){return 1;}public Set<String> categories(){return Set.of(category);}
        public List<String> omissions(BackupProfile profile){return external?List.of("workspace.external-git: remote services remain external dependencies; archive contains only captured local stand evidence"):List.of();}
        public void write(SnapshotContext snapshot,ComponentSink sink)throws IOException {
            sink.checkpoint();var entries=new ArrayList<Map<String,Object>>();
            for(var key:snapshot.repositories().keySet()) {
                var evidence=new LinkedHashMap<String,Object>();evidence.put("repository",key);evidence.put("archiveId",snapshot.repositoryArchiveIds().get(key));
                evidence.put("captured",snapshot.repositories().get(key));evidence.put("payloadOwner","workspace");evidence.put("externalServerBackup",false);entries.add(evidence);
            }
            byte[] document=PortableRows.json().writeValueAsBytes(Map.of("schemaVersion",1,"category",category,"repositories",entries));
            try(var input=new ByteArrayInputStream(document)){sink.write("data/"+owner.value()+"/"+dataset+".json",input);}sink.checkpoint();
        }
    }
}
