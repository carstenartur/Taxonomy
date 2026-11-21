package com.taxonomy.extension.api.integration;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeFile;
import com.taxonomy.extension.api.plugin.ExtensionKey;
import com.taxonomy.shared.extension.ExtensionKind;

import java.util.List;
import java.util.Map;

/** Optional native delivery from the host's checked FFFmLV checkpoint, using a supplied QEA copy basis. */
public interface AdmbwQeaExport extends IntegrationConnectorExtension {
    ExtensionKey KEY = new ExtensionKey(ExtensionKind.INTEGRATION_CONNECTOR, "sparx-qea@2");
    String MEDIA_TYPE = "application/vnd.sparx.qea";
    int MAX_BASIS_BYTES = 32 * 1024 * 1024;

    ExchangeFile exportAdmbw(Delivery delivery, String rootPackageGuid, byte[] basis);

    /** Older plugins still support export without exposing a native reference inventory. */
    default boolean supportsBasisInspection() { return false; }
    default Basis inspectAdmbwBasis(byte[] basis) {
        throw new UnsupportedOperationException("Native basis inspection is unavailable");
    }

    record Basis(String sha256, String projectGuid, List<Root> roots, List<Reference> references) {
        public Basis { roots = List.copyOf(roots); references = List.copyOf(references); }
    }

    record Root(String guid, String name) { }

    record Reference(String guid, String stereotype, String name, String notes, List<String> packagePath,
                     String rootGuid, String ownerGuid) {
        public Reference { packagePath = List.copyOf(packagePath); }
    }

    /** Older plugins must not silently discard a host's delivery-scope disclosure. */
    default boolean supportsCoverageNotes() { return false; }

    /** Set when the writer preserves reviewed BWRequirement tagged values. */
    default boolean supportsAssessedRequirementTags() { return false; }

    record Delivery(String projectCode, String commit, List<View> views, String coverageNotes) {
        public Delivery { views = List.copyOf(views); }
        public Delivery(String projectCode, String commit, List<View> views) {
            this(projectCode, commit, views, null);
        }
    }

    record View(String viewpoint, String name, List<String> packagePath, List<Element> elements, List<Connector> connectors) {
        public View {
            packagePath = List.copyOf(packagePath);
            elements = List.copyOf(elements);
            connectors = List.copyOf(connectors);
        }
    }

    record Element(String ref, String stereotype, String name, String notes, String catalogueGuid,
                   Integer requirementVersion, String requirementContentHash, Map<String, String> assessedTags) {
        public Element { assessedTags = assessedTags == null ? Map.of() : Map.copyOf(assessedTags); }
        public Element(String ref, String stereotype, String name, String notes, String catalogueGuid,
                       Integer requirementVersion, String requirementContentHash) {
            this(ref, stereotype, name, notes, catalogueGuid, requirementVersion, requirementContentHash, Map.of());
        }
    }

    record Connector(String stereotype, String client, String supplier) { }
}
