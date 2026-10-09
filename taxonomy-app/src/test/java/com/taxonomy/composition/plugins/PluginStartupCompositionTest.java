package com.taxonomy.composition.plugins;

import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.extension.api.llm.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.shared.extension.ExtensionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginStartupCompositionTest {
    @TempDir Path temporary;
    @Test void independentlyLoadedStartupProviderUsesTheHostPolicyAndTheSharedCatalog() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("operator-plugins"));
        buildProvider(plugins.resolve("provider.jar"));
        new ApplicationContextRunner().withUserConfiguration(Composition.class, PluginCatalogConfiguration.class)
                .withPropertyValues("taxonomy.plugins.directory=" + plugins,
                        "taxonomy.plugins.cache-directory=" + temporary.resolve("cache"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var catalog = context.getBean(ExtensionCatalog.class);
                    try (var lease = catalog.acquire(new ExtensionKey(ExtensionKind.LLM_PROVIDER, "EXAMPLE_JAR"), LlmTransportExtension.class)) {
                        assertThat(lease.plugin().id()).isEqualTo("example.provider");
                        assertThat(lease.extension().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
                    }
                    var config = context.getBean(LlmProviderConfig.class);
                    try (var scope = config.withRequestProvider("EXAMPLE_JAR")) {
                        assertThat(config.getActiveProviderId()).isEqualTo(new ProviderId("EXAMPLE_JAR"));
                        var gateway = context.getBean(LlmGatewayRegistry.class).getGatewayById(config.getActiveProviderId());
                        assertThat(gateway.extractResponseText(gateway.sendHttpRequest("frozen prompt", ""))).isEqualTo("offline result");
                        verify(context.getBean(AiPromptBudgetPolicy.class)).requireWithinBudget("frozen prompt", "EXAMPLE_JAR");
                    }
                    try (var builtin = catalog.acquire(new ExtensionKey(ExtensionKind.LLM_PROVIDER, "OPENAI"), LlmTransportExtension.class)) {
                        assertThat(builtin.extension().transport().providerName()).isEqualTo("OPENAI");
                    }
                });
        try (var caches = Files.list(temporary.resolve("cache"))) { assertThat(caches).isEmpty(); }
    }
    @Configuration(proxyBeanMethods = false)
    @Import({LlmGatewayRegistry.class, OpenAiLlmProviderExtension.class})
    static class Composition {
        @Bean LlmProviderConfig providers() { return new LlmProviderConfig(mock(LocalEmbeddingService.class)); }
        @Bean RestTemplate restTemplate() { return mock(RestTemplate.class); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean AnalysisRuntimeSettings runtimeSettings() { return (key, fallback) -> fallback; }
        @Bean AiPromptBudgetPolicy budget() { return mock(AiPromptBudgetPolicy.class); }
    }
    private void buildProvider(Path jar) throws Exception {
        Path source = temporary.resolve("ExternalProvider.java");
        Files.writeString(source, """
                package example.plugin;
                import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
                import com.taxonomy.extension.api.llm.*;
                import com.taxonomy.shared.extension.TaxonomyExtension;
                import java.util.List;
                public final class ExternalProvider implements TaxonomyPlugin {
                    public List<TaxonomyExtension> extensions() { return List.of(new Provider()); }
                    public static final class Provider implements LlmTransportExtension {
                        public LlmProviderDescriptor descriptor() {
                            return new LlmProviderDescriptor("EXAMPLE_JAR", "External", false, false, true, true, List.of());
                        }
                        public LlmTransport transport() { return new LlmTransport() {
                            public String providerName() { return "EXAMPLE_JAR"; }
                            public String sendHttpRequest(String prompt, String key) { return "offline envelope"; }
                            public String extractResponseText(String raw) { return "offline result"; }
                        }; }
                    }
                }
                """);
        String sdk = Path.of(TaxonomyPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String domain = Path.of(com.taxonomy.dto.ArchitectureReport.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path classes = Files.createDirectory(temporary.resolve("compiled"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none", "-classpath",
                sdk + java.io.File.pathSeparator + domain, "-d", classes.toString(), source.toString())).isZero();
        Manifest manifest = new Manifest(); var attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0"); attrs.putValue("Plugin-Id", "example.provider");
        attrs.putValue("Plugin-Version", "1.0.0"); attrs.putValue("Plugin-Requires", ">=1.0.0 & <2.0.0");
        attrs.putValue("Taxonomy-Plugin-Mode", "STARTUP"); attrs.putValue("Taxonomy-Plugin-Capabilities", "llm");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest); var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output); output.closeEntry();
            }
            output.putNextEntry(new JarEntry("META-INF/services/" + TaxonomyPlugin.class.getName()));
            output.write("example.plugin.ExternalProvider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
    }
}
