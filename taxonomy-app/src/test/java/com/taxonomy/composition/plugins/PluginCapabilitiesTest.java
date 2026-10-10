package com.taxonomy.composition.plugins;

import com.taxonomy.shared.features.FeatureAssembly;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.export.spi.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PluginCapabilitiesTest {
    @Test void unavailableApplicationServicesDoNotAdvertiseDeadFormats() {
        var catalog=new PluginCatalog();var plugin=new PluginDescriptor(new PluginIdentity("example.export","1.0.0","a".repeat(64)),">=1.0.0 & <2.0.0",List.of(),Set.of(),PluginMode.DYNAMIC);
        catalog.publish(plugin,List.of(new ExportFormatExtension(){
            public ExportFormatDescriptor descriptor(){return new ExportFormatDescriptor("sample","Example","txt","text/plain",false);}
            public ExportResult export(ExportContext context){throw new AssertionError("Discovery must never execute a format");}
        }));
        assertThat(new PluginCapabilitiesController(catalog,new FeatureAssembly.FeatureSet(Set.of())).capabilities().exports()).isEmpty();
        var controller=new PluginCapabilitiesController(catalog,new FeatureAssembly.FeatureSet(Set.of("analysis","architecture")));
        var before=controller.capabilities();
        assertThat(before.exports()).singleElement().satisfies(format->assertThat(format.plugin()).isEqualTo(plugin.identity()));
        catalog.beginDraining(plugin.identity());
        var after=controller.capabilities();
        assertThat(after.revision()).isGreaterThan(before.revision());assertThat(after.exports()).isEmpty();
        assertThat(before.exports()).hasSize(1);
    }
}
