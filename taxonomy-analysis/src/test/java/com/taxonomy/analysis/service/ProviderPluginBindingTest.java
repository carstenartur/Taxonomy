package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.extension.api.llm.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderPluginBindingTest {
    private final PluginCatalog catalog = new PluginCatalog();
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("taxonomy.llm.providers.example.configuration-revision", "deployment-1")
            .withProperty("taxonomy.llm.providers.example.model", "model-1");
    private final LlmProviderConfig config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
    private final LlmTransport transport = mock(LlmTransport.class);
    ProviderPluginBindingTest() {
        when(transport.providerName()).thenReturn("EXAMPLE");
        var extension = new LlmTransportExtension() {
            public LlmProviderDescriptor descriptor() { return new LlmProviderDescriptor("EXAMPLE", "Example", false, false, true, true, List.of()); }
            public LlmTransport transport() { return transport; }
        };
        config.configureExtensions(List.of(extension), environment);
        ReflectionTestUtils.setField(config, "catalog", catalog);
        catalog.publish(new PluginDescriptor(new PluginIdentity("example.provider", "1.0.0", "a".repeat(64)),
                ">=1.0.0 & <2.0.0", List.of(), Set.of(), PluginMode.STARTUP), List.of(extension));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(LlmProvider.class)
    void builtinProvidersRetainTheirBindingWithoutCallingANetwork(LlmProvider provider) {
        var builtin=new com.taxonomy.extension.api.llm.LlmProviderExtension() {
            public LlmProviderDescriptor descriptor() {return new LlmProviderDescriptor(provider.name(),provider.name(),false,false,true,true,List.of());}
        };
        catalog.publish(new PluginDescriptor(new PluginIdentity("builtin."+provider.name().toLowerCase(java.util.Locale.ROOT).replace('_','-'),"1.0.0","e".repeat(64)),
                ">=1.0.0",List.of(),Set.of(),PluginMode.STARTUP),List.of(builtin));
        var binding=config.captureProviderBinding(provider.name());
        try(var scope=config.withRequestProvider(provider.name(),binding)) {
            assertThat(config.getActiveProviderId()).isEqualTo(provider.id());
        }
        verify(transport, never()).sendHttpRequest(any(), any());
    }
    @Test void continuationIdentityChangesWithProviderArtifactOrConfiguration() throws Exception {
        var prompts=mock(PromptTemplateService.class);when(prompts.getAllTemplateCodes()).thenReturn(List.of());
        var llm=new LlmService(config,null,new tools.jackson.databind.json.JsonMapper(),null,prompts,null,null);
        String original=llm.recoveryPolicyFingerprint("EXAMPLE");
        environment.setProperty("taxonomy.llm.providers.example.configuration-revision","deployment-2");
        assertThat(llm.recoveryPolicyFingerprint("EXAMPLE")).isNotEqualTo(original);
        environment.setProperty("taxonomy.llm.providers.example.configuration-revision","deployment-1");
        assertThat(llm.recoveryPolicyFingerprint("EXAMPLE")).isEqualTo(original);
        var old=catalog.snapshot().plugins().getFirst();
        var key=catalog.snapshot().extensions().getFirst().key();
        LlmProviderExtension extension;
        try(var lease=catalog.acquire(key,LlmProviderExtension.class)){extension=lease.extension();}
        catalog.beginDraining(old.identity());catalog.remove(old.identity());
        catalog.publish(new PluginDescriptor(new PluginIdentity(old.identity().id(),old.identity().version(),"f".repeat(64)),
                old.hostApiRange(),old.requires(),old.capabilities(),old.mode()),List.of(extension));
        assertThat(llm.recoveryPolicyFingerprint("EXAMPLE")).isNotEqualTo(original);
    }
    @Test void capturesExactArtifactAndRetainsTheProviderForExecution() {
        var binding = config.captureProviderBinding("EXAMPLE");
        assertThat(binding.plugin().artifactSha256()).isEqualTo("a".repeat(64));
        assertThat(binding.configurationRevision()).matches("[a-f0-9]{64}");
        try (var scope = config.withRequestProvider("EXAMPLE", binding)) {
            assertThat(config.getActiveProviderId()).isEqualTo(new ProviderId("EXAMPLE"));
        }
        verify(transport, never()).sendHttpRequest(any(), any());
    }
    @Test void aDifferentArtifactStopsBeforeProviderInvocation() {
        var binding = config.captureProviderBinding("EXAMPLE");
        var wrong = new PluginInvocation(new PluginIdentity(binding.plugin().id(), "1.0.0", "b".repeat(64)), binding.configurationRevision());
        assertThatThrownBy(() -> config.withRequestProvider("EXAMPLE", wrong)).hasMessage("PROVIDER_PLUGIN_UNAVAILABLE");
        verify(transport, never()).sendHttpRequest(any(), any());
    }
    @Test void changedModelAndOperatorRevisionRequireNewAdmission() {
        var binding = config.captureProviderBinding("EXAMPLE");
        environment.setProperty("taxonomy.llm.providers.example.model", "model-2");
        assertThatThrownBy(() -> config.withRequestProvider("EXAMPLE", binding)).hasMessage("PROVIDER_CONFIGURATION_CHANGED");
        environment.setProperty("taxonomy.llm.providers.example.model", "model-1");
        environment.setProperty("taxonomy.llm.providers.example.configuration-revision", "deployment-2");
        assertThatThrownBy(() -> config.withRequestProvider("EXAMPLE", binding)).hasMessage("PROVIDER_CONFIGURATION_CHANGED");
        verify(transport, never()).sendHttpRequest(any(), any());
    }
    @Test void credentialsNeverEnterThePublicRevisionAndExternalLegacyJobsFailClosed() {
        var before = config.captureProviderBinding("EXAMPLE");
        environment.setProperty("taxonomy.llm.providers.example.api-key", "private-value");
        assertThat(config.captureProviderBinding("EXAMPLE")).isEqualTo(before);
        assertThatThrownBy(() -> config.withRequestProvider("EXAMPLE", null)).hasMessage("PROVIDER_BINDING_REQUIRED");
        environment.setProperty("taxonomy.llm.providers.example.configuration-revision", "");
        assertThatThrownBy(() -> config.captureProviderBinding("EXAMPLE")).hasMessage("PROVIDER_CONFIGURATION_REVISION_REQUIRED");
    }
    @Test void anAbsentPluginCannotSelectTheWorkersDefault() {
        var binding = config.captureProviderBinding("EXAMPLE");
        var restarted = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        assertThatThrownBy(() -> restarted.withRequestProvider("EXAMPLE", binding)).hasMessage("PROVIDER_PLUGIN_UNAVAILABLE");
    }
}
