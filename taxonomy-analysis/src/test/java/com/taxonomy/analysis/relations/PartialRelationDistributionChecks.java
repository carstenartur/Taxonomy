package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.dto.RelationSearchModel.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.taxonomy.analysis.relations.RelationSearchDistribution.*;

/** Uses the production deterministic scheduler and scalar transport, without a broker stub. */
public final class PartialRelationDistributionChecks {
    private PartialRelationDistributionChecks() { }
    public static void main(String[] args) throws Exception {
        int passed = 0; var failures = new ArrayList<String>();
        for (var method : Arrays.stream(PartialRelationDistributionChecks.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("test")).sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()) {
            try { method.invoke(null); passed++; System.out.println("PASS " + method.getName()); }
            catch (java.lang.reflect.InvocationTargetException failure) { failures.add(method.getName() + ": " + failure.getCause()); }
        }
        System.out.println("Partial distribution: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }
    public static void testFailureSettlesOnlyItsGrantAndIndependentWorkContinues() {
        var plan = plan(); var wave = ready(plan, List.of()); check(wave.size()==2, "two fully funded branches first");
        var failed = failure(wave.get(0), "RELATION_EVALUATION_FAILED");
        var complete = success(wave.get(1), 1);
        check(ready(plan,List.of(failed)).equals(List.of(wave.get(1))), "wait for the remaining wave member, not abort");
        var next = ready(plan, List.of(failed,complete));
        check(next.size()==1 && next.getFirst().ordinal()==2, "third independent intent must still be admitted");
        var all = List.of(failed,complete,success(next.getFirst(),1));
        var report = combine(plan,all);
        check(report.totalCalls()==5 && report.totalCalls()<=report.maxCalls(), "no uncharged failure or budget inflation");
        check(report.result().unfinished().size()==1, "only the failed branch remains open");
        check(report.result().trace().size()==2, "independent negative findings preserved, failed answer not made negative");
        check(ready(plan,all).isEmpty(), "all planned work attempted exactly once");
    }
    public static void testFailureCompletionOrderCannotReleaseExtraBudget() {
        var plan = plan(); var wave = ready(plan,List.of());
        var one = failure(wave.get(0),"RELATION_EVALUATION_FAILED"); var two = success(wave.get(1),1);
        check(ready(plan,List.of(one,two)).equals(ready(plan,List.of(two,one))), "order-independent next grants");
        check(combine(plan,List.of(one,two)).equals(combine(plan,List.of(two,one))), "order-independent retained evidence");
    }
    public static void testCancellationRemainsAnOperationStop() {
        var plan=plan(); var wave=ready(plan,List.of());
        check(ready(plan,List.of(failure(wave.get(0),"CANCELLED"),success(wave.get(1),1))).isEmpty(), "no new work after cancellation");
    }
    public static void testBlockingStopDominatesEarlierIndependentFailure() {
        var plan = plan(); var wave = ready(plan, List.of());
        var ordinary = RelationSearchDistribution.failed(plan, wave.getFirst());
        for (String reason : List.of("CANCELLED", "TIME_LIMIT", "MEMORY_PRESSURE", "AWAITING_DECISION")) {
            var stopped = RelationSearchDistribution.failed(plan, wave.get(1), reason);
            var results = List.of(ordinary, stopped);
            check(ready(plan, results).isEmpty(), "stop cannot admit independent work");
            var report = combine(plan, results);
            check(report.stopReason().equals(reason), "aggregate must not hide " + reason + " behind the earlier evaluation failure");
            check(report.equals(combine(plan, List.of(stopped, ordinary))), "stop reason cannot depend on completion order");
            check(report.result().edges().isEmpty() && report.result().trace().isEmpty(), "neither failure invents semantic evidence");
        }
    }
    public static void testUnknownStopDominatesEarlierIndependentFailure() {
        var plan = plan(); var wave = ready(plan, List.of());
        var ordinary = RelationSearchDistribution.failed(plan, wave.getFirst());
        var stopped = RelationSearchDistribution.failed(plan, wave.get(1), "BROKER_EXPIRED");
        check(combine(plan, List.of(ordinary, stopped)).stopReason().equals("BROKER_EXPIRED"),
                "an unrecognized stop remains explicit rather than becoming an ordinary assessment failure");
        check(ready(plan, List.of(ordinary, stopped)).isEmpty(), "unknown stop does not authorize more work");
    }
    public static void testForeignAndOverBudgetGrantRemainsRejected() {
        var plan=plan(); var wave=ready(plan,List.of());
        var foreign=new Work("another-plan",0,TaxonomyShardRoot.of("IP"),2,2);
        expectInvalid(()->ready(plan,List.of(failure(foreign,"RELATION_EVALUATION_FAILED"))));
        expectInvalid(()->new WorkResult(wave.getFirst(),empty(),3,"RELATION_EVALUATION_FAILED"));
        var changed=new Work(plan.preparationId(),0,TaxonomyShardRoot.of("IP"),1,1);
        expectInvalid(()->ready(plan,List.of(failure(changed,"RELATION_EVALUATION_FAILED"))));
    }
    public static void testPersistedFailureReplaysWithoutNewSemanticDecisions() throws Exception {
        var plan=plan(); var wave=ready(plan,List.of()); var json=new ObjectMapper();
        var saved=json.readValue(json.writeValueAsString(failure(wave.getFirst(),"RELATION_EVALUATION_FAILED")),WorkResult.class);
        check(saved.result().edges().isEmpty() && saved.result().trace().isEmpty(),"no invented accepted or negative relation");
        check(saved.calls()==2,"unknown consumption retains the full admitted allowance");
        check(ready(plan,List.of(saved)).equals(List.of(wave.get(1))),"failed grant is not repeated");
    }
    public static void testCatalogueDatabaseOutageIsNotACompletedPartialAssessment() {
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { throw new org.springframework.dao.DataAccessResourceFailureException("offline"); }
            public List<Node> roots() { return List.of(new Node("IP","IP","Information","",true)); }
            public List<Node> children(Node node) { return List.of(); }
        };
        var engine = new RequirementRelationSearch(catalogue,new com.taxonomy.relations.service.RelationCompatibilityMatrix(),
                prompt -> { throw new AssertionError("no provider work during catalogue outage"); }, () -> {});
        try {
            engine.search("Record status",Map.of("BP-1",70),plan().options());
            throw new AssertionError("database outage became a completed partial assessment");
        } catch (org.springframework.dao.DataAccessResourceFailureException expected) { }
    }
    static Plan plan() {
        var source=new Node("BP-1","BP","Process","",false);
        var contribution=new Contribution(source,"Record status","Record status","");
        var root=new Node("IP","IP","Information", "",true);
        var items=java.util.stream.IntStream.range(0,3).mapToObj(i->new Item(i,0,0,"CONSUMES",Direction.OUTGOING,TaxonomyShardRoot.of("IP"),2)).toList();
        return new Plan(1,"preparation","hash",List.of("source-result"),new RequirementRelationSearch.Options(new Limits(6,8,10,512),32),
                List.of(source),List.of(root),List.of(new SourceAssessment(source,List.of(contribution),"required","")),items,1,0,List.of(),"",true);
    }
    static WorkResult failure(Work grant,String reason) {
        if (reason.equals("RELATION_EVALUATION_FAILED")) return RelationSearchDistribution.failed(plan(),grant);
        var unfinished=new Unfinished("BP-1","CONSUMES",Direction.OUTGOING,List.of("IP"),reason,"");
        return new WorkResult(grant,new Result(List.of(),List.of(unfinished),List.of(),0,0,0),Math.min(grant.maxCalls(),grant.maxWorkItems()),reason);
    }
    static WorkResult success(Work grant,int calls) {
        var trace=new Trace("BP-1","CONSUMES",Direction.OUTGOING,Phase.NAVIGATE,"IP",Outcome.REJECT,"not needed");
        return new WorkResult(grant,new Result(List.of(),List.of(),List.of(trace),calls,calls,0),calls,"");
    }
    static Result empty(){return new Result(List.of(),List.of(),List.of(),0,0,0);}
    static void expectInvalid(Runnable call) {try{call.run();throw new AssertionError("invalid grant accepted");}catch(IllegalArgumentException expected){}}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
