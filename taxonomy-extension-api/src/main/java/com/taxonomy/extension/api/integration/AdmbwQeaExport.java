package com.taxonomy.extension.api.integration;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeFile;
import com.taxonomy.extension.api.plugin.ExtensionKey;
import com.taxonomy.shared.extension.ExtensionKind;

import java.util.List;

/** Optional native delivery from the host's checked FFFmLV checkpoint, using a supplied QEA copy basis. */
public interface AdmbwQeaExport extends IntegrationConnectorExtension {
    ExtensionKey KEY = new ExtensionKey(ExtensionKind.INTEGRATION_CONNECTOR, "sparx-qea@2");
    String MEDIA_TYPE = "application/vnd.sparx.qea";
    int MAX_BASIS_BYTES = 32 * 1024 * 1024;

    ExchangeFile exportAdmbw(Delivery delivery, String rootPackageGuid, byte[] basis);

    record Delivery(String projectCode, String commit, List<View> views) {
        public Delivery { views = List.copyOf(views); }
    }

    record View(String viewpoint, String name, List<String> packagePath, List<Element> elements, List<Connector> connectors) {
        public View {
            packagePath = List.copyOf(packagePath);
            elements = List.copyOf(elements);
            connectors = List.copyOf(connectors);
        }
    }

    record Element(String ref, String stereotype, String name, String notes, String catalogueGuid,
                   Integer requirementVersion, String requirementContentHash) { }

    record Connector(String stereotype, String client, String supplier) { }
}
