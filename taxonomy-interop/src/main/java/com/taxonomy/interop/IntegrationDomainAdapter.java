package com.taxonomy.interop;

import com.taxonomy.interop.IntegrationPortfolioPort.*;
import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.exchange.OslcRdf;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.interop.sparx.SparxSnapshots;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceArchitectureReadPort.WorkspaceDocument;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Adapts reviewed canonical DTOs to existing portfolio services and typed DSL commands. */
@Service
public class IntegrationDomainAdapter {
    private final IntegrationPortfolioPort projects;
    private final IntegrationJson json;
    public IntegrationDomainAdapter(IntegrationPortfolioPort projects, IntegrationJson json) {
        this.projects = projects; this.json = json;
    }
    public record Snapshot(InternalState state, Map<String, Artifact> items, List<RequirementData> requirements, List<MappingLoss> losses, String projectKey) {
        public Snapshot(InternalState state, Map<String, Artifact> items, List<RequirementData> requirements, List<MappingLoss> losses) {
            this(state, items, requirements, losses, null);
        }
    }
    public record AppliedRequirement(String businessIdentity, Long requirementId) {}

    /** Bind only identities actually delivered in a reviewed file; this is not an external acknowledgement. */
    public Map<String, AppliedRequirement> exportBindings(Connection connection, Snapshot current,
                                                          WorkspaceDocument document, Map<String, Artifact> selected) {
        Map<String, AppliedRequirement> result = new TreeMap<>();
        if (connection.projectId() != null) {
            for (RequirementData requirement : current.requirements()) {
                String external = connection.connectorId().equals(OslcRdf.RM_PROFILE)
                        ? "urn:uuid:" + UUID.nameUUIDFromBytes((connection.id() + ":requirement:" + requirement.id()).getBytes(StandardCharsets.UTF_8))
                        : requirementExternalId(connection, requirement.id());
                result.put("REQUIREMENT:" + external, new AppliedRequirement(requirement.requirementKey(), requirement.id()));
            }
        }
        if (connection.projectId() == null || SparxSnapshots.isSparx(connection.connectorId())) for (BlockAst block : ArchitectureSemanticPatch.index(document.dsl()).values()) {
            String kind = block.getKind();
            if (!Set.of("element", "view", "relation").contains(kind) && !(nativePackages(connection) && "package".equals(kind))) continue;
            String business = kind.equals("relation") ? String.join(" ", block.getHeaderTokens()) : block.getHeaderTokens().getFirst();
            result.put(("package".equals(kind) ? "SPECIFICATION" : kind.toUpperCase(java.util.Locale.ROOT)) + ":" + externalId(connection, "export-" + kind + ":" + business), new AppliedRequirement(business, null));
        }
        if (nativePackages(connection)) for (Artifact a : selected.values()) if (a.kind() == ArtifactKind.RELATION
                && "REQUIREMENT_MAPPING".equals(a.extensions().get("nativeProjection")))
            result.put(ExchangeItems.key(a), new AppliedRequirement(a.extensions().get("nativeSource") + " -> " + a.extensions().get("nativeTarget"), null));
        for (var entry : selected.entrySet()) if (!Set.of(ArtifactKind.REQUIREMENT, ArtifactKind.ELEMENT, ArtifactKind.VIEW, ArtifactKind.RELATION).contains(entry.getValue().kind()))
            result.putIfAbsent(entry.getKey(), new AppliedRequirement(businessId(connection, null, entry.getValue()), null));
        return result;
    }
    public Snapshot snapshot(RepositoryContext context, Connection connection, List<Identity> mappings, WorkspaceDocument document) {
        List<RequirementData> requirements = connection.projectId() == null ? List.of()
                : projects.listRequirements(connection.projectId(), context.username(), workspace(context));
        Map<Long, RequirementData> byId = new LinkedHashMap<>(); requirements.forEach(r -> byId.put(r.id(), r));
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(document.dsl());
        Map<String, Artifact> items = new TreeMap<>();
        List<MappingLoss> losses = new ArrayList<>();
        for (Identity mapping : mappings) {
            if (mapping.removed()) continue;
            Artifact baseline = mapping.internal(); if (baseline == null) continue;
            Artifact current = baseline;
            if (connection.authority() == AuthorityMode.LINK_ONLY) {
                // A trace snapshot describes the external resource, independently of the linked object's display fields.
                items.put(mapping.externalId(), current); continue;
            }
            if (baseline.kind() == ArtifactKind.REQUIREMENT && mapping.requirementId() != null) {
                RequirementData requirement = byId.get(mapping.requirementId());
                if (requirement == null || requirement.archived()) continue;
                current = new Artifact(baseline.id(), baseline.kind(), baseline.type(), requirement.title(), requirement.requireCurrentVersion().text(), baseline.attributes(), baseline.extensions());
            } else if (baseline.kind() == ArtifactKind.ELEMENT || baseline.kind() == ArtifactKind.VIEW) {
                String kind = baseline.kind() == ArtifactKind.ELEMENT ? "element" : "view";
                BlockAst block = blocks.get(kind + ":" + mapping.businessIdentity()); if (block == null) continue;
                Map<String, String> extensions = new LinkedHashMap<>(baseline.extensions());
                String type = baseline.type();
                if (baseline.kind() == ArtifactKind.ELEMENT) {
                    String canonical = block.getHeaderTokens().get(2);
                    if (!canonical.equals(baseline.extensions().get("canonicalType"))) type = elementType(connection, canonical);
                    extensions.put("canonicalType", canonical);
                    if (extensions.containsKey("taxonomy:ElementType")) extensions.put("taxonomy:ElementType", canonical);
                    ArchitectureDslCommands.ELEMENT_PROPERTIES.stream().filter(p -> !Set.of("title", "description").contains(p))
                            .forEach(p -> extensions.remove("taxonomy:" + p));
                    for (var property : block.getProperties()) if (ArchitectureDslCommands.ELEMENT_PROPERTIES.contains(property.key())
                            && !Set.of("title", "description").contains(property.key())) extensions.put("taxonomy:" + property.key(), property.value());
                }
                Map<String, String> attributes = new TreeMap<>(baseline.attributes());
                if (SparxSnapshots.isSparx(connection.connectorId()) && baseline.kind() == ArtifactKind.ELEMENT
                        && !Objects.equals(extensions.get("canonicalType"), baseline.extensions().get("canonicalType")))
                    attributes.put("tag:taxonomy.elementType", block.getHeaderTokens().get(2));
                current = new Artifact(baseline.id(), baseline.kind(), type, value(block.property("title")), value(block.property("description")), attributes, extensions);
            } else if (baseline.kind() == ArtifactKind.RELATION && architectureProfile(connection)) {
                if ("PRESERVE_ONLY".equals(baseline.extensions().get("nativeProjection"))) { items.put(mapping.externalId(), current); continue; }
                if ("REQUIREMENT_MAPPING".equals(baseline.extensions().get("nativeProjection"))) {
                    if (blocks.containsKey("mapping:" + mapping.businessIdentity())) items.put(mapping.externalId(), current);
                    continue;
                }
                BlockAst relation = blocks.get("relation:" + mapping.businessIdentity()); if (relation == null) continue;
                Map<String, String> extensions = new LinkedHashMap<>(baseline.extensions());
                if (extensions.containsKey("taxonomy:RelationType")) extensions.put("taxonomy:RelationType", relation.getHeaderTokens().get(1));
                if (extensions.containsKey("taxonomy:status") || !"accepted".equals(relation.property("status")))
                    extensions.put("taxonomy:status", value(relation.property("status")));
                current = new Artifact(baseline.id(), baseline.kind(), baseline.type(), baseline.title(), baseline.text(), baseline.attributes(), extensions);
            }
            items.put(mapping.externalId(), current);
        }
        if (connection.connectorId().equals(ArchiMateExchangeCodec.PROFILE) && connection.authority() != AuthorityMode.LINK_ONLY) {
            Map<String, String> elements = new TreeMap<>(), views = new TreeMap<>();
            for (Identity mapping : mappings) if (!mapping.removed() && mapping.internal() != null) {
                if (mapping.internal().kind() == ArtifactKind.ELEMENT) elements.put(mapping.businessIdentity(), mapping.internal().id());
                if (mapping.internal().kind() == ArtifactKind.VIEW) views.put(mapping.businessIdentity(), mapping.internal().id());
            }
            synchronizeViews(connection, blocks, elements, views, items, losses);
            pruneConnections(items, losses);
        }
        if (nativePackages(connection) && connection.authority() != AuthorityMode.LINK_ONLY) {
            String modelId = items.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT)
                    .map(a -> a.extensions().get("container")).filter(Objects::nonNull).findFirst().orElse(null);
            IntegrationPackageProjection.synchronize(connection, document.dsl(), mappings, items, modelId, false);
            for (Artifact a : items.values()) if (a.kind() == ArtifactKind.PLACEMENT && items.containsKey("REQUIREMENT:" + a.extensions().get("artifact")))
                losses.add(new MappingLoss(a.id(), "package", "SPARX_REQUIREMENT_PACKAGE_PRESERVED_ONLY", LossDisposition.PRESERVED_EXTENSION,
                        "Requirement package membership remains exchange evidence"));
        }
        InternalState state = new InternalState(context.repositoryId(), document.state().workspaceScopeKey(), context.branch(), document.state().commitId(),
                document.state().semanticRevision(), connection.projectId(), json.fingerprint(requirements.stream().map(r -> java.util.Arrays.asList(r.id(), r.title(), r.status(), r.currentVersionId(), r.updatedAt())).toList()));
        return new Snapshot(state, Map.copyOf(items), requirements, List.copyOf(losses), nativePackages(connection) && connection.projectId() != null
                ? projects.getProject(connection.projectId(), context.username(), workspace(context)).projectKey() : null);
    }

    public void requireProject(RepositoryContext context, Long projectId) { projects.requireProject(projectId, context.username(), workspace(context)); }
    public void lockProject(RepositoryContext context, Long projectId) {
        if (projectId != null) projects.requireProjectForUpdate(projectId, context.username(), workspace(context));
    }

    public AppliedRequirement linkTarget(RepositoryContext context, Connection connection, String dsl, String identity) {
        if (identity == null || identity.length() > 300) throw new IllegalArgumentException("A bounded internal link target is required");
        if (identity.startsWith("requirement:") && connection.projectId() != null) {
            String key = identity.substring("requirement:".length());
            var requirement = projects.listRequirements(connection.projectId(), context.username(), workspace(context)).stream()
                    .filter(r -> r.requirementKey().equals(key) && !r.archived()).findFirst().orElseThrow(IntegrationProblem::missing);
            return new AppliedRequirement(identity, requirement.id());
        }
        if (identity.startsWith("element:") && ArchitectureSemanticPatch.index(dsl).containsKey(identity)) return new AppliedRequirement(identity, null);
        throw IntegrationProblem.missing();
    }

    /** Capture the complete selected project/model, overlaying current canonical values on accepted exchange evidence. */
    public ExchangeDocument exportDocument(Connection connection, Snapshot current, WorkspaceDocument document,
                                           List<Identity> mappings, ExchangeDocument previous) {
        ExchangeDocument template = previous == null ? new ExchangeDocument(connection.connectorId(), connection.profileVersion(), null, true, "",
                List.of(), List.of(), List.of(), Map.of("identifier", SparxSnapshots.isSparx(connection.connectorId())
                        ? externalId(connection, "model") : "taxonomy-" + connection.id(), "title", connection.displayName()), List.of()) : previous;
        Map<String, Artifact> items = new TreeMap<>(current.items());
        List<MappingLoss> losses = new ArrayList<>(template.losses()); losses.addAll(current.losses());
        if (connection.projectId() != null) {
            Map<Long, Identity> known = new LinkedHashMap<>(); mappings.stream().filter(m -> m.requirementId() != null).forEach(m -> known.put(m.requirementId(), m));
            List<Artifact> added = new ArrayList<>();
            for (RequirementData requirement : current.requirements()) if (!requirement.archived()) {
                Identity existing = known.get(requirement.id());
                if (existing != null && items.containsKey(existing.externalId())) continue;
                boolean oslc = connection.connectorId().equals(OslcRdf.RM_PROFILE);
                String id = existing != null ? existing.externalId().substring("REQUIREMENT:".length())
                        : oslc ? "urn:uuid:" + UUID.nameUUIDFromBytes((connection.id() + ":requirement:" + requirement.id()).getBytes(StandardCharsets.UTF_8))
                        : requirementExternalId(connection, requirement.id());
                Artifact artifact = new Artifact(id, ArtifactKind.REQUIREMENT, oslc ? OslcRdf.RM + "Requirement" : SparxSnapshots.isSparx(connection.connectorId()) ? "Class" : "taxonomy-object", requirement.title(),
                        requirement.requireCurrentVersion().text(), Map.of(), Map.of()); items.put(ExchangeItems.key(artifact), artifact); added.add(artifact);
            }
            if (connection.connectorId().equals(ReqifExchangeCodec.PROFILE) && previous != null && !added.isEmpty()) {
                // Local additions have their own stable specification; imported multi-level hierarchies stay intact.
                String specification = stableId(connection.id(), "export-local-requirements");
                Artifact group = new Artifact(specification, ArtifactKind.SPECIFICATION, "taxonomy-specification-type", "Taxonomy additions", "", Map.of(), Map.of());
                items.put(ExchangeItems.key(group), group); int position = 0;
                for (Artifact artifact : added) {
                    Artifact placement = new Artifact(stableId(connection.id(), "export-occurrence:" + artifact.id()), ArtifactKind.PLACEMENT, "placement", "", "", Map.of(),
                            Map.of("container", specification, "parent", "", "artifact", artifact.id(), "position", Integer.toString(position++)));
                    items.put(ExchangeItems.key(placement), placement);
                }
            }
        }
        if (connection.projectId() == null || SparxSnapshots.isSparx(connection.connectorId())) {
            Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(document.dsl());
            Map<String, String> ids = new TreeMap<>();
            mappings.stream().filter(m -> !m.removed()).forEach(m -> { if (m.internal() != null && m.internal().kind() == ArtifactKind.ELEMENT) ids.put(m.businessIdentity(), m.internal().id()); });
            for (BlockAst block : blocks.values()) if (block.getKind().equals("element")) {
                String id = block.getHeaderTokens().getFirst(), canonical = block.getHeaderTokens().get(2);
                if (ids.containsKey(id)) continue;
                String external = externalId(connection, "export-element:" + id); ids.put(id, external);
                Map<String, String> extensions = new TreeMap<>(); extensions.put("canonicalType", canonical);
                for (var property : block.getProperties()) if (ArchitectureDslCommands.ELEMENT_PROPERTIES.contains(property.key()) && !Set.of("title", "description").contains(property.key()))
                    extensions.put("taxonomy:" + property.key(), property.value());
                Map<String, String> attributes = SparxSnapshots.isSparx(connection.connectorId()) ? Map.of("tag:taxonomy.elementType", canonical) : Map.of();
                Artifact artifact = new Artifact(external, ArtifactKind.ELEMENT, elementType(connection, canonical), value(block.property("title")), value(block.property("description")), attributes, extensions);
                items.put(ExchangeItems.key(artifact), artifact);
            }
            Set<String> known = mappings.stream().filter(m -> !m.removed()).map(Identity::businessIdentity).collect(java.util.stream.Collectors.toSet());
            for (BlockAst block : blocks.values()) if (block.getKind().equals("relation")) {
                String key = String.join(" ", block.getHeaderTokens()); if (known.contains(key)) continue;
                String source = ids.get(block.getHeaderTokens().get(0)), target = ids.get(block.getHeaderTokens().get(2)), type = block.getHeaderTokens().get(1);
                if (source == null || target == null) throw new IntegrationProblem("RELATION_MAPPING_REQUIRED", 422, "Canonical relation has an unmapped endpoint");
                Map<String, String> extension = new TreeMap<>(Map.of("source", source, "target", target, "canonicalType", type, "taxonomy:status", value(block.property("status"))));
                if (type.equals("PRODUCES")) extension.put("accessType", "Write");
                String mappedType = SparxSnapshots.isSparx(connection.connectorId()) ? SparxMappingProfile.eaRelation(type) : archimateRelation(type);
                String externalId = externalId(connection, "export-relation:" + key);
                if (SparxSnapshots.isSparx(connection.connectorId())) extension.put("direction", type.equals("RELATED_TO") ? "Unspecified" : "Source -> Destination");
                if (mappedType == null) losses.add(new MappingLoss(externalId, "type", "UNSUPPORTED_RELATION_TYPE", LossDisposition.UNSUPPORTED,
                        "Canonical " + type + " has no declared exchange mapping; reject this relation or explicitly select an export mapping"));
                Map<String, String> attributes = SparxSnapshots.isSparx(connection.connectorId()) ? Map.of("tag:taxonomy.relationType", type) : Map.of();
                Artifact artifact = new Artifact(externalId, ArtifactKind.RELATION, mappedType == null ? type : mappedType, "", "", attributes, extension);
                items.put(ExchangeItems.key(artifact), artifact);
            }
            if (nativePackages(connection) && connection.projectId() != null) {
                Map<String, String> requirementIds = new HashMap<>();
                for (RequirementData requirement : current.requirements()) {
                    Identity mapping = mappings.stream().filter(m -> requirement.id().equals(m.requirementId()) && !m.removed()).findFirst().orElse(null);
                    String external = mapping == null ? requirementExternalId(connection, requirement.id()) : mapping.internal().id();
                    List<BlockAst> candidates = blocks.values().stream().filter(b -> b.getKind().equals("requirement")
                            && Objects.equals(current.projectKey(), b.property("x-project-key"))
                            && requirement.requirementKey().equals(b.property("x-requirement-key"))).toList();
                    if (candidates.size() == 1) requirementIds.put(candidates.getFirst().getHeaderTokens().getFirst(), external);
                }
                for (BlockAst block : blocks.values()) if (block.getKind().equals("mapping") && block.property("x-exchange-id") != null) {
                    String key = String.join(" ", block.getHeaderTokens()); if (known.contains(key)) continue;
                    String source = requirementIds.get(block.getHeaderTokens().get(0)), target = ids.get(block.getHeaderTokens().get(2));
                    if (source == null || target == null) continue;
                    Artifact relation = new Artifact(externalId(connection, "export-mapping:" + key), ArtifactKind.RELATION, "Dependency", "", "", Map.of(),
                            Map.of("source", source, "target", target, "canonicalType", "DEPENDS_ON", "direction", "Source -> Destination",
                                    "nativeProjection", "REQUIREMENT_MAPPING", "nativeSource", block.getHeaderTokens().get(0), "nativeTarget", block.getHeaderTokens().get(2)));
                    items.put(ExchangeItems.key(relation), relation);
                }
            }
            for (BlockAst block : blocks.values()) if (block.getKind().equals("view") && !known.contains(block.getHeaderTokens().getFirst())) {
                if (SparxSnapshots.isSparx(connection.connectorId())) {
                    losses.add(new MappingLoss(block.getHeaderTokens().getFirst(), "view", "SPARX_LAYOUT_EXCLUDED", LossDisposition.UNSUPPORTED,
                            "Taxonomy views are outside the Sparx semantic profile and are not exported"));
                    continue;
                }
                String viewId = stableId(connection.id(), "export-view:" + block.getHeaderTokens().getFirst());
                Artifact view = new Artifact(viewId, ArtifactKind.VIEW, "Diagram", value(block.property("title")), value(block.property("description")), Map.of(), Map.of());
                items.put(ExchangeItems.key(view), view); int position = 0;
                for (var property : block.getProperties()) if (property.key().equals("include") && ids.containsKey(property.value())) {
                    String elementId = ids.get(property.value());
                    Artifact placement = new Artifact(stableId(connection.id(), viewId + ":" + elementId), ArtifactKind.PLACEMENT, "placement", "", "",
                            Map.of("x", Integer.toString(position % 5 * 180), "y", Integer.toString(position / 5 * 100), "w", "160", "h", "70"),
                            Map.of("container", viewId, "parent", "", "artifact", elementId, "position", Integer.toString(position++)));
                    items.put(ExchangeItems.key(placement), placement);
                }
            }
            Map<String, String> views = new TreeMap<>();
            mappings.stream().filter(m -> !m.removed() && m.internal() != null && m.internal().kind() == ArtifactKind.VIEW)
                    .forEach(m -> views.put(m.businessIdentity(), m.internal().id()));
            synchronizeViews(connection, blocks, ids, views, items, losses);
        }
        // Local removals have an explicit export loss report; never leave an orphaned hierarchy or view connection.
        if (nativePackages(connection)) IntegrationPackageProjection.synchronize(connection, document.dsl(), mappings, items,
                template.metadata().get("identifier"), true);
        boolean removed;
        do {
            Set<String> present = items.values().stream().filter(a -> a.kind() != ArtifactKind.PLACEMENT && a.kind() != ArtifactKind.METADATA).map(Artifact::id).collect(java.util.stream.Collectors.toSet());
            Set<String> placements = items.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT).map(Artifact::id).collect(java.util.stream.Collectors.toSet());
            removed = items.entrySet().removeIf(e -> {
                Artifact item = e.getValue(); Map<String, String> extension = item.extensions();
                boolean omit = item.kind() == ArtifactKind.RELATION && (!present.contains(extension.get("source")) || !present.contains(extension.get("target")))
                        || item.kind() == ArtifactKind.PLACEMENT && (!value(extension.get("artifact")).isEmpty() && !present.contains(extension.get("artifact"))
                        || !value(extension.get("parent")).isEmpty() && !placements.contains(extension.get("parent"))
                        || !"organizations".equals(extension.get("container")) && !present.contains(extension.get("container"))
                        && !(SparxSnapshots.isSparx(connection.connectorId()) && template.metadata().get("identifier").equals(extension.get("container"))));
                if (omit) losses.add(new MappingLoss(item.id(), "dependency", "LOCAL_DEPENDENCY_REMOVED", LossDisposition.TRANSFORMED,
                        "Occurrence or relation is omitted because its canonical target was removed locally"));
                return omit;
            });
        } while (removed);
        pruneConnections(items, losses);
        ExchangeDocument result = ExchangeItems.expand(template, items);
        if (SparxSnapshots.isSparx(connection.connectorId())) {
            // A native addition has a stable root occurrence; imported parent/occurrence identity is retained.
            List<Placement> occurrences = new ArrayList<>(result.placements());
            Set<String> placed = occurrences.stream().map(Placement::artifactId).collect(java.util.stream.Collectors.toSet());
            int position = occurrences.stream().filter(p -> p.parentId() == null).mapToInt(Placement::position).max().orElse(-1) + 1;
            for (Artifact artifact : result.artifacts()) if (!placed.contains(artifact.id()))
                occurrences.add(new Placement("placement:" + artifact.id(), template.metadata().get("identifier"), null, artifact.id(), position++, Map.of()));
            result = new ExchangeDocument(result.profile(), result.profileVersion(), result.externalVersion(), result.completeScope(), result.source(),
                    result.artifacts(), result.relations(), occurrences, result.metadata(), result.losses());
        }
        result = new ExchangeDocument(result.profile(), result.profileVersion(), result.externalVersion(), result.completeScope(), result.source(), result.artifacts(),
                result.relations(), result.placements(), result.metadata(), losses);
        // Freeze the identities even when unsupported features defer serialization until review.
        return SparxSnapshots.isSparx(connection.connectorId()) ? SparxSnapshots.identify(result, connection.id()) : result;
    }

    private static void synchronizeViews(Connection connection, Map<String, BlockAst> blocks, Map<String, String> elements,
                                          Map<String, String> views, Map<String, Artifact> items, List<MappingLoss> losses) {
        for (var view : views.entrySet()) {
            BlockAst block = blocks.get("view:" + view.getKey()); if (block == null) continue;
            Set<String> included = block.propertyValues("include").stream().map(elements::get).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            Set<String> managed = Set.copyOf(elements.values());
            items.entrySet().removeIf(e -> e.getValue().kind() == ArtifactKind.PLACEMENT && view.getValue().equals(e.getValue().extensions().get("container"))
                    && managed.contains(e.getValue().extensions().get("artifact")) && !included.contains(e.getValue().extensions().get("artifact")));
            // A removed semantic parent must not leave a dangling placement parent. Preserve surviving children explicitly at the root.
            Set<String> remaining = items.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT).map(Artifact::id).collect(java.util.stream.Collectors.toSet());
            items.replaceAll((id, item) -> {
                if (item.kind() != ArtifactKind.PLACEMENT || !view.getValue().equals(item.extensions().get("container"))
                        || value(item.extensions().get("parent")).isEmpty() || remaining.contains(item.extensions().get("parent"))) return item;
                Map<String, String> extension = new TreeMap<>(item.extensions()); extension.put("parent", "");
                losses.add(new MappingLoss(item.id(), "parent", "VIEW_OCCURRENCE_REPARENTED", LossDisposition.TRANSFORMED,
                        "The removed parent places this surviving occurrence at the view root; retained coordinates require layout review"));
                return new Artifact(item.id(), item.kind(), item.type(), item.title(), item.text(), item.attributes(), extension);
            });
            Set<String> represented = items.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT && view.getValue().equals(a.extensions().get("container")))
                    .map(a -> a.extensions().get("artifact")).collect(java.util.stream.Collectors.toSet());
            int position = represented.size();
            for (String element : included.stream().sorted().toList()) if (!represented.contains(element)) {
                Artifact placement = new Artifact(stableId(connection.id(), view.getValue() + ":" + element), ArtifactKind.PLACEMENT, "placement", "", "",
                        Map.of("x", Integer.toString(position % 5 * 180), "y", Integer.toString(position / 5 * 100), "w", "160", "h", "70"),
                        Map.of("container", view.getValue(), "parent", "", "artifact", element, "position", Integer.toString(position++)));
                items.put(ExchangeItems.key(placement), placement);
            }
        }
    }

    /** Only export removes stale presentation connections, with a visible loss for each removed reference. */
    private static void pruneConnections(Map<String, Artifact> items, List<MappingLoss> losses) {
        Set<String> relations = items.values().stream().filter(a -> a.kind() == ArtifactKind.RELATION).map(Artifact::id).collect(java.util.stream.Collectors.toSet());
        items.replaceAll((id, item) -> {
            String evidence = item.extensions().get("connectionsXml");
            if (item.kind() != ArtifactKind.VIEW || evidence == null) return item;
            var xml = com.taxonomy.exchange.ExchangeXml.parse(evidence.getBytes(StandardCharsets.UTF_8));
            Set<String> targets = items.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT && item.id().equals(a.extensions().get("container")))
                    .map(Artifact::id).collect(java.util.stream.Collectors.toSet());
            com.taxonomy.exchange.ExchangeXml.children(xml.getDocumentElement()).forEach(c -> targets.add(c.getAttribute("identifier")));
            boolean removed;
            do {
                removed = false;
                for (var c : com.taxonomy.exchange.ExchangeXml.children(xml.getDocumentElement())) if (!targets.contains(c.getAttribute("source")) || !targets.contains(c.getAttribute("target"))
                        || c.hasAttribute("relationshipRef") && !relations.contains(c.getAttribute("relationshipRef"))) {
                    targets.remove(c.getAttribute("identifier")); xml.getDocumentElement().removeChild(c); removed = true;
                    losses.add(new MappingLoss(c.getAttribute("identifier"), "connection", "LOCAL_DEPENDENCY_REMOVED", LossDisposition.TRANSFORMED,
                            "View connection is omitted because its canonical relation or placement was removed locally"));
                }
            } while (removed);
            Map<String, String> extensions = new TreeMap<>(item.extensions()); extensions.put("connectionsXml", com.taxonomy.exchange.ExchangeXml.xml(xml));
            return new Artifact(item.id(), item.kind(), item.type(), item.title(), item.text(), item.attributes(), extensions);
        });
    }

    public AppliedRequirement applyRequirement(RepositoryContext context, Connection connection, Artifact value, Identity previous, String rationale) {
        if (connection.projectId() == null)
            throw new IntegrationProblem("REQUIREMENT_PROJECT_REQUIRED", 422, "Select a project for reviewed requirement changes or reject the requirements");
        if (value == null) {
            if (previous != null && previous.requirementId() != null) projects.archiveRequirement(connection.projectId(), previous.requirementId(), context.username(), workspace(context));
            return new AppliedRequirement(previous.businessIdentity(), previous.requirementId());
        }
        if (value.title().isBlank() || value.title().length() > 240 || value.text().isBlank() || value.text().length() > 100000)
            throw new IntegrationProblem("REQUIREMENT_MAPPING_REQUIRED", 422, "Requirement title/text is outside the documented portfolio profile; reject or remap this object");
        ImportProvenance provenance = new ImportProvenance("integration:" + connection.id() + ":" + stableId(connection.id(), value.id()), value.text());
        if (previous == null || previous.requirementId() == null) {
            String key = "EXT-" + stableId(connection.id(), value.id()).substring(4).toUpperCase(java.util.Locale.ROOT);
            RequirementData created = projects.createRequirement(connection.projectId(), new ImportedRequirement(key, value.title(), value.text(), rationale, provenance),
                    context.username(), workspace(context));
            return new AppliedRequirement(created.requirementKey(), created.id());
        }
        RequirementData before = projects.getRequirement(connection.projectId(), previous.requirementId(), context.username(), workspace(context));
        boolean textChanged = !before.requireCurrentVersion().text().equals(value.text());
        boolean requiresReview = textChanged || before.archived();
        if (!before.title().equals(value.title()) || requiresReview)
            projects.updateRequirement(connection.projectId(), previous.requirementId(), value.title(), requiresReview, context.username(), workspace(context));
        if (textChanged) projects.addRequirementVersion(connection.projectId(), previous.requirementId(), value.text(), rationale, provenance, context.username(), workspace(context));
        return new AppliedRequirement(previous.businessIdentity(), previous.requirementId());
    }

    public enum EndpointKind { ARCHITECTURE_ELEMENT, REQUIREMENT, PACKAGE }
    public record EndpointRef(EndpointKind kind, String businessIdentity, Long requirementId) {}
    public record EndpointIndex(Map<String, EndpointRef> external) {
        public EndpointIndex { external = Map.copyOf(external); }
    }
    public Map<String, RequirementApplyPlan> planRequirements(RepositoryContext context, Connection connection,
            Map<String, Artifact> selected, List<Identity> mappings, String dsl) {
        Map<String, Identity> known = new TreeMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
        Map<String, RequirementApplyPlan> plans = new LinkedHashMap<>();
        Map<String, RequirementApplyPlan> canonical = new LinkedHashMap<>();
        for (Artifact artifact : selected.values()) if (artifact.kind() == ArtifactKind.REQUIREMENT) {
            if (connection.projectId() == null) throw new IntegrationProblem("REQUIREMENT_PROJECT_REQUIRED", 422, "Select a project for reviewed requirements");
            if (artifact.title().isBlank() || artifact.title().length() > 240 || artifact.text().isBlank() || artifact.text().length() > 100000)
                throw new IntegrationProblem("REQUIREMENT_MAPPING_REQUIRED", 422, "Requirement title/text is outside the portfolio profile");
            Identity previous = known.get(ExchangeItems.key(artifact));
            String key = previous != null && previous.requirementId() != null
                    ? projects.getRequirement(connection.projectId(), previous.requirementId(), context.username(), workspace(context)).requirementKey()
                    : "EXT-" + stableId(connection.id(), artifact.id()).substring(4).toUpperCase(java.util.Locale.ROOT);
            RequirementApplyPlan plan = projects.planRequirementApply(connection.projectId(), key, dsl, context.username(), workspace(context));
            RequirementApplyPlan collision = canonical.putIfAbsent(plan.canonicalIdentity(), plan);
            if (collision != null && !collision.equals(plan)) throw new IntegrationProblem("REQUIREMENT_IDENTITY_MISMATCH", 409, "Planned requirement identities collide");
            plans.put(ExchangeItems.key(artifact), plan);
        }
        return Map.copyOf(plans);
    }
    public EndpointIndex indexEndpoints(Connection connection, Map<String, Artifact> selected, List<Identity> mappings,
                                         Map<String, RequirementApplyPlan> plans) {
        Map<String, Identity> known = new TreeMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
        Map<String, EndpointRef> endpoints = new LinkedHashMap<>();
        for (Artifact a : selected.values()) {
            Identity prior = known.get(ExchangeItems.key(a));
            if (a.kind() == ArtifactKind.ELEMENT || a.kind() == ArtifactKind.SPECIFICATION)
                endpoints.put(a.id(), new EndpointRef(a.kind() == ArtifactKind.ELEMENT ? EndpointKind.ARCHITECTURE_ELEMENT : EndpointKind.PACKAGE,
                        businessId(connection, prior, a), null));
            else if (a.kind() == ArtifactKind.REQUIREMENT) {
                RequirementApplyPlan plan = plans.get(ExchangeItems.key(a));
                if (plan != null) endpoints.put(a.id(), new EndpointRef(EndpointKind.REQUIREMENT, plan.canonicalIdentity(), prior == null ? null : prior.requirementId()));
            }
        }
        return new EndpointIndex(endpoints);
    }
    /** Pure validation of the full planned state, including real/planned requirement identities. */
    public void validateCompletePlan(String dsl, List<ArchitectureCommand> commands, Map<String, RequirementApplyPlan> plans) {
        String preview = dsl;
        ArchitectureDslCommands transformer = new ArchitectureDslCommands();
        for (ArchitectureCommand command : commands) if (!(command instanceof UpsertRequirementMapping)) preview = transformer.apply(preview, command).dsl();
        Set<String> planned = plans.values().stream().map(RequirementApplyPlan::canonicalIdentity).collect(java.util.stream.Collectors.toSet());
        for (ArchitectureCommand command : commands) if (command instanceof UpsertRequirementMapping mapping) {
            try { transformer.validatePlannedRequirementMapping(preview, mapping, planned); }
            catch (ArchitectureDslCommands.CommandProblem problem) {
                throw new IntegrationProblem(problem.code(), 422, problem.getMessage());
            }
        }
    }

    public List<ArchitectureCommand> architectureCommands(Connection connection, String dsl, Map<String, Artifact> current,
                                                         Map<String, Artifact> selected, List<Identity> mappings) {
        return architectureCommands(connection, dsl, current, selected, mappings,
                indexEndpoints(connection, selected, mappings, Map.of()), Map.of());
    }

    public List<ArchitectureCommand> architectureCommands(Connection connection, String dsl, Map<String, Artifact> current,
            Map<String, Artifact> selected, List<Identity> mappings, EndpointIndex endpoints, Map<String, EndpointOverride> overrides) {
        return architectureCommands(connection, dsl, current, selected, mappings, endpoints, overrides, "Reviewed external connector");
    }
    public List<ArchitectureCommand> architectureCommands(Connection connection, String dsl, Map<String, Artifact> current,
            Map<String, Artifact> selected, List<Identity> mappings, EndpointIndex endpoints, Map<String, EndpointOverride> overrides, String rationale) {
        Map<String, Identity> known = new TreeMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
        Map<String, String> elementIds = new LinkedHashMap<>();
        selected.values().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).forEach(a -> elementIds.put(a.id(), businessId(connection, known.get(ExchangeItems.key(a)), a)));
        List<ArchitectureCommand> commands = new ArrayList<>(); Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(dsl);
        for (var entry : current.entrySet()) if (entry.getValue().kind() == ArtifactKind.VIEW && !selected.containsKey(entry.getKey())) {
            Identity prior = known.get(entry.getKey());
            if (prior != null && blocks.containsKey("view:" + prior.businessIdentity())) commands.add(new DeleteArchitectureView(prior.businessIdentity()));
        }
        Map<String, RelationTarget> relationTargets = new LinkedHashMap<>();
        for (Artifact relation : selected.values()) if (relation.kind() == ArtifactKind.RELATION)
            relationTargets.put(ExchangeItems.key(relation), relationTarget(relation, endpoints, overrides.get(ExchangeItems.key(relation))));
        // Remove replaced relations first; dependency checks remain server-authoritative.
        for (var entry : current.entrySet()) if (entry.getValue().kind() == ArtifactKind.RELATION) {
            Artifact next = selected.get(entry.getKey()); Identity prior = known.get(entry.getKey());
            if (prior != null && blocks.containsKey("relation:" + prior.businessIdentity())
                    && (next == null || !prior.businessIdentity().equals(relationTargets.get(entry.getKey()).businessIdentity())))
                commands.add(new DeleteArchitectureRelation(parseRelation(prior.businessIdentity())));
            if (prior != null && blocks.containsKey("mapping:" + prior.businessIdentity())
                    && (next == null || !prior.businessIdentity().equals(relationTargets.get(entry.getKey()).businessIdentity()))) {
                String[] pair = prior.businessIdentity().split(" -> ", 2);
                commands.add(new DeleteRequirementMapping(pair[0], pair[1]));
            }
        }
        for (Artifact artifact : selected.values()) if (artifact.kind() == ArtifactKind.ELEMENT) {
            if (ExchangeItems.fields(artifact).equals(ExchangeItems.fields(current.get(ExchangeItems.key(artifact))))) continue;
            String internalId = elementIds.get(artifact.id()); String type = artifact.extensions().get("canonicalType");
            if (type == null) throw new IntegrationProblem("UNSUPPORTED_ELEMENT_TYPE", 422, "Reject or explicitly remap the unsupported element type");
            Map<String, String> properties = new TreeMap<>(Map.of("title", artifact.title(), "description", artifact.text()));
            ArchitectureDslCommands.ELEMENT_PROPERTIES.stream().filter(p -> !Set.of("title", "description").contains(p))
                    .forEach(p -> { if (artifact.extensions().containsKey("taxonomy:" + p)) properties.put(p, artifact.extensions().get("taxonomy:" + p)); });
            commands.add(blocks.containsKey("element:" + internalId) ? new UpdateArchitectureElement(internalId, type, properties) : new CreateArchitectureElement(internalId, type, properties));
            BlockAst priorBlock = blocks.get("element:" + internalId);
            if (priorBlock != null) {
                Set<String> cleared = priorBlock.getProperties().stream().map(com.taxonomy.dsl.ast.PropertyAst::key)
                        .filter(p -> ArchitectureDslCommands.ELEMENT_PROPERTIES.contains(p) && !properties.containsKey(p)).collect(java.util.stream.Collectors.toSet());
                if (!cleared.isEmpty()) commands.add(new ClearArchitectureElementProperties(internalId, cleared));
            }
            commands.add(new SetExchangeProperties("element", internalId, exchangeProperties(connection, artifact)));
        }
        if (nativePackages(connection)) commands.addAll(IntegrationPackageProjection.commands(connection, dsl, current, selected, mappings, this));
        for (Artifact artifact : selected.values()) if (artifact.kind() == ArtifactKind.VIEW) {
            boolean layoutChanged = !current.entrySet().stream().filter(e -> e.getValue().kind() == ArtifactKind.PLACEMENT && artifact.id().equals(e.getValue().extensions().get("container")))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> ExchangeItems.fields(e.getValue())))
                    .equals(selected.entrySet().stream().filter(e -> e.getValue().kind() == ArtifactKind.PLACEMENT && artifact.id().equals(e.getValue().extensions().get("container")))
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> ExchangeItems.fields(e.getValue()))));
            if (!layoutChanged && ExchangeItems.fields(artifact).equals(ExchangeItems.fields(current.get(ExchangeItems.key(artifact))))) continue;
            Set<String> members = selected.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT && artifact.id().equals(a.extensions().get("container")))
                    .map(a -> elementIds.get(a.extensions().get("artifact"))).filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            String viewId = businessId(connection, known.get(ExchangeItems.key(artifact)), artifact);
            BlockAst existingView = blocks.get("view:" + viewId);
            Set<String> managed = mappings.stream().filter(m -> m.internal() != null && m.internal().kind() == ArtifactKind.ELEMENT).map(Identity::businessIdentity).collect(java.util.stream.Collectors.toSet());
            if (existingView != null) existingView.getProperties().stream().filter(p -> p.key().equals("include") && !managed.contains(p.value())).forEach(p -> members.add(p.value()));
            commands.add(new UpsertArchitectureView(viewId, artifact.title(), artifact.text(), List.copyOf(members), exchangeProperties(connection, artifact)));
        }
        for (var entry : current.entrySet()) if (entry.getValue().kind() == ArtifactKind.ELEMENT && !selected.containsKey(entry.getKey())) {
            Identity prior = known.get(entry.getKey()); if (prior != null && blocks.containsKey("element:" + prior.businessIdentity())) commands.add(new DeleteArchitectureElement(prior.businessIdentity()));
        }
        for (Artifact relation : selected.values()) if (relation.kind() == ArtifactKind.RELATION) {
            if (ExchangeItems.fields(relation).equals(ExchangeItems.fields(current.get(ExchangeItems.key(relation))))) continue;
            RelationTarget target = relationTargets.get(ExchangeItems.key(relation));
            if (target.projection() == RelationProjection.PRESERVE_ONLY) continue;
            if (target.projection() == RelationProjection.REQUIREMENT_MAPPING) {
                commands.add(new UpsertRequirementMapping(target.source(), target.target(), rationale, exchangeProperties(connection, relation)));
                continue;
            }
            RelationKey key = new RelationKey(target.source(), target.type(), target.target());
            String status = relation.extensions().getOrDefault("taxonomy:status", "accepted");
            if (!blocks.containsKey("relation:" + key.id())) commands.add(new CreateArchitectureRelation(key, status));
            else if (!status.equals(blocks.get("relation:" + key.id()).property("status"))) commands.add(new UpdateArchitectureRelation(key, status));
            commands.add(new SetExchangeProperties("relation", key.id(), exchangeProperties(connection, relation)));
        }
        if (commands.size() > 2000) throw new IntegrationProblem("APPLY_COMMAND_LIMIT", 422, "Review at most 1000 semantic objects per import; the preview can contain a larger scope");
        return List.copyOf(commands);
    }

    public static boolean nativePackages(Connection connection) {
        return SparxSnapshots.isSparx(connection.connectorId()) && "2".equals(connection.profileVersion());
    }
    public record RelationTarget(RelationProjection projection, String source, String target, String type) {
        public String businessIdentity() {
            return projection == RelationProjection.PRESERVE_ONLY ? "preserved" : source + (projection == RelationProjection.REQUIREMENT_MAPPING ? " -> " : " " + type + " ") + target;
        }
    }
    private static RelationTarget relationTarget(Artifact relation, EndpointIndex index, EndpointOverride override) {
        // Retained approval authorizes projection/ends; canonicalType is the effective relation type.
        // Historical nativeType evidence must not override a later reviewed canonical remap.
        if (override == null && relation.extensions().containsKey("nativeProjection")) override = new EndpointOverride(
                relation.extensions().get("nativeSource"), relation.extensions().get("nativeTarget"),
                RelationProjection.valueOf(relation.extensions().get("nativeProjection")), null);
        if (override != null && override.projection() == RelationProjection.PRESERVE_ONLY)
            return new RelationTarget(RelationProjection.PRESERVE_ONLY, null, null, null);
        if ("Bi-Directional".equals(relation.extensions().get("direction")))
            throw new IntegrationProblem("SPARX_DIRECTION_UNMAPPED", 422, "Bidirectional connector requires preserve-only review");
        EndpointRef source = index.external().get(relation.extensions().get("source"));
        EndpointRef target = index.external().get(relation.extensions().get("target"));
        if ("Destination -> Source".equals(relation.extensions().get("direction"))) { EndpointRef swap = source; source = target; target = swap; }
        if (override != null) {
            if (override.projection() == null) throw new IntegrationProblem("SPARX_ENDPOINT_MAPPING_REQUIRED", 422, "Choose an endpoint projection");
            EndpointRef reviewedSource = nativeEndpoint(index, override.sourceInternalIdentity());
            EndpointRef reviewedTarget = nativeEndpoint(index, override.targetInternalIdentity());
            if (source == null || target == null || reviewedSource == null || reviewedTarget == null
                    || source.kind() != reviewedSource.kind() || target.kind() != reviewedTarget.kind()
                    || !source.businessIdentity().equals(reviewedSource.businessIdentity()) || !target.businessIdentity().equals(reviewedTarget.businessIdentity()))
                throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422,
                        "Reviewed identities must match the exact normalized connector ends; endpoint retargeting is unsupported");
            source = reviewedSource; target = reviewedTarget;
        }
        if (source == null || target == null) throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422, "Connector endpoint is outside the accepted scope");
        RelationProjection projection = override == null ? RelationProjection.ARCHITECTURE_RELATION : override.projection();
        if (source.kind() == EndpointKind.PACKAGE || target.kind() == EndpointKind.PACKAGE || source.kind() == EndpointKind.REQUIREMENT && target.kind() == EndpointKind.REQUIREMENT)
            throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422, "Package and requirement-pair connectors require preserve-only review");
        if (projection == RelationProjection.REQUIREMENT_MAPPING) {
            if (source.kind() != EndpointKind.REQUIREMENT || target.kind() != EndpointKind.ARCHITECTURE_ELEMENT)
                throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422, "Requirement mapping direction is requirement to element");
            return new RelationTarget(projection, source.businessIdentity(), target.businessIdentity(), null);
        }
        if (source.kind() != EndpointKind.ARCHITECTURE_ELEMENT || target.kind() != EndpointKind.ARCHITECTURE_ELEMENT)
            throw new IntegrationProblem("SPARX_ENDPOINT_MAPPING_REQUIRED", 422, "Requirement connectors need an explicit requirement mapping projection");
        String type = override != null && override.canonicalType() != null ? override.canonicalType() : relation.extensions().get("canonicalType");
        if (type == null) throw new IntegrationProblem("RELATION_MAPPING_REQUIRED", 422, "Select a native relation type");
        return new RelationTarget(projection, source.businessIdentity(), target.businessIdentity(), type);
    }
    private static EndpointRef nativeEndpoint(EndpointIndex index, String identity) {
        List<EndpointRef> matches = index.external().values().stream().filter(e -> e.businessIdentity().equals(identity)).distinct().toList();
        if (matches.size() > 1) throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422, "Native endpoint identity is ambiguous");
        return matches.isEmpty() ? null : matches.getFirst();
    }
    public String relationBusinessId(Artifact relation, EndpointIndex index, EndpointOverride override) {
        RelationTarget target = relationTarget(relation, index, override);
        return target.projection() == RelationProjection.PRESERVE_ONLY ? "preserved:" + relation.id() : target.businessIdentity();
    }

    public String businessId(Connection connection, Identity existing, Artifact artifact) {
        if (existing != null) return existing.businessIdentity();
        return stableId(connection.id(), artifact.kind() + ":" + artifact.id());
    }
    public String relationBusinessId(Artifact relation, Connection connection, Map<String, Artifact> selected, List<Identity> mappings) {
        Map<String, Identity> known = new LinkedHashMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
        Map<String, String> ids = new LinkedHashMap<>(); selected.values().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT)
                .forEach(a -> ids.put(a.id(), businessId(connection, known.get(ExchangeItems.key(a)), a)));
        return relationKey(relation, ids).id();
    }
    public UnaryOperator<String> portfolioContribution(RepositoryContext context) {
        return source -> ArchitectureSemanticPatch.applyProjection(source, projects.contributeTo(source, context.username(), workspace(context)));
    }
    private Map<String, String> exchangeProperties(Connection connection, Artifact artifact) {
        return Map.of("x-exchange-connection", connection.id().toString(), "x-exchange-id", artifact.id(), "x-exchange-profile", connection.connectorId(),
                "x-exchange-artifact", json.write(artifact));
    }
    private static RelationKey relationKey(Artifact relation, Map<String, String> elements) {
        String source = elements.get(relation.extensions().get("source")), target = elements.get(relation.extensions().get("target"));
        String type = relation.extensions().get("canonicalType");
        if (source == null || target == null || type == null) throw new IntegrationProblem("RELATION_MAPPING_REQUIRED", 422, "Relation endpoints and type must belong to the accepted supported model");
        String direction = relation.extensions().get("direction");
        if ("Bi-Directional".equals(direction))
            throw new IntegrationProblem("SPARX_DIRECTION_UNMAPPED", 422, "Reject the bidirectional connector; this profile cannot represent both directions as one native relation");
        if ("Destination -> Source".equals(direction)) return new RelationKey(target, type, source);
        return new RelationKey(source, type, target);
    }
    private static RelationKey parseRelation(String value) { String[] tokens = value.split(" ", 3); return new RelationKey(tokens[0], tokens[1], tokens[2]); }
    public static String stableId(UUID connection, String id) { return "ext-" + UUID.nameUUIDFromBytes((connection + "\u0000" + id).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static String externalId(Connection connection, String identity) {
        return SparxSnapshots.isSparx(connection.connectorId()) ? SparxMappingProfile.externalId(connection.id(), identity) : stableId(connection.id(), identity);
    }
    private static String requirementExternalId(Connection connection, Long identity) {
        return SparxSnapshots.isSparx(connection.connectorId()) ? externalId(connection, "export-requirement:" + identity) : "taxonomy-requirement-" + identity;
    }
    private static String elementType(Connection connection, String canonical) {
        return SparxSnapshots.isSparx(connection.connectorId()) ? SparxMappingProfile.umlType(canonical) : archimateType(canonical);
    }
    public static boolean architectureProfile(Connection connection) {
        return connection.connectorId().equals(ArchiMateExchangeCodec.PROFILE) || SparxSnapshots.isSparx(connection.connectorId());
    }
    public static WorkspaceContext workspace(RepositoryContext context) { return new WorkspaceContext(context.username(), context.workspaceId(), context.branch(), context.repositoryId()); }
    private static String value(String value) { return value == null ? "" : value; }
    public static String archimateType(String canonical) {
        return switch (canonical) {
            case "Capability" -> "Capability"; case "Process" -> "BusinessProcess"; case "BusinessRole" -> "BusinessRole";
            case "CoreService" -> "ApplicationService"; case "COIService" -> "BusinessService"; case "CommunicationsService" -> "CommunicationNetwork";
            case "InformationProduct" -> "DataObject"; case "UserApplication", "System", "Component" -> "ApplicationComponent";
            default -> throw new IntegrationProblem("UNSUPPORTED_ELEMENT_TYPE", 422, "Canonical element type has no exchange mapping");
        };
    }
    public static String archimateRelation(String canonical) {
        return switch (canonical) {
            case "REALIZES" -> "Realization"; case "SUPPORTS" -> "Serving"; case "ASSIGNED_TO" -> "Assignment";
            case "CONSUMES", "PRODUCES" -> "Access"; case "COMMUNICATES_WITH" -> "Flow"; case "CONTAINS" -> "Composition";
            case "RELATED_TO" -> "Association";
            default -> null;
        };
    }
}
