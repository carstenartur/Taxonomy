package com.taxonomy.interop.inventory;
import com.taxonomy.extension.api.inventory.*;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.interop.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Manual, grant-bound, leased inventory reads. Read failures retain previous successful observations. */
@Service
public class InventorySourceHost {
    private final InventorySourceProfiles profiles;private final ExtensionCatalog catalog;private final InventoryJournal journal;private final IntegrationJson json;private final Environment environment;
    public InventorySourceHost(InventorySourceProfiles profiles,ExtensionCatalog catalog,InventoryJournal journal,IntegrationJson json,Environment environment) {this.profiles=profiles;this.catalog=catalog;this.journal=journal;this.json=json;this.environment=environment;}
    public SourceRead read(Scope scope,Demand demand,List<String> selected) {
        if(selected.size()>3 || new HashSet<>(selected).size()!=selected.size())throw new IllegalArgumentException("Select at most three distinct approved source profiles");
        // Check the complete authorization set before the first outbound call.
        for(String id:selected) profile(scope,demand,id,null);
        long allowedBytes=0;int allowedRequests=0,allowedObjects=0,allowedSeconds=0;
        for(String id:selected) {var p=profile(scope,demand,id,null);allowedBytes+=p.limits().maxTotalBytes();allowedRequests+=p.limits().maxRequests();allowedObjects+=p.limits().maxObjects();allowedSeconds+=p.limits().timeoutSeconds();}
        if(allowedBytes>16*1024*1024 || allowedRequests>100 || allowedObjects>10000 || allowedSeconds>120)throw new IllegalArgumentException("Combined inventory read budget exceeds host bounds");
        var candidates=new ArrayList<Candidate>();var sources=new ArrayList<SourceEvidence>();
        for(String id:selected) {
            var p=profile(scope,demand,id,null);var operation=UUID.randomUUID();
            var state=new InternalState(scope.repositoryId(),scope.scopeKey(),scope.branch(),null,0,demand.projectId(),demand.snapshotFingerprint());
            var authority=new IntegrationContext(operation,AuthorityMode.LINK_ONLY,new ExternalScope("INVENTORY",p.instanceId(),null),state,scope.actor(),id,p.revision());
            var request=new Request(authority,p.instanceId(),p.revision(),p.origin(),p.projections(),p.selectedIds(),p.readUsageRelations(),p.limits(),p.options());
            try(var lease=catalog.acquire(new ExtensionKey(ExtensionKind.INVENTORY_SOURCE,p.extensionId()),InventorySourceExtension.class)) {
                var artifact=Objects.requireNonNull(lease.plugin(),"Inventory extension artifact identity is required");
                Observation observed=lease.extension().read(request,new ReadAccess() {
                    public char[] credential() {return secret(p.credentialEnvironment());}
                    public char[] principal() {return p.principalEnvironment()==null?null:secret(p.principalEnvironment());}
                    public boolean allowPrivateNetworks() {return p.allowPrivateNetworks();}
                });
                if(observed.objects().stream().anyMatch(o->!p.instanceId().equals(o.key().instance()) || !p.selectedIds().contains(o.key().id())))throw new ReadFailure("OBSERVATION_SELECTOR_MISMATCH");
                if(!observed.request().equals(request) || observed.objects().size()>p.limits().maxObjects() || observed.relations().size()>p.limits().maxRelations() || observed.requests()>p.limits().maxRequests() || observed.bytes()>p.limits().maxTotalBytes())throw new ReadFailure("INVALID_OBSERVATION");
                if(candidates.size()+observed.objects().size()>100)throw new ReadFailure("CANDIDATE_LIMIT");
                var normalized=new ArrayList<Candidate>();
                for(var object:observed.objects()) {
                    if(candidates.size()>=100)throw new ReadFailure("CANDIDATE_LIMIT");
                    String key="external:"+json.fingerprint(object.key());
                    var metadata=new TreeMap<String,String>();metadata.put("sourceProfile",id);metadata.put("instance",object.key().instance());metadata.put("namespace",object.key().namespace());metadata.put("externalId",object.key().id());metadata.put("sourceType",object.sourceType());metadata.put("observationId",operation.toString());
                    object.fields().forEach((field,value)->metadata.put("field:"+field,value.state()+":"+Objects.toString(value.value(),"")));
                    // Adapter fields are evidence, never implicit organizational availability or confirmed mappings.
                    normalized.add(new Candidate(key,null,field(object,"name",object.key().id()),object.kind().name(),json.fingerprint(object),metadata,assertions(object,p,key,operation,observed.finishedAt())));
                }
                journal.observation(scope,id,p.revision(),demand.question().organization(),operation,observed,null);
                candidates.addAll(normalized);
                sources.add(new SourceEvidence(id,p.revision(),operation.toString(),observed.finishedAt(),json.fingerprint(p.selectedIds()),"SUCCEEDED",observed.limitations(),json.fingerprint(p),artifact.toString()));
            } catch(ReadFailure failure) {
                journal.observation(scope,id,p.revision(),demand.question().organization(),UUID.randomUUID(),null,failure.code());throw failure;
            } catch(RuntimeException failure) {
                journal.observation(scope,id,p.revision(),demand.question().organization(),UUID.randomUUID(),null,"SOURCE_UNAVAILABLE");throw new ReadFailure("SOURCE_UNAVAILABLE");
            }
        }
        return new SourceRead(candidates,sources);
    }
    public void authorize(Scope scope,Demand demand,List<SourceEvidence> sources) {
        for(var source:sources) {
            var p=profile(scope,demand,source.profileId(),source.revision());
            if(source.profileFingerprint()!=null && !source.profileFingerprint().equals(json.fingerprint(p)))throw IntegrationProblem.conflict("INVENTORY_PROFILE_CHANGED");
        }
    }
    public void revalidate(Scope scope,Demand demand,List<SourceEvidence> sources) {
        authorize(scope,demand,sources);
        for(var source:sources) {
            if(!source.observationId().equals(journal.latestSuccessful(scope,source.profileId(),demand.question().organization())))throw IntegrationProblem.conflict("INVENTORY_OBSERVATION_CHANGED");
            if(source.extensionArtifact()!=null && !source.extensionArtifact().equals("ARTIFACT_UNRECORDED")) {
                var p=profile(scope,demand,source.profileId(),source.revision());
                var registered=catalog.snapshot().extensions().stream().filter(e->e.key().equals(new ExtensionKey(ExtensionKind.INVENTORY_SOURCE,p.extensionId()))).findFirst().orElseThrow(()->IntegrationProblem.conflict("INVENTORY_PLUGIN_CHANGED"));
                if(!source.extensionArtifact().equals(registered.plugin().toString()))throw IntegrationProblem.conflict("INVENTORY_PLUGIN_CHANGED");
            }
        }
    }
    private InventorySourceProfiles.Profile profile(Scope scope,Demand demand,String id,String revision) {
        var p=profiles.getProfiles().get(id);
        if(p==null || !p.repositoryId().equals(scope.repositoryId()) || !p.organization().equals(demand.question().organization()) || !p.principals().contains(scope.actor()))throw new IntegrationProblem("INVENTORY_SOURCE_DENIED",403,"Inventory source profile is not authorized");
        if(revision!=null && !revision.equals(p.revision()))throw IntegrationProblem.conflict("INVENTORY_PROFILE_CHANGED");return p;
    }
    private List<Assertion> assertions(ObservedObject object,InventorySourceProfiles.Profile profile,String key,UUID observation,Instant recorded) {
        var assertions=new ArrayList<Assertion>();int index=0;
        for(var rule:profile.assertions()) {
            String value=present(object,rule.positiveField());
            if(!"true".equals(value) && !"false".equals(value))continue;
            try {
                var start=boundary(present(object,rule.startField()));var end=boundary(present(object,rule.endField()));
                assertions.add(new Assertion(observation+":"+json.fingerprint(object.key())+":"+(index++),key,rule.criterion(),present(object,rule.nodeField()),Boolean.parseBoolean(value),start,end,
                    present(object,rule.organizationField()),present(object,rule.environmentField()),rule.kind(),"Profile "+object.key().instance()+"/"+object.key().namespace()+"/"+object.key().id()+" field "+rule.positiveField(),recorded));
            } catch(IllegalArgumentException invalid) {throw new ReadFailure("INVALID_ASSERTION_VALUE");}
        }
        return assertions;
    }
    private String present(ObservedObject object,String field) {if(field==null)return null;var value=object.fields().get(field);return value!=null && value.state()==State.PRESENT?value.value():null;}
    private Boundary boundary(String value) {return value==null?null:new Boundary(value,value.length()==4?Precision.YEAR:Precision.DATE);}
    private char[] secret(String ref) {String value=environment.getProperty(ref);if(value==null||value.isBlank())throw new ReadFailure("CREDENTIAL_UNAVAILABLE");return value.toCharArray();}
    private String field(ObservedObject object,String field,String fallback) {var value=object.fields().get(field);return value!=null&&value.state()==State.PRESENT?value.value():fallback;}
}
