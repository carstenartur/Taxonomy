package com.taxonomy.analysis.service;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.core.env.StandardEnvironment;
import java.lang.reflect.Method;

public final class AnalysisTimingChecks {
    static Method method(Class<?> type,String name,Class<?>... args) {
        try { return type.getMethod(name,args); }
        catch(NoSuchMethodException e){throw new AssertionError(type.getSimpleName()+" must expose "+name);}
    }
    public static void resultTiming() throws Exception {
        var result = new AnalysisResult();
        var get=method(AnalysisResult.class,"getAnalysisDurationMillis");
        var set=method(AnalysisResult.class,"setAnalysisDurationMillis",Long.class);
        if(get.invoke(result)!=null)throw new AssertionError("Unknown duration is not zero");
        set.invoke(result,123456L);
        if(!Long.valueOf(123456).equals(get.invoke(result)))throw new AssertionError("Measured duration lost");
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var reloaded = json.readValue(json.writeValueAsString(result), AnalysisResult.class);
        if (!Long.valueOf(123456L).equals(reloaded.getAnalysisDurationMillis()))
            throw new AssertionError("Snapshot JSON lost the measured duration");
        if (json.readValue("{}", AnalysisResult.class).getAnalysisDurationMillis() != null)
            throw new AssertionError("Historical JSON must not invent a zero duration");
        var mapper = new com.taxonomy.analysis.controller.AnalysisSseEventMapper();
        var event = new com.taxonomy.analysis.usecase.AnalysisStreamEvent.Complete(
            "SUCCESS", java.util.Map.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), 123456L);
        if (!Long.valueOf(123456L).equals(((java.util.Map<?, ?>)mapper.map(event).payload()).get("analysisDurationMillis")))
            throw new AssertionError("SSE complete must preserve the measured duration");
        var error = new com.taxonomy.analysis.usecase.AnalysisStreamEvent.Error(
            "PARTIAL", "Stopped", java.util.Map.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of(), 9876L);
        if (!Long.valueOf(9876L).equals(((java.util.Map<?, ?>)mapper.map(error).payload()).get("analysisDurationMillis")))
            throw new AssertionError("SSE partial must preserve the measured duration");
        boolean rejected=false;
        try{set.invoke(result,-1L);}catch(java.lang.reflect.InvocationTargetException e){rejected=e.getCause() instanceof IllegalArgumentException;}
        if(!rejected)throw new AssertionError("Negative duration must be rejected");
    }
    public static void frozenRunTiming() throws Exception {
        var registry=new AnalysisProgressRegistry(new StandardEnvironment());
        var context=new WorkspaceContext("qa","qa-workspace","draft","qa-repository");
        var elapsed=method(AnalysisProgressRegistry.Snapshot.class,"elapsedMillis");
        var finished=method(AnalysisProgressRegistry.Snapshot.class,"finishedAt");
        try(var run=registry.open(null,"qa",context,null)) {
            var live=registry.snapshot(run.id(),"qa",context);
            if(finished.invoke(live)!=null)throw new AssertionError("Running operation has no finish timestamp");
            run.finish("PARTIAL");
            var terminal=registry.snapshot(run.id(),"qa",context);
            if(finished.invoke(terminal)==null)throw new AssertionError("Missing finish time");
            long measured=(Long)elapsed.invoke(terminal);
            for(int i=0;i<100;i++) {
                var again=registry.snapshot(run.id(),"qa",context);
                if((Long)elapsed.invoke(again)!=measured)throw new AssertionError("Finished duration changes on observation");
            }
            run.finish("SUCCESS");
            if(!"PARTIAL".equals(registry.snapshot(run.id(),"qa",context).status()))throw new AssertionError("Timing must not change terminal ownership");
        }
    }
    public static void main(String[] args)throws Exception {
        int failed=0;
        try{resultTiming();System.out.println("PASS result timing");}catch(AssertionError e){failed++;System.err.println(e.getMessage());}
        try{frozenRunTiming();System.out.println("PASS frozen run timing");}catch(AssertionError e){failed++;System.err.println(e.getMessage());}
        if(failed>0)throw new AssertionError(failed+" timing checks failed");
    }
}
