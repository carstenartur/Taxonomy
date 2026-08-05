package com.taxonomy.architecture.workbench;

import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for framework-neutral architecture renderers. */
@Configuration(proxyBeanMethods = false)
public class ArchitectureWorkbenchConfiguration {

    @Bean
    LayeredDiagramLayoutService layeredDiagramLayoutService() {
        return new LayeredDiagramLayoutService();
    }

    @Bean
    SvgDiagramRenderer svgDiagramRenderer() {
        return new SvgDiagramRenderer();
    }
}
