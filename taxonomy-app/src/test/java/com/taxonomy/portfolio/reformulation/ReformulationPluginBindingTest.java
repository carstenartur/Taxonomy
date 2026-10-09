package com.taxonomy.portfolio.reformulation;

import com.taxonomy.analysis.reformulation.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import com.taxonomy.extension.api.llm.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.portfolio.reformulation.ReformulationDtos.Run;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReformulationPluginBindingTest {
    private final JsonMapper json=new JsonMapper();
    private final PluginInvocation binding=new PluginInvocation(new PluginIdentity("example.provider","1.0.0","a".repeat(64)),"b".repeat(64));
    private Run persistedRun() {
        var old=new Run("run","proposal",1,"QUEUED","EXAMPLE","model","prompt-v1","schema-v1","prompt",null,null,null,"actor",Instant.EPOCH,Map.of());
        ObjectNode stored=json.valueToTree(old);stored.set("providerBinding",json.valueToTree(binding));
        return json.readValue(json.writeValueAsString(stored),Run.class);
    }
    @Test void persistedRunRetainsExactArtifactAndPublicConfigurationRevision() {
        var loaded=persistedRun();
        assertThat(json.valueToTree(loaded).path("providerBinding")).isEqualTo(json.valueToTree(binding));
    }
    @Test void recoveredProviderReplacementStopsBeforeUsageOrSourceRead() {
        var run=persistedRun();
        var providers=new LlmProviderConfig(mock(LocalEmbeddingService.class));
        var transport=mock(LlmTransport.class);when(transport.providerName()).thenReturn("EXAMPLE");
        var extension=new LlmTransportExtension() {
            public LlmTransport transport() {return transport;}
            public LlmProviderDescriptor descriptor() {return new LlmProviderDescriptor("EXAMPLE","Example",false,false,true,true,List.of());}
        };
        var environment=new MockEnvironment().withProperty("taxonomy.llm.providers.example.configuration-revision","one");
        ReflectionTestUtils.invokeMethod(providers,"configureExtensions",List.of(extension),environment);
        var catalog=new PluginCatalog();
        catalog.publish(new PluginDescriptor(new PluginIdentity("example.provider","1.0.0","c".repeat(64)),">=1.0.0",List.of(),Set.of(),PluginMode.STARTUP),List.of(extension));
        ReflectionTestUtils.setField(providers,"catalog",catalog);
        var recovery=mock(ReformulationRecoveryService.class);
        var dispatch=new ReformulationRecoveryService.Dispatch(1L,2L,"proposal","actor",null,run,"endpoint");
        var claim=new ReformulationRecoveryService.Claim(dispatch,"owner",1);
        when(recovery.dueAfter(1,"")).thenReturn(List.of(dispatch));
        when(recovery.claim(eq(dispatch),anyString())).thenReturn(Optional.of(claim));
        var executor=mock(AsyncTaskExecutor.class);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(executor).execute(any(Runnable.class));
        var engine=mock(FrozenReformulationEngine.class);var reconciler=mock(CrossTaxonomyReconciler.class);
        var usage=mock(ReformulationUsageService.class);
        new ReformulationExecutionService(mock(ReformulationService.class),engine,reconciler,providers,executor,json,recovery,usage).recoverAvailable(1);
        verify(recovery).finish(eq(claim),isNull(),eq("PROVIDER_PLUGIN_UNAVAILABLE"),any());
        verify(recovery,never()).source(any());verifyNoInteractions(usage,engine,reconciler);
    }
}
