package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.extension.api.plugin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginJobRestartTest {
    @TempDir Path directory;
    @Test void fileDatabaseRestartRetainsIdentityAndStopsBeforeCallingAnAbsentProvider() throws Exception {
        String url="jdbc:hsqldb:file:"+directory.resolve("jobs")+";hsqldb.tx=mvcc";
        var context=context("plugin-restart");var root=TaxonomyShardRoot.of("CP");
        var binding=new PluginInvocation(new PluginIdentity("example.provider","1.0.0","a".repeat(64)),"b".repeat(64));
        var original=command("requirement");SubtaxonomyAnalysisTask task;
        try(var db=new Database(url,"update",sql->sql)) {
            var bound=new AnalyzeRequirementCommand(original.businessText(),false,20,"EXAMPLE",original.username(),original.workspaceContext(),null,new com.taxonomy.dto.AnalysisScope(java.util.Set.of("CP"), com.taxonomy.dto.AnalysisMode.TAXONOMIES_ONLY),binding);
            db.store.admit(context,bound,null,Map.of(root,"{\"root\":\"CP\"}"));task=db.task(root);
        }
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){statement.execute("SHUTDOWN");}
        var provider=new LlmProviderConfig(mock(LocalEmbeddingService.class));
        var invocation=mock(Runnable.class);
        try(var db=new Database(url,"update",sql->sql)) {
            var worker=new ClusterAnalysisService(db.store,(input,t,cancelled)->{
                assertThat(input.command().providerBinding()).isEqualTo(binding);
                try(var scope=provider.withRequestProvider(input.command().provider(),input.command().providerBinding())) {
                    invocation.run();return result("CP",80);
                }
            },new ClusterAnalysisSignals());
            var prepared=worker.prepare(task);
            assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.STOPPED);
            assertThat(prepared.completion().stopReason()).isEqualTo("PROVIDER_PLUGIN_UNAVAILABLE");
            db.completions.commit(prepared);db.store.accept(prepared.completion());
            assertThat(db.store.snapshot(context).result().getStatus()).isEqualTo("PARTIAL");
            verifyNoInteractions(invocation);
        }
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){statement.execute("SHUTDOWN");}
    }
}
