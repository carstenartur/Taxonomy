package com.taxonomy.extension.api.inventory;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import java.util.*;
import java.util.function.Supplier;
/** Narrow composition boundary. Implementations own durable typed inventory journals and source authorization. */
public interface InventoryEvidencePort {
    /** Safe composition failure; no remote body, credentials or transport exception chain. */
    final class Failure extends RuntimeException {
        private final String code;private final int status;
        public Failure(String code,int status,String detail) {super(detail);this.code=code;this.status=status;}
        public String code(){return code;}public int status(){return status;}
    }
    record Scope(String repositoryId,String scopeKey,String branch,String actor) {
        public Scope { for(var v:List.of(repositoryId,scopeKey,branch,actor)) if(v.isBlank()) throw new IllegalArgumentException("Missing inventory scope"); }
    }
    record SourceRead(List<Candidate> candidates,List<SourceEvidence> sources) {
        public SourceRead { candidates=List.copyOf(candidates);sources=List.copyOf(sources); }
    }
    List<Assertion> assertions(Scope scope,String candidateId);
    void appendAssertion(Scope scope,Assertion assertion);
    SourceRead readSources(Scope scope,Demand demand,List<String> profileIds);
    void authorize(Scope scope,Demand demand,List<SourceEvidence> sources);
    default void revalidate(Scope scope,Demand demand,List<SourceEvidence> sources) { authorize(scope,demand,sources); }
    String fingerprint(Object value);
    void map(Scope scope,Mapping mapping);
    List<Mapping> mappings(Scope scope,String candidateId);
    void save(Scope scope,Comparison comparison);
    Comparison read(Scope scope,UUID comparisonId);
    List<Comparison> list(Scope scope,Long projectId,String snapshotId);
    List<Reviewed> reviews(Scope scope,UUID comparisonId);
    Reviewed review(Scope scope,UUID comparisonId,Review review,Supplier<Void> application);
}
