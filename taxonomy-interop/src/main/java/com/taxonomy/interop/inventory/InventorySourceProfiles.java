package com.taxonomy.interop.inventory;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;

/** Operator configuration only. Empty by default; restored evidence cannot activate grants. */
@Component
@ConfigurationProperties(prefix="taxonomy.inventory")
public class InventorySourceProfiles {
    private Map<String,Profile> profiles=Map.of();
    public Map<String,Profile> getProfiles() { return profiles; }
    public void setProfiles(Map<String,Profile> profiles) { this.profiles=Map.copyOf(profiles); }
    /** Each meaning/field/kind is approved for this installation; unknown or missing values do not create an assertion. */
    public record AssertionProjection(Criterion criterion,String positiveField,String startField,String endField,
                                      String organizationField,String environmentField,String nodeField,AssertionKind kind) {
        public AssertionProjection {Objects.requireNonNull(criterion);Objects.requireNonNull(positiveField);Objects.requireNonNull(kind);}
    }
    public record RelationshipProjection(String sourceRelation,RelationshipKind kind,String qualifier,AssertionKind assertionKind) {
        public RelationshipProjection {
            if(sourceRelation==null || sourceRelation.isBlank() || sourceRelation.length()>100)throw new IllegalArgumentException("Invalid source relationship meaning");
            Objects.requireNonNull(kind);Objects.requireNonNull(assertionKind);
            if(qualifier!=null && (qualifier.isBlank() || qualifier.length()>200))throw new IllegalArgumentException("Invalid relationship qualifier");
        }
    }
    public record Profile(String revision,String extensionId,String instanceId,URI origin,String repositoryId,
                          String organization,Set<String> principals,Set<String> selectedIds,List<TypeProjection> projections,
                          boolean readUsageRelations,Limits limits,Map<String,String> options,String credentialEnvironment,
                          String principalEnvironment,boolean allowPrivateNetworks,List<AssertionProjection> assertions,List<RelationshipProjection> relationships) {
        public Profile(String revision,String extensionId,String instanceId,URI origin,String repositoryId,String organization,Set<String> principals,Set<String> selectedIds,List<TypeProjection> projections,boolean readUsageRelations,Limits limits,Map<String,String> options,String credentialEnvironment,String principalEnvironment,boolean allowPrivateNetworks,List<AssertionProjection> assertions) {
            this(revision,extensionId,instanceId,origin,repositoryId,organization,principals,selectedIds,projections,readUsageRelations,limits,options,credentialEnvironment,principalEnvironment,allowPrivateNetworks,assertions,List.of());
        }
        public Profile {
            Objects.requireNonNull(revision);Objects.requireNonNull(extensionId);Objects.requireNonNull(instanceId);Objects.requireNonNull(origin);Objects.requireNonNull(repositoryId);Objects.requireNonNull(organization);
            relationships=relationships==null?List.of():List.copyOf(relationships);
            if(relationships.size()>16)throw new IllegalArgumentException("Relationship projection limit");
            assertions=assertions==null?List.of():List.copyOf(assertions);
            if(assertions.size()>16)throw new IllegalArgumentException("Assertion projection limit");
            principals=Set.copyOf(principals);selectedIds=Set.copyOf(selectedIds);projections=List.copyOf(projections);options=Map.copyOf(options);
            if(!"https".equals(origin.getScheme()) || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null)throw new IllegalArgumentException("Inventory profile requires a trusted HTTPS origin");
            if(credentialEnvironment==null || !credentialEnvironment.matches("[A-Z][A-Z0-9_]{1,99}"))throw new IllegalArgumentException("Credential must be an environment reference");
            if(principalEnvironment!=null && !principalEnvironment.matches("[A-Z][A-Z0-9_]{1,99}"))throw new IllegalArgumentException("Principal must be an environment reference");
            if(options.keySet().stream().anyMatch(k->k.toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|token|credential|authorization).*")))throw new IllegalArgumentException("Secrets must use environment references");
            if(selectedIds.isEmpty())throw new IllegalArgumentException("An approved bounded read selector is required");
            if(limits==null)limits=Limits.defaults();
        }
    }
}
