package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.mapping.ExternalModelMapper;
import com.taxonomy.dsl.mapping.MappingProfile;
import com.taxonomy.dsl.mapping.MappingResult;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.extension.api.importer.ImportInput;
import com.taxonomy.extension.api.importer.ImportProfileExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared parse-map-serialize-materialize implementation for framework profiles. */
public abstract class AbstractFrameworkImportProfileExtension implements ImportProfileExtension {

    private static final Logger log =
            LoggerFactory.getLogger(AbstractFrameworkImportProfileExtension.class);

    private final DslMaterializeService materializeService;

    protected AbstractFrameworkImportProfileExtension(DslMaterializeService materializeService) {
        this.materializeService = materializeService;
    }

    protected abstract MappingProfile profile();

    protected abstract ExternalParser parser();

    @Override
    public FrameworkImportResult preview(ImportInput input) {
        String profileId = descriptor().profileId();
        String displayName = descriptor().displayName();
        try {
            ExternalParser.ParsedExternalModel parsed = parser().parse(input.inputStream());
            MappingResult mappingResult =
                    new ExternalModelMapper(profile()).map(parsed.elements(), parsed.relations());
            return new FrameworkImportResult(
                    profileId,
                    displayName,
                    true,
                    parsed.elements().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedElements", 0),
                    parsed.relations().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedRelations", 0),
                    0, 0, null,
                    mappingResult.warnings(),
                    new ArrayList<>(mappingResult.unmappedTypes()),
                    mappingResult.mappingStatistics());
        } catch (Exception e) {
            log.error("Preview failed for profile {}", profileId, e);
            return errorResult(profileId, displayName, "Preview failed: " + e.getMessage());
        }
    }

    @Override
    public FrameworkImportResult importData(ImportInput input) {
        String profileId = descriptor().profileId();
        String displayName = descriptor().displayName();
        try {
            ExternalParser.ParsedExternalModel parsed = parser().parse(input.inputStream());
            MappingResult mappingResult =
                    new ExternalModelMapper(profile()).map(parsed.elements(), parsed.relations());
            String dslText = new TaxDslSerializer().serialize(
                    new ModelToAstMapper().toDocument(mappingResult.model(), profileId + "-import"));
            String path = "import/" + profileId + "-" + System.currentTimeMillis() + ".taxdsl";
            DslMaterializeService.MaterializeResult materialized =
                    materializeService.materialize(dslText, path, input.branch(), null);

            List<String> warnings = new ArrayList<>(mappingResult.warnings());
            warnings.addAll(materialized.warnings());
            if (!materialized.valid()) {
                warnings.addAll(materialized.errors());
            }
            return new FrameworkImportResult(
                    profileId,
                    displayName,
                    materialized.valid(),
                    parsed.elements().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedElements", 0),
                    parsed.relations().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedRelations", 0),
                    materialized.relationsCreated(),
                    materialized.hypothesesCreated(),
                    materialized.documentId(),
                    warnings,
                    new ArrayList<>(mappingResult.unmappedTypes()),
                    mappingResult.mappingStatistics());
        } catch (Exception e) {
            log.error("Import failed for profile {}", profileId, e);
            return errorResult(profileId, displayName, "Import failed: " + e.getMessage());
        }
    }

    private static FrameworkImportResult errorResult(
            String profileId, String displayName, String message) {
        return new FrameworkImportResult(
                profileId, displayName, false,
                0, 0, 0, 0, 0, 0, null,
                List.of(message), List.of(), Map.of());
    }
}
