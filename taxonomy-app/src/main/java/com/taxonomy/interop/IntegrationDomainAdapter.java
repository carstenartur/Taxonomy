package com.taxonomy.interop;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.interop.persistence.IntegrationStore.Identity;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioGitService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final ProjectPortfolioService projects;
    private final PortfolioGitService portfolio;
    private final IntegrationJson json;
    public IntegrationDomainAdapter(ProjectPortfolioService projects, PortfolioGitService portfolio, IntegrationJson json) {
        this.projects = projects; this.portfolio = portfolio; this.json = json;
    }
    public record Snapshot(InternalState state, Map<String, Artifact> items, List<RequirementView> requirements) {}
    public record AppliedRequirement(String businessIdentity, Long requirementId) {}
    public Snapshot snapshot(RepositoryContext context, Connection connection, List<Identity> mappings, ArchitectureEditorService.Document document) {
        List<RequirementView> requirements = connection.projectId() == null ? List.of()
                : projects.listRequirements(connection.projectId(), context.username(), workspace(context));
        Map<Long, RequirementView> byId = new LinkedHashMap<>(); requirements.forEach(r -> byId.put(r.id(), r));
        Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(document.dsl());
        Map<String, Artifact> items = new TreeMap<>();
        for (Identity mapping : mappings) {
            if (mapping.removed()) continue;
            Artifact baseline = mapping.internal(); if (baseline == null) continue;
            Artifact current = baseline;
            if (baseline.kind() == ArtifactKind.REQUIREMENT && mapping.requirementId() != null) {
                RequirementView requirement = byId.get(mapping.requirementId());
                if (requirement == null || requirement.status() == RequirementStatus.ARCHIVED) continue;
                current = new Artifact(baseline.id(), baseline.kind(), baseline.type(), requirement.title(), requirement.currentVersion().text(), baseline.attributes(), baseline.extensions());
            } else if (baseline.kind() == ArtifactKind.ELEMENT || baseline.kind() == ArtifactKind.VIEW) {
                String kind = baseline.kind() == ArtifactKind.ELEMENT ? "element" : "view";
                BlockAst block = blocks.get(kind + ":" + mapping.businessIdentity()); if (block == null) continue;
                Map<String, String> extensions = new LinkedHashMap<>(baseline.extensions());
                String type = baseline.type();
                if (baseline.kind() == ArtifactKind.ELEMENT) {
                    String canonical = block.getHeaderTokens().get(2);
                    if (!canonical.equals(baseline.extensions().get("canonicalType"))) type = archimateType(canonical);
                    extensions.put("canonicalType", canonical); extensions.put("taxonomy:ElementType", canonical);
                    for (var property : block.getProperties()) if (ArchitectureDslCommands.ELEMENT_PROPERTIES.contains(property.key())
                            && !Set.of("title", "description").contains(property.key())) extensions.put("taxonomy:" + property.key(), property.value());
                }
                current = new Artifact(baseline.id(), baseline.kind(), type, value(block.property("title")), value(block.property("description")), baseline.attributes(), extensions);
            } else if (baseline.kind() == ArtifactKind.RELATION && connection.connectorId().equals(ArchiMateExchangeCodec.PROFILE)) {
                BlockAst relation = blocks.get("relation:" + mapping.businessIdentity()); if (relation == null) continue;
                Map<String, String> extensions = new LinkedHashMap<>(baseline.extensions());
                extensions.put("taxonomy:RelationType", relation.getHeaderTokens().get(1));
                extensions.put("taxonomy:status", value(relation.property("status")));
                current = new Artifact(baseline.id(), baseline.kind(), baseline.type(), baseline.title(), baseline.text(), baseline.attributes(), extensions);
            }
            items.put(mapping.externalId(), current);
        }
        InternalState state = new InternalState(context.repositoryId(), document.context().workspaceScopeKey(), context.branch(), document.context().commit(),
                document.context().revision(), connection.projectId(), json.fingerprint(requirements.stream().map(r -> List.of(r.id(), r.title(), r.status(), r.currentVersionId(), r.updatedAt())).toList()));
        return new Snapshot(state, Map.copyOf(items), requirements);
    }

    public void requireProject(RepositoryContext context, Long projectId) { projects.requireProject(projectId, context.username(), workspace(context)); }
    public void lockProject(RepositoryContext context, Long projectId) {
        if (projectId != null) projects.requireProjectForUpdate(projectId, context.username(), workspace(context));
    }

    /** Capture the complete selected project/model, overlaying current canonical values on accepted exchange evidence. */
    public ExchangeDocument exportDocument(Connection connection, Snapshot current, ArchitectureEditorService.Document document,
                                           List<Identity> mappings, ExchangeDocument previous) {
        ExchangeDocument template = previous == null ? new ExchangeDocument(connection.connectorId(), connection.profileVersion(), null, true, "",
                List.of(), List.of(), List.of(), Map.of("identifier", "taxonomy-" + connection.id(), "title", connection.displayName()), List.of()) : previous;
        Map<String, Artifact> items = new TreeMap<>(current.items());
        if (connection.projectId() != null) {
            Set<Long> known = mappings.stream().filter(m -> m.requirementId() != null).map(Identity::requirementId).collect(java.util.stream.Collectors.toSet());
            for (RequirementView requirement : current.requirements()) if (!known.contains(requirement.id()) && requirement.status() != RequirementStatus.ARCHIVED) {
                Artifact artifact = new Artifact("taxonomy-requirement-" + requirement.id(), ArtifactKind.REQUIREMENT, "taxonomy-object", requirement.title(),
                        requirement.currentVersion().text(), Map.of(), Map.of()); items.put(ExchangeItems.key(artifact), artifact);
            }
        } else {
            Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(document.dsl());
            Map<String, String> ids = new TreeMap<>();
            mappings.stream().filter(m -> !m.removed()).forEach(m -> { if (m.internal() != null && m.internal().kind() == ArtifactKind.ELEMENT) ids.put(m.businessIdentity(), m.internal().id()); });
            for (BlockAst block : blocks.values()) if (block.getKind().equals("element")) {
                String id = block.getHeaderTokens().getFirst(), canonical = block.getHeaderTokens().get(2);
                if (ids.containsKey(id)) continue;
                String external = stableId(connection.id(), "export-element:" + id); ids.put(id, external);
                Map<String, String> extensions = new TreeMap<>(); extensions.put("canonicalType", canonical);
                for (var property : block.getProperties()) if (ArchitectureDslCommands.ELEMENT_PROPERTIES.contains(property.key()) && !Set.of("title", "description").contains(property.key()))
                    extensions.put("taxonomy:" + property.key(), property.value());
                Artifact artifact = new Artifact(external, ArtifactKind.ELEMENT, archimateType(canonical), value(block.property("title")), value(block.property("description")), Map.of(), extensions);
                items.put(ExchangeItems.key(artifact), artifact);
            }
            Set<String> known = mappings.stream().filter(m -> !m.removed()).map(Identity::businessIdentity).collect(java.util.stream.Collectors.toSet());
            for (BlockAst block : blocks.values()) if (block.getKind().equals("relation")) {
                String key = String.join(" ", block.getHeaderTokens()); if (known.contains(key)) continue;
                String source = ids.get(block.getHeaderTokens().get(0)), target = ids.get(block.getHeaderTokens().get(2)), type = block.getHeaderTokens().get(1);
                if (source == null || target == null) throw new IntegrationProblem("RELATION_MAPPING_REQUIRED", 422, "Canonical relation has an unmapped endpoint");
                Map<String, String> extension = new TreeMap<>(Map.of("source", source, "target", target, "canonicalType", type, "taxonomy:status", value(block.property("status"))));
                if (type.equals("PRODUCES")) extension.put("accessType", "Write");
                Artifact artifact = new Artifact(stableId(connection.id(), "export-relation:" + key), ArtifactKind.RELATION, archimateRelation(type), "", "", Map.of(), extension);
                items.put(ExchangeItems.key(artifact), artifact);
            }
            for (BlockAst block : blocks.values()) if (block.getKind().equals("view") && !known.contains(block.getHeaderTokens().getFirst())) {
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
        }
        // Accepted requirements may have been archived locally; their occurrence/relation evidence is no longer part of this snapshot.
        Set<String> present = items.values().stream().filter(a -> a.kind() != ArtifactKind.PLACEMENT && a.kind() != ArtifactKind.METADATA).map(Artifact::id).collect(java.util.stream.Collectors.toSet());
        items.entrySet().removeIf(e -> e.getValue().kind() == ArtifactKind.RELATION && (!present.contains(e.getValue().extensions().get("source")) || !present.contains(e.getValue().extensions().get("target"))));
        items.entrySet().removeIf(e -> e.getValue().kind() == ArtifactKind.PLACEMENT && !e.getValue().extensions().get("artifact").isEmpty() && !present.contains(e.getValue().extensions().get("artifact")));
        return ExchangeItems.expand(template, items);
    }

    public AppliedRequirement applyRequirement(RepositoryContext context, Connection connection, Artifact value, Identity previous, String rationale) {
        if (value == null) {
            if (previous != null && previous.requirementId() != null) projects.updateRequirement(connection.projectId(), previous.requirementId(),
                    new UpdateRequirementRequest(null, RequirementStatus.ARCHIVED, null, null, null, null, null), context.username(), workspace(context));
            return new AppliedRequirement(previous.businessIdentity(), previous.requirementId());
        }
        if (value.title().isBlank() || value.title().length() > 240 || value.text().isBlank() || value.text().length() > 100000)
            throw new IntegrationProblem("REQUIREMENT_MAPPING_REQUIRED", 422, "Requirement title/text is outside the documented portfolio profile; reject or remap this object");
        SourceReference provenance = new SourceReference(null, null, List.of(), "integration:" + connection.id() + ":" + stableId(connection.id(), value.id()), null, value.text());
        if (previous == null || previous.requirementId() == null) {
            String key = "EXT-" + stableId(connection.id(), value.id()).substring(4).toUpperCase(java.util.Locale.ROOT);
            RequirementView created = projects.createRequirement(connection.projectId(), new CreateRequirementRequest(key, value.title(), value.text(),
                    RequirementStatus.DRAFT, 50, Criticality.MEDIUM, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, context.username(), rationale, provenance),
                    context.username(), workspace(context));
            return new AppliedRequirement(created.requirementKey(), created.id());
        }
        RequirementView before = projects.getRequirement(connection.projectId(), previous.requirementId(), context.username(), workspace(context));
        boolean textChanged = !before.currentVersion().text().equals(value.text());
        if (!before.title().equals(value.title()) || textChanged)
            projects.updateRequirement(connection.projectId(), previous.requirementId(), new UpdateRequirementRequest(value.title(), textChanged ? RequirementStatus.DRAFT : null,
                    null, null, null, textChanged ? ReviewStatus.PROPOSED : null, null), context.username(), workspace(context));
        if (textChanged) projects.addRequirementVersion(connection.projectId(), previous.requirementId(), new CreateRequirementVersionRequest(value.text(), rationale, provenance), context.username(), workspace(context));
        return new AppliedRequirement(previous.businessIdentity(), previous.requirementId());
    }

    public List<ArchitectureCommand> architectureCommands(Connection connection, String dsl, Map<String, Artifact> current,
                                                         Map<String, Artifact> selected, List<Identity> mappings) {
        Map<String, Identity> known = new TreeMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
        Map<String, String> elementIds = new LinkedHashMap<>();
        selected.values().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).forEach(a -> elementIds.put(a.id(), businessId(connection, known.get(ExchangeItems.key(a)), a)));
        List<ArchitectureCommand> commands = new ArrayList<>(); Map<String, BlockAst> blocks = ArchitectureSemanticPatch.index(dsl);
        for (var entry : current.entrySet()) if (entry.getValue().kind() == ArtifactKind.VIEW && !selected.containsKey(entry.getKey())) {
            Identity prior = known.get(entry.getKey());
            if (prior != null && blocks.containsKey("view:" + prior.businessIdentity())) commands.add(new DeleteArchitectureView(prior.businessIdentity()));
        }
        // Remove replaced relations first; dependency checks remain server-authoritative.
        for (var entry : current.entrySet()) if (entry.getValue().kind() == ArtifactKind.RELATION) {
            Artifact next = selected.get(entry.getKey()); Identity prior = known.get(entry.getKey());
            if (prior != null && blocks.containsKey("relation:" + prior.businessIdentity())
                    && (next == null || !prior.businessIdentity().equals(relationKey(next, elementIds).id())))
                commands.add(new DeleteArchitectureRelation(parseRelation(prior.businessIdentity())));
        }
        for (Artifact artifact : selected.values()) if (artifact.kind() == ArtifactKind.ELEMENT) {
            if (ExchangeItems.fields(artifact).equals(ExchangeItems.fields(current.get(ExchangeItems.key(artifact))))) continue;
            String internalId = elementIds.get(artifact.id()); String type = artifact.extensions().get("canonicalType");
            if (type == null) throw new IntegrationProblem("UNSUPPORTED_ELEMENT_TYPE", 422, "Reject or explicitly remap the unsupported element type");
            Map<String, String> properties = Map.of("title", artifact.title(), "description", artifact.text());
            commands.add(blocks.containsKey("element:" + internalId) ? new UpdateArchitectureElement(internalId, type, properties) : new CreateArchitectureElement(internalId, type, properties));
            commands.add(new SetExchangeProperties("element", internalId, exchangeProperties(connection, artifact)));
        }
        for (Artifact artifact : selected.values()) if (artifact.kind() == ArtifactKind.VIEW) {
            boolean layoutChanged = !current.entrySet().stream().filter(e -> e.getValue().kind() == ArtifactKind.PLACEMENT && artifact.id().equals(e.getValue().extensions().get("container")))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> ExchangeItems.fields(e.getValue())))
                    .equals(selected.entrySet().stream().filter(e -> e.getValue().kind() == ArtifactKind.PLACEMENT && artifact.id().equals(e.getValue().extensions().get("container")))
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> ExchangeItems.fields(e.getValue()))));
            if (!layoutChanged && ExchangeItems.fields(artifact).equals(ExchangeItems.fields(current.get(ExchangeItems.key(artifact))))) continue;
            List<String> members = selected.values().stream().filter(a -> a.kind() == ArtifactKind.PLACEMENT && artifact.id().equals(a.extensions().get("container")))
                    .map(a -> elementIds.get(a.extensions().get("artifact"))).filter(Objects::nonNull).distinct().toList();
            commands.add(new UpsertArchitectureView(businessId(connection, known.get(ExchangeItems.key(artifact)), artifact), artifact.title(), members, exchangeProperties(connection, artifact)));
        }
        for (var entry : current.entrySet()) if (entry.getValue().kind() == ArtifactKind.ELEMENT && !selected.containsKey(entry.getKey())) {
            Identity prior = known.get(entry.getKey()); if (prior != null && blocks.containsKey("element:" + prior.businessIdentity())) commands.add(new DeleteArchitectureElement(prior.businessIdentity()));
        }
        for (Artifact relation : selected.values()) if (relation.kind() == ArtifactKind.RELATION) {
            if (ExchangeItems.fields(relation).equals(ExchangeItems.fields(current.get(ExchangeItems.key(relation))))) continue;
            RelationKey key = relationKey(relation, elementIds);
            if (!blocks.containsKey("relation:" + key.id())) commands.add(new CreateArchitectureRelation(key, "accepted"));
            commands.add(new SetExchangeProperties("relation", key.id(), exchangeProperties(connection, relation)));
        }
        if (commands.size() > 2000) throw new IntegrationProblem("APPLY_COMMAND_LIMIT", 422, "Review at most 1000 semantic objects per import; the preview can contain a larger scope");
        return List.copyOf(commands);
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
    public UnaryOperator<String> portfolioContribution(RepositoryContext context) { return source -> portfolio.contributeTo(source, context.username(), workspace(context)); }
    private Map<String, String> exchangeProperties(Connection connection, Artifact artifact) {
        return Map.of("x-exchange-connection", connection.id().toString(), "x-exchange-id", artifact.id(), "x-exchange-profile", connection.connectorId(),
                "x-exchange-artifact", json.write(artifact));
    }
    private static RelationKey relationKey(Artifact relation, Map<String, String> elements) {
        String source = elements.get(relation.extensions().get("source")), target = elements.get(relation.extensions().get("target"));
        String type = relation.extensions().get("canonicalType");
        if (source == null || target == null || type == null) throw new IntegrationProblem("RELATION_MAPPING_REQUIRED", 422, "Relation endpoints and type must belong to the accepted supported model");
        return new RelationKey(source, type, target);
    }
    private static RelationKey parseRelation(String value) { String[] tokens = value.split(" ", 3); return new RelationKey(tokens[0], tokens[1], tokens[2]); }
    public static String stableId(UUID connection, String id) { return "ext-" + UUID.nameUUIDFromBytes((connection + "\u0000" + id).getBytes(StandardCharsets.UTF_8)).toString(); }
    public static WorkspaceContext workspace(RepositoryContext context) { return new WorkspaceContext(context.username(), context.workspaceId(), context.branch(), context.repositoryId()); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String archimateType(String canonical) {
        return switch (canonical) {
            case "Capability" -> "Capability"; case "Process" -> "BusinessProcess"; case "BusinessRole" -> "BusinessRole";
            case "CoreService" -> "ApplicationService"; case "COIService" -> "BusinessService"; case "CommunicationsService" -> "CommunicationNetwork";
            case "InformationProduct" -> "DataObject"; case "UserApplication", "System", "Component" -> "ApplicationComponent";
            default -> throw new IntegrationProblem("UNSUPPORTED_ELEMENT_TYPE", 422, "Canonical element type has no exchange mapping");
        };
    }
    private static String archimateRelation(String canonical) {
        return switch (canonical) {
            case "REALIZES" -> "Realization"; case "SUPPORTS" -> "Serving"; case "ASSIGNED_TO" -> "Assignment";
            case "CONSUMES", "PRODUCES" -> "Access"; case "COMMUNICATES_WITH" -> "Flow"; case "CONTAINS" -> "Composition";
            case "RELATED_TO" -> "Association";
            default -> throw new IntegrationProblem("UNSUPPORTED_RELATION_TYPE", 422, "Canonical relation type has no declared exchange mapping");
        };
    }
}
