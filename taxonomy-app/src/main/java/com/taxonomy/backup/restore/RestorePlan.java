package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.identity.StableIdentityHash;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Exact server-produced private plan. Approval and complete owner readiness are separate proofs. */
public record RestorePlan(UUID planId,PrincipalId actor,String createdAt,String expiresAt,String archiveSha256,
        String sourceInstallationId,String applicationVersion,BackupProfile profile,GitRepresentation representation,
        SecretsSelection secrets,RestoreMode mode,UUID generationId,SourceDatabaseFamily sourceFamily,
        boolean sourceFamilyWitnessed,AuditedSourceFamilyDeclaration sourceFamilyDeclaration,TargetDatabaseIdentity targetIdentity,String targetSchemaSha256,
        List<ComponentBinding> components,List<String> requiredFeatures,List<PrincipalMapping> principals,
        List<RepositoryMapping> repositories,String registryRevision,List<RestoreDiagnostic> diagnostics) {
    public record PrincipalMapping(PrincipalId source,RestorePrincipalAllocation allocation) {
        public PrincipalMapping{Objects.requireNonNull(source);Objects.requireNonNull(allocation);}
    }
    public record RepositoryMapping(BackupRepositoryKey source,RestoreRepositoryAllocation allocation) {
        public RepositoryMapping{Objects.requireNonNull(source);Objects.requireNonNull(allocation);}
    }
    public record ComponentBinding(BackupComponentId owner,int version,BackupCompleteness completeness,
                                    List<String> resources,List<BackupComponentId> dependencies) {
        public ComponentBinding {
            Objects.requireNonNull(owner);Objects.requireNonNull(completeness);if(version<1)throw new IllegalArgumentException("Invalid restore component version");
            resources=ordered(resources,Comparator.naturalOrder());dependencies=ordered(dependencies,Comparator.comparing(BackupComponentId::value));
            resources.forEach(value->text(value,4096));
        }
    }
    public RestorePlan {
        Objects.requireNonNull(planId);Objects.requireNonNull(actor);Objects.requireNonNull(profile);Objects.requireNonNull(representation);
        Objects.requireNonNull(secrets);Objects.requireNonNull(mode);Objects.requireNonNull(generationId);Objects.requireNonNull(targetIdentity);
        hash(archiveSha256);hash(targetSchemaSha256);text(applicationVersion,255);text(registryRevision,255);
        if(sourceInstallationId==null||!UUID.fromString(sourceInstallationId).toString().equals(sourceInstallationId))throw new IllegalArgumentException("Invalid source installation binding");
        Instant created=Instant.parse(createdAt),expires=Instant.parse(expiresAt);
        if(!created.toString().equals(createdAt)||!expires.toString().equals(expiresAt)||!expires.isAfter(created)||Duration.between(created,expires).compareTo(Duration.ofHours(24))>0)
            throw new IllegalArgumentException("Invalid restore plan expiry");
        if(sourceFamilyWitnessed) {
            if(sourceFamily==null||sourceFamilyDeclaration!=null)throw new IllegalArgumentException("Invalid source database witness binding");
        } else if(sourceFamilyDeclaration!=null) {
            Instant declared=Instant.parse(sourceFamilyDeclaration.declaredAt());
            if(sourceFamily!=sourceFamilyDeclaration.family()||!archiveSha256.equals(sourceFamilyDeclaration.archiveSha256())
                    ||!actor.equals(sourceFamilyDeclaration.declaredBy())||declared.isAfter(created)||declared.isBefore(created.minus(Duration.ofMinutes(15))))
                throw new IllegalArgumentException("Invalid source database declaration binding");
        } else if(sourceFamily!=null)throw new IllegalArgumentException("Invalid source database declaration binding");
        components=ordered(components,Comparator.comparing(component->component.owner().value()));
        requiredFeatures=ordered(requiredFeatures,Comparator.naturalOrder());requiredFeatures.forEach(value->text(value,255));
        principals=ordered(principals,Comparator.comparing(mapping->mapping.source().value().toString()));
        repositories=ordered(repositories,Comparator.comparing((RepositoryMapping mapping)->mapping.source().repositoryId()).thenComparing(mapping->mapping.source().workspaceId(),Comparator.nullsFirst(Comparator.naturalOrder())));
        diagnostics=List.copyOf(diagnostics);if(diagnostics.size()>100_000)throw new IllegalArgumentException("Restore plan diagnostic budget exceeded");
        distinct(components.stream().map(ComponentBinding::owner).toList());distinct(principals.stream().map(PrincipalMapping::source).toList());
        distinct(principals.stream().map(mapping->mapping.allocation().targetPrincipal()).toList());distinct(principals.stream().map(mapping->mapping.allocation().targetScope()).toList());
        distinct(repositories.stream().map(RepositoryMapping::source).toList());distinct(repositories.stream().map(mapping->mapping.allocation().targetKey()).toList());
        distinct(repositories.stream().map(mapping->mapping.allocation().physicalStorageName()).toList());
    }
    public List<RestoreDiagnostic> blockers(){return diagnostics.stream().filter(diagnostic->diagnostic.severity()==RestoreDiagnostic.Severity.BLOCKER).toList();}
    public String sha256()throws IOException{return StableIdentityHash.sha256(RestorePlanCodec.encode(this));}
    private static <T>List<T> ordered(List<T> values,Comparator<? super T> order) {
        if(values==null||values.size()>100_000)throw new IllegalArgumentException("Restore plan item budget exceeded");
        var copy=new ArrayList<>(values);copy.forEach(Objects::requireNonNull);copy.sort(order);return List.copyOf(copy);
    }
    private static void distinct(List<?> values){if(new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException("Conflicting restore plan allocations");}
    private static void hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid restore plan digest");}
    private static void text(String value,int maximum){if(value==null||value.isBlank()||value.length()>maximum||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid restore plan text");}
}
