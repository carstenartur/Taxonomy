package com.taxonomy.dsl.export;

import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.DocumentAst;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.model.ArchitectureOrganization;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.extension.api.plugin.ExtensionKey;
import com.taxonomy.extension.api.project.ProjectDataProvider;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.workspace.service.ProjectCatalogueSeed;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import com.taxonomy.identity.StableIdentityHash;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** A dataset is copied into one new Git source. Native IDs are retained across catalogue editions. */
@Component
public final class ProjectDataSeed {
    private final ExtensionCatalog extensions;

    public ProjectDataSeed(@Lazy ExtensionCatalog extensions) { this.extensions = extensions; }

    public List<ProjectCatalogueSeed.Choice> choices() {
        return extensions.snapshot().extensions().stream()
                .filter(entry -> entry.key().kind() == ExtensionKind.PROJECT_DATA_PROVIDER)
                .map(entry -> new ProjectCatalogueSeed.Choice(entry.plugin().id() + "/" + entry.key().id(),
                        entry.descriptor().displayName())).toList();
    }

    public String initial(String namespace, String provider, UUID currentCatalogue, Consumer<String> verifyCatalogue) {
        String[] parts = ProjectCatalogueSeed.requireProvider(provider).split("/", 2);
        try (var lease = extensions.acquire(new ExtensionKey(ExtensionKind.PROJECT_DATA_PROVIDER, parts[1]), ProjectDataProvider.class)) {
            if (!lease.plugin().id().equals(parts[0])) throw new IllegalArgumentException("Project data provider ownership changed");
            var dataset = lease.extension().dataset();
            UUID catalogue = dataset.catalogueRevision() == null ? currentCatalogue : dataset.catalogueRevision();
            if (catalogue != null && !catalogue.equals(currentCatalogue)) verifyCatalogue.accept(catalogue.toString());
            var model = new CanonicalArchitectureModel();
            model.setCatalogueRevision(catalogue == null ? null : catalogue.toString());
            for (var unit : dataset.organizations()) {
                String id = identity(provider, unit.sourceId());
                var provenance = Map.of("x-source-id", unit.sourceId(), "x-source-provider", provider,
                        "x-source-revision", dataset.sourceRevision(), "x-source-artifact", lease.plugin().artifactSha256());
                model.getOrganizations().add(new ArchitectureOrganization(id, unit.title(),
                        unit.parentSourceId() == null ? null : identity(provider, unit.parentSourceId()), provenance));
            }
            List<String> errors = new ArrayList<>();
            ArchitectureOrganization.validate(model, (code, message) -> errors.add(message));
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
            var document = new ModelToAstMapper().toDocument(model, namespace);
            String revision = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    dataset.sourceRevision().getBytes(StandardCharsets.UTF_8));
            return "# Project data: " + provider + " " + lease.plugin().artifactSha256() + " " + revision
                    + "\n" + new TaxDslSerializer().serialize(document);
        } catch (com.taxonomy.extension.api.plugin.ExtensionUnavailableException absent) {
            throw new IllegalArgumentException("Project data provider is unavailable: " + provider, absent);
        }
    }

    /** Add newly observed native units. Existing local titles, hierarchy and responsibility assignments are retained. */
    public Plan planImport(String source, String provider) {
        var parser = new TaxDslParser();
        var meta = parser.parse(source).getMeta();
        String revision = meta == null ? null : meta.catalogueRevision();
        UUID pinned = revision == null ? null : UUID.fromString(revision);
        String dataset = initial("project-data", provider, pinned, requested -> {
            throw new IllegalArgumentException("The dataset requires a different project catalogue revision");
        });
        var current = ArchitectureSemanticPatch.index(source);
        for (var block : current.values()) {
            if ("organization".equals(block.getKind()) && block.propertyValues("x-source-provider").contains(provider)) {
                var ids = block.propertyValues("x-source-id");
                if (ids.size() != 1 || !block.propertyValues("x-source-provider").equals(List.of(provider))
                        || !block.getHeaderTokens().equals(List.of(identity(provider, ids.getFirst()))))
                    throw new IllegalArgumentException("Original organization identity conflicts with the project source");
            }
        }
        var add = new ArrayList<BlockAst>();
        var units = parser.parse(dataset).blocksOfKind("organization");
        for (var incoming : units) {
            var existing = current.get(ArchitectureSemanticPatch.key(incoming));
            if (existing == null) add.add(incoming);
            else if (!existing.propertyValues("x-source-id").equals(incoming.propertyValues("x-source-id"))
                    || !existing.propertyValues("x-source-provider").equals(List.of(provider))) {
                throw new IllegalArgumentException("Organization identity conflicts with an existing project element");
            }
        }
        String suffix = new TaxDslSerializer().serialize(new DocumentAst(null, add));
        String after = add.isEmpty() ? source : source + "\n" + suffix;
        return new Plan(after, StableIdentityHash.sha256(dataset), add.size(), units.size() - add.size());
    }

    public record Plan(String source, String datasetHash, int added, int existing) {}

    private static String identity(String provider, String nativeId) {
        if (nativeId == null || nativeId.isBlank() || nativeId.length() > 512
                || nativeId.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
            throw new IllegalArgumentException("A bounded original organization source ID is required");
        return "org-" + StableIdentityHash.sha256(provider + "\n" + nativeId);
    }
}
