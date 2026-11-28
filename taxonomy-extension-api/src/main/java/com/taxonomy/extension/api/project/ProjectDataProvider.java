package com.taxonomy.extension.api.project;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;
import java.util.List;
import java.util.UUID;

/** A startup plugin supplies versioned organization data and selects a locally retained catalogue. */
public interface ProjectDataProvider extends TaxonomyExtension {
    @Override default ExtensionKind kind() { return ExtensionKind.PROJECT_DATA_PROVIDER; }

    Dataset dataset();

    /** A null catalogue revision uses the installation's current catalogue. */
    record Dataset(String sourceRevision, UUID catalogueRevision, List<Unit> organizations) {
        public Dataset {
            if (sourceRevision == null || sourceRevision.isBlank() || sourceRevision.length() > 256)
                throw new IllegalArgumentException("A bounded dataset source revision is required");
            if (organizations.size() > 10_000) throw new IllegalArgumentException("Too many project organizations");
            organizations = List.copyOf(organizations);
        }
    }

    /** Native source identifiers are opaque and case-sensitive. */
    record Unit(String sourceId, String title, String parentSourceId) {}
}
