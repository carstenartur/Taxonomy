package com.taxonomy.composition.interop;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryEvidencePort;
import com.taxonomy.interop.IntegrationJson;
import com.taxonomy.interop.inventory.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;
/** Joins inventory journal/source ownership with the portfolio comparison workflow. */
@com.taxonomy.shared.features.ConditionalOnFeature({"interop","portfolio"})
@Service
public class InventoryEvidenceAdapter implements InventoryEvidencePort {
    private final InventoryJournal journal;private final InventorySourceHost sources;private final IntegrationJson json;
    public InventoryEvidenceAdapter(InventoryJournal journal,InventorySourceHost sources,IntegrationJson json){this.journal=journal;this.sources=sources;this.json=json;}
    public List<Assertion> assertions(Scope s,String candidate){return call(()->journal.assertions(s,candidate));}
    public void appendAssertion(Scope s,Assertion a){call(()->{journal.append(s,a);return null;});}
    public SourceRead readSources(Scope s,Demand d,List<String> ids){return call(()->sources.read(s,d,ids));}
    public void authorize(Scope s,Demand d,List<SourceEvidence> refs){call(()->{sources.authorize(s,d,refs);return null;});}
    public void revalidate(Scope s,Demand d,List<SourceEvidence> refs){call(()->{sources.revalidate(s,d,refs);return null;});}
    public String fingerprint(Object value){return json.fingerprint(value);}
    public void save(Scope s,Comparison c){call(()->{journal.save(s,c);return null;});}
    public Comparison read(Scope s,UUID id){return call(()->journal.read(s,id));}
    public List<Comparison> list(Scope s,Long project,String snapshot){return call(()->journal.list(s,project,snapshot));}
    public List<Reviewed> reviews(Scope s,UUID id){return call(()->journal.reviews(s,id));}
    public Reviewed review(Scope s,UUID id,Review r,Supplier<Void> apply){return call(()->journal.review(s,id,r,apply));}
    public void map(Scope s,Mapping m){call(()->{journal.map(s,m);return null;});}
    public List<Mapping> mappings(Scope s,String candidate){return call(()->journal.mappings(s,candidate));}
    private <T> T call(Supplier<T> action) {
        try{return action.get();}
        catch(com.taxonomy.interop.IntegrationProblem p){throw new Failure(p.code(),p.status(),p.getMessage());}
        catch(com.taxonomy.extension.api.inventory.InventoryReadContracts.ReadFailure p){throw new Failure(p.code(),502,p.getMessage());}
    }
}
