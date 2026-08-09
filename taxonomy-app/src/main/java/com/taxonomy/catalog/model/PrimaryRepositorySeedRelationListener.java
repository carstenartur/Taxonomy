package com.taxonomy.catalog.model;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import jakarta.persistence.PrePersist;
import org.springframework.stereotype.Component;

/**
 * Transitional persistence boundary for the built-in Excel/CSV relation seed loader.
 *
 * <p>The historic catalog loader constructs committed relations directly because the
 * taxonomy nodes and their relations are imported in one batched startup transaction.
 * Those built-in seeds unambiguously belong to the primary central repository. No other
 * caller may omit {@code repositoryId}; interactive, workspace and integration writes
 * fail closed in {@link TaxonomyRelation#synchronizeTenantKeys()}.</p>
 */
@Component
public class PrimaryRepositorySeedRelationListener {

    private static final String EXCEL_PROVENANCE = "excel";
    private static final String CSV_PROVENANCE_PREFIX = "csv-";

    private final SystemRepositoryService repositoryService;

    public PrimaryRepositorySeedRelationListener(SystemRepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @PrePersist
    public void bindBuiltInSeedToPrimaryRepository(TaxonomyRelation relation) {
        if (relation.getRepositoryId() != null
                && !relation.getRepositoryId().isBlank()) {
            return;
        }
        if (!isBuiltInSeed(relation.getProvenance())) {
            throw new IllegalStateException(
                    "TaxonomyRelation repositoryId is required outside the built-in catalog seed loader");
        }
        SystemRepository primary = repositoryService.getPrimaryRepository();
        relation.setRepositoryId(primary.getRepositoryId());
    }

    private static boolean isBuiltInSeed(String provenance) {
        return EXCEL_PROVENANCE.equals(provenance)
                || (provenance != null && provenance.startsWith(CSV_PROVENANCE_PREFIX));
    }
}
