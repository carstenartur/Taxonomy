package com.taxonomy.export.service;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioExportMetadata;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Core export registration must not require the optional portfolio/architecture features. */
class VisioMetadataAssemblyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coreVisioAdapterUsesRealBuildMetadataWithoutPortfolioOrArchitectureServices(boolean packagedBuild) throws Exception {
        var converter = mock(VisioDiagramService.class);
        var builder = mock(VisioPackageBuilder.class);
        when(converter.convert(any(), any())).thenReturn(new VisioDocument());
        when(builder.build(any())).thenReturn(new byte[]{1});
        var runner = new ApplicationContextRunner()
                .withPropertyValues("app.display-version=core-fixture-build")
                .withBean(VisioDiagramService.class, () -> converter)
                .withBean(VisioPackageBuilder.class, () -> builder)
                .withUserConfiguration(VisioWorkingViewMetadata.class, VisioExportExtension.class);
        if (packagedBuild) {
            var properties = new Properties();
            properties.setProperty("version", "packaged-build-version");
            runner = runner.withBean(BuildProperties.class, () -> new BuildProperties(properties));
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(VisioExportExtension.class);
            var adapter = context.getBean(VisioExportExtension.class);
            adapter.export(new ExportContext(new DiagramModel("Graph", List.of(), List.of(), null), Map.of()));
            var metadata = ArgumentCaptor.forClass(VisioExportMetadata.class);
            verify(converter).convert(any(), metadata.capture());
            assertThat(metadata.getValue().document().get("taxonomy.generatorVersion").value())
                    .isEqualTo(packagedBuild ? "packaged-build-version" : "core-fixture-build");
            assertThat(metadata.getValue().document()).doesNotContainKey("taxonomy.requirementUrl");
        });
    }
}
