package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.mapping.ExternalModelMapper;
import com.taxonomy.dsl.mapping.MappingProfile;
import com.taxonomy.dsl.mapping.MappingResult;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dto.FrameworkImportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Convenience base class for {@link ImportProfileExtension} implementations backed by a
 * {@link MappingProfile} and an {@link ExternalParser}.
 *
 * <p>Subclasses only need to supply:
 * <ul>
 *   <li>{@link #descriptor()} — profile metadata</li>
 *   <li>{@link #profile()} — the mapping profile instance</li>
 *   <li>{@link #parser()} — the file parser instance</li>
 * </ul>
 *
 * <p>The parse → map → serialize → materialize pipeline is provided here so
 * individual adapters stay free of boilerplate.
 */
public abstract class AbstractFrameworkImportProfileExtension implements ImportProfileExtension {

    private static final Logger log = LoggerFactory.getLogger(AbstractFrameworkImportProfileExtension.class);

    private final DslMaterializeService materializeService;

    protected AbstractFrameworkImportProfileExtension(DslMaterializeService materializeService) {
        this.materializeService = materializeService;
    }

    /** Returns the {@link MappingProfile} used by this adapter. */
    protected abstract MappingProfile profile();

    /** Returns the {@link ExternalParser} used by this adapter. */
    protected abstract ExternalParser parser();

    @Override
    public FrameworkImportResult preview(ImportInput input) {
        String profileId = descriptor().profileId();
        String displayName = descriptor().displayName();
        try {
            ExternalParser.ParsedExternalModel parsed = parser().parse(input.inputStream());
            ExternalModelMapper mapper = new ExternalModelMapper(profile());
            MappingResult mappingResult = mapper.map(parsed.elements(), parsed.relations());

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
        String branch = input.branch();
        try {
            // 1. Parse
            ExternalParser.ParsedExternalModel parsed = parser().parse(input.inputStream());

            // 2. Map to canonical model
            ExternalModelMapper mapper = new ExternalModelMapper(profile());
            MappingResult mappingResult = mapper.map(parsed.elements(), parsed.relations());

            // 3. Serialize to DSL text
            ModelToAstMapper astMapper = new ModelToAstMapper();
            TaxDslSerializer serializer = new TaxDslSerializer();
            var astDoc = astMapper.toDocument(mappingResult.model(), profileId + "-import");
            String dslText = serializer.serialize(astDoc);

            // 4. Materialize
            String path = "import/" + profileId + "-" + System.currentTimeMillis() + ".taxdsl";
            DslMaterializeService.MaterializeResult matResult =
                    materializeService.materialize(dslText, path, branch, null);

            List<String> allWarnings = new ArrayList<>(mappingResult.warnings());
            allWarnings.addAll(matResult.warnings());
            if (!matResult.valid()) {
                allWarnings.addAll(matResult.errors());
            }

            return new FrameworkImportResult(
                    profileId,
                    displayName,
                    matResult.valid(),
                    parsed.elements().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedElements", 0),
                    parsed.relations().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedRelations", 0),
                    matResult.relationsCreated(),
                    matResult.hypothesesCreated(),
                    matResult.documentId(),
                    allWarnings,
                    new ArrayList<>(mappingResult.unmappedTypes()),
                    mappingResult.mappingStatistics());
        } catch (Exception e) {
            log.error("Import failed for profile {}", profileId, e);
            return errorResult(profileId, displayName, "Import failed: " + e.getMessage());
        }
    }

    private static FrameworkImportResult errorResult(String profileId, String displayName, String message) {
        return new FrameworkImportResult(
                profileId, displayName, false,
                0, 0, 0, 0, 0, 0, null,
                List.of(message), List.of(), Map.of());
    }
}
