package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.backup.runtime.BackupAuthorizationService;
import javax.sql.DataSource;
import java.io.*;
import java.time.*;
import java.util.*;

/** Read-only private preflight. A witnessed vendor and a parser do not grant import support. */
public final class RestorePlanner {
    private static final BackupComponentId APPLICATION=new BackupComponentId("application");
    private static final List<String> IDENTITY_KINDS=List.of("principals","bindings","local-accounts","roles","user-roles","capability-grants","version-grants","access-audit");
    private final BackupAuthorizationService authority;private final Clock clock;private final String applicationVersion;private final RestoreSupportRegistry registry;
    public RestorePlanner(BackupAuthorizationService authority,Clock clock,String applicationVersion,RestoreSupportRegistry registry) {
        this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);this.applicationVersion=Objects.requireNonNull(applicationVersion);this.registry=Objects.requireNonNull(registry);
    }
    public RestorePlan plan(VerifiedBackup archive,DataSource active,DataSource target,UUID generationId,
            Map<BackupRepositoryKey,RestoreRepositoryAllocation> repositories,Map<PrincipalId,RestorePrincipalAllocation> principals,
            RestoreMode mode,PrincipalId actor,AuditedSourceFamilyDeclaration declaration,BackupCheckpoint checkpoint)throws IOException {
        requireAuthority(actor);checkpoint.check();var manifest=archive.manifest();
        if(!applicationVersion.equals(manifest.applicationVersion()))throw new IOException("Restore application version requires a reviewed converter");
        var targetIdentity=TargetDatabaseIdentity.proveIsolated(active,target,checkpoint);
        var schema=TargetSchemaEvidence.inspect(target,checkpoint);
        if(!schema.tableNames().isEmpty()||schema.populated())throw new IOException("Restore target must be genuinely empty before planning");
        Map<BackupRepositoryKey,RestoreRepositoryAllocation> repositoryMappings;Map<PrincipalId,RestorePrincipalAllocation> principalMappings;
        try{repositoryMappings=Map.copyOf(repositories);principalMappings=Map.copyOf(principals);}catch(NullPointerException malformed){throw new IOException("Restore mapping is incomplete or foreign");}
        var sourceKeys=new HashSet<BackupRepositoryKey>();manifest.repositories().forEach(repository->sourceKeys.add(repository.id()));
        if(!sourceKeys.equals(repositoryMappings.keySet()))throw new IOException("Restore repository mapping is incomplete or foreign");
        var targetRepositoryIds=new HashMap<String,String>();var storageNames=new HashSet<String>();
        for(var entry:repositoryMappings.entrySet()) {
            checkpoint.check();var source=entry.getKey();var allocation=entry.getValue();
            if((source.workspaceId()==null)!=(allocation.targetKey().workspaceId()==null)||!storageNames.add(allocation.physicalStorageName().toLowerCase(Locale.ROOT)))throw new IOException("Restore repository mapping changes or merges routing namespaces");
            String old=targetRepositoryIds.putIfAbsent(source.repositoryId(),allocation.targetKey().repositoryId());
            if(old!=null&&!old.equals(allocation.targetKey().repositoryId()))throw new IOException("Restore repository mapping splits one source repository");
        }
        if(new HashSet<>(targetRepositoryIds.values()).size()!=targetRepositoryIds.size())throw new IOException("Restore repository mapping merges source repositories");
        var family=RestoreArchiveRecords.sourceDatabase(archive,checkpoint);
        if(declaration!=null) {
            Instant declared=Instant.parse(declaration.declaredAt()),now=clock.instant();
            if(family.isPresent()||!declaration.archiveSha256().equals(archive.archiveSha256())||!declaration.declaredBy().equals(actor)
                    ||declared.isAfter(now)||declared.isBefore(now.minus(Duration.ofMinutes(15))))throw new IOException("Restore source family declaration binding is invalid");
        }
        var diagnostics=new ArrayList<>(registry.inspect(manifest));
        if(family.isEmpty()&&declaration==null)diagnostics.add(blocker("SOURCE_DATABASE_FAMILY_REQUIRED",null,null));
        if(declaration!=null)diagnostics.add(new RestoreDiagnostic("SOURCE_FAMILY_OPERATOR_DECLARED",RestoreDiagnostic.Severity.INFORMATION,null,null));
        if(mode!=RestoreMode.EMPTY_TARGET)diagnostics.add(blocker("UNSUPPORTED_MODE",null,null));
        var knownResources=new HashSet<String>();knownResources.add("verification/capture.json");
        boolean identity=RestoreArchiveRecords.declares(archive,APPLICATION,"identities/principals.ndjson");
        if(identity) {
            new RestoreIdentityImporter().preview(archive,principalMappings,checkpoint);
            IDENTITY_KINDS.forEach(kind->knownResources.add("identities/"+kind+".ndjson"));knownResources.add("identities/installation.json");
            if(manifest.request().secrets()==SecretsSelection.INCLUDE_ENCRYPTED)knownResources.add("protected/identities/password-hashes.ndjson");
        } else if(!principalMappings.isEmpty())throw new IOException("Restore principal mapping has no declared source identity owner");
        var components=new ArrayList<RestorePlan.ComponentBinding>();
        for(var component:manifest.components()) {
            checkpoint.check();components.add(new RestorePlan.ComponentBinding(component.id(),component.version(),component.completeness(),component.entryPaths(),new ArrayList<>(component.dependencies())));
            for(String resource:component.entryPaths())if(!knownResources.contains(resource))diagnostics.add(blocker("UNREGISTERED_RESOURCE",component.id().value(),resource));
            if(component.completeness()!=BackupCompleteness.COMPLETE)diagnostics.add(blocker("NONCOMPLETE_COMPONENT",component.id().value(),null));
        }
        var principalList=principalMappings.entrySet().stream().map(entry->new RestorePlan.PrincipalMapping(entry.getKey(),entry.getValue())).toList();
        var repositoryList=repositoryMappings.entrySet().stream().map(entry->new RestorePlan.RepositoryMapping(entry.getKey(),entry.getValue())).toList();
        Instant created=clock.instant();checkpoint.check();
        return new RestorePlan(UUID.randomUUID(),actor,created.toString(),created.plus(Duration.ofMinutes(15)).toString(),archive.archiveSha256(),manifest.sourceInstallationId(),
                applicationVersion,manifest.request().profile(),manifest.request().gitRepresentation(),manifest.request().secrets(),mode,generationId,
                family.map(SourceDatabaseMetadata::family).orElse(declaration==null?null:declaration.family()),family.isPresent(),declaration,targetIdentity,schema.schemaSha256(),components,
                new ArrayList<>(manifest.requiredFeatures()),principalList,repositoryList,registry.revision(),diagnostics);
    }
    public RestorePlan plan(VerifiedBackup archive,DataSource active,DataSource target,UUID generationId,
            Map<BackupRepositoryKey,RestoreRepositoryAllocation> repositories,Map<PrincipalId,RestorePrincipalAllocation> principals,
            RestoreMode mode,PrincipalId actor,BackupCheckpoint checkpoint)throws IOException {
        return plan(archive,active,target,generationId,repositories,principals,mode,actor,null,checkpoint);
    }
    void requireAuthority(PrincipalId actor){authority.require(actor,BackupCapability.RESTORE_INSTALLATION,new BackupScope.Installation());}
    void requireSupported(RestorePlan plan)throws IOException{registry.requireSupported(plan);}
    private static RestoreDiagnostic blocker(String code,String owner,String resource){return new RestoreDiagnostic(code,RestoreDiagnostic.Severity.BLOCKER,owner,resource);}
}
