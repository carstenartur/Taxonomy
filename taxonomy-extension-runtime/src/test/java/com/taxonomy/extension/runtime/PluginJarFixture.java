package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.taxonomy.shared.extension.TaxonomyExtension;
import com.taxonomy.dto.ArchitectureReport;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;

/** Compiles a real isolated plugin against only the supported SDK and domain artifacts. */
final class PluginJarFixture {
    static final String SERVICE = "META-INF/services/" + TaxonomyPlugin.class.getName();
    static Path create(Path root, String fileName, String id, Map<String, String> overrides,
                       String body, Map<String, byte[]> extra) throws Exception {
        Files.createDirectories(root);
        String code = """
                package example.plugin;
                import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
                import com.taxonomy.extension.api.report.*;
                import com.taxonomy.shared.extension.TaxonomyExtension;
                import java.util.List;
                public final class ExamplePlugin implements TaxonomyPlugin {
                    public List<TaxonomyExtension> extensions() {
                        %s
                    }
                    public static final class Renderer implements ReportRendererExtension {
                        public String reportTypeId() { return "external"; }
                        public Class<?> reportModelType() { return String.class; }
                        public ReportFormatDescriptor descriptor() { return new ReportFormatDescriptor("txt", "External", "txt", "text/plain", false); }
                        public ReportRenderResult render(ReportRenderContext context) {
                            return new ReportRenderResult(context.payloadAs(String.class).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                """.formatted(body);
        return createSource(root, fileName, id, overrides, code, extra);
    }
    static Path createSource(Path root, String fileName, String id, Map<String, String> overrides,
                             String code, Map<String, byte[]> extra) throws Exception {
        Files.createDirectories(root);
        Path sources = Files.createTempDirectory(root.getParent(), "plugin-build-");
        Path source = sources.resolve("ExamplePlugin.java"); Files.writeString(source, code);
        String sdk = Path.of(TaxonomyPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String domain = Path.of(ArchitectureReport.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-classpath",
                sdk + java.io.File.pathSeparator + domain, "-d", sources.toString(), source.toString());
        if (result != 0) throw new IllegalStateException("Independent fixture plugin did not compile");
        Manifest manifest = new Manifest(); var attrs = manifest.getMainAttributes();
        attrs.putValue("Manifest-Version", "1.0"); attrs.putValue("Plugin-Id", id); attrs.putValue("Plugin-Version", "1.0.0");
        attrs.putValue("Plugin-Requires", ">=1.0.0 & <2.0.0"); attrs.putValue("Taxonomy-Plugin-Mode", "DYNAMIC");
        attrs.putValue("Taxonomy-Plugin-Capabilities", "render"); overrides.forEach(attrs::putValue);
        Path target = root.resolve(fileName);
        try (var output = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            try (var paths = Files.walk(sources)) {
                for (Path compiled : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    output.putNextEntry(new JarEntry(sources.relativize(compiled).toString().replace('\\', '/')));
                    Files.copy(compiled, output); output.closeEntry();
                }
            }
            Map<String, byte[]> resources = new TreeMap<>(extra);
            resources.putIfAbsent(SERVICE, "example.plugin.ExamplePlugin\n".getBytes(StandardCharsets.UTF_8));
            for (var entry : resources.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return target;
    }
    static Path create(Path root, String file, String id) throws Exception {
        return create(root, file, id, Map.of(), "return List.of(new Renderer());", Map.of());
    }
}
