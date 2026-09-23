package com.taxonomy.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxonomy.diagram.*;
import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only working-view handoff. Reuses the reviewed codec; never creates a sync checkpoint. */
public final class SparxDiagramHandoff {
    public static final String PROFILE = "taxonomy-sparx-working-view-v1";
    private static final Map<String, String> NODE_TYPES = Map.ofEntries(
            Map.entry("Capabilities", "Capability"), Map.entry("Business Processes", "Process"),
            Map.entry("Business Roles", "BusinessRole"), Map.entry("Services", "CoreService"),
            Map.entry("Core Services", "CoreService"), Map.entry("COI Services", "COIService"),
            Map.entry("Communications Services", "CommunicationsService"),
            Map.entry("Applications", "UserApplication"), Map.entry("User Applications", "UserApplication"),
            Map.entry("Information Products", "InformationProduct"), Map.entry("Systems", "System"),
            Map.entry("Components", "Component"));
    // An explicit broad visual handoff, not a claim of native semantic equivalence.
    private static final Set<String> ASSOCIATION_PROJECTIONS = Set.of(
            "SUPPORTS", "ENABLES", "USES", "FULFILLS", "IMPLEMENTS", "ASSIGNED_TO", "PRODUCES", "REQUIRES", "PART_OF");
    private SparxDiagramHandoff() { }

    /** A namespace identifies this delivery, not a persisted repository or bidirectional connection. */
    public static byte[] build(DiagramModel graph, UUID namespace) throws IOException {
        Objects.requireNonNull(namespace, "Delivery namespace");
        Map<String, DiagramNode> nodes = validate(graph);
        List<Artifact> artifacts = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        List<MappingLoss> losses = new ArrayList<>();
        List<Map<String, Object>> nodeEvidence = new ArrayList<>(), relationEvidence = new ArrayList<>();
        for (DiagramNode node : nodes.values()) {
            String canonical = SparxMappingProfile.CANONICAL_TYPES.contains(node.type()) ? node.type() : NODE_TYPES.get(node.type());
            if (canonical == null && !node.container()) throw new IllegalArgumentException("No Sparx mapping for element type: " + node.type());
            String id = identity(namespace, "node", node.id());
            Map<String, String> attributes = new TreeMap<>();
            attributes.put("tag:taxonomy.sourceId", node.id());
            attributes.put("tag:taxonomy.sourceType", node.type());
            attributes.put("tag:taxonomy.relevance", Double.toString(node.relevance()));
            attributes.put("tag:taxonomy.anchor", Boolean.toString(node.anchor()));
            attributes.put("tag:taxonomy.selectedForImpact", Boolean.toString(node.selectedForImpact()));
            attributes.put("tag:taxonomy.layer", Integer.toString(node.layer()));
            attributes.put("tag:taxonomy.depth", Integer.toString(node.depth()));
            if (node.parentId() != null) attributes.put("tag:taxonomy.sourceParentId", node.parentId());
            if (node.container()) {
                attributes.put("tag:taxonomy.displayOnly", "true");
                losses.add(new MappingLoss(node.id(), "container", "VISUAL_GROUP_AS_PACKAGE", LossDisposition.TRANSFORMED,
                        "Display-only grouping exported as a package, not an architecture element."));
            } else attributes.put("tag:taxonomy.elementType", canonical);
            artifacts.add(new Artifact(id, node.container() ? ArtifactKind.SPECIFICATION : ArtifactKind.ELEMENT,
                    node.container() ? "Package" : SparxMappingProfile.umlType(canonical), node.label(), "", attributes,
                    node.container() ? Map.of() : Map.of("canonicalType", canonical)));
            Map<String, Object> evidence = new TreeMap<>();
            evidence.put("sourceId", node.id()); evidence.put("exportGuid", id); evidence.put("sourceType", node.type());
            evidence.put("title", node.label() == null ? "" : node.label()); evidence.put("attributes", attributes);
            nodeEvidence.add(evidence);
        }
        for (DiagramEdge edge : graph.edges().stream().sorted(Comparator.comparing(DiagramEdge::id)).toList()) {
            String canonical = edge.relationType(), transport = SparxMappingProfile.eaRelation(canonical);
            if (transport == null) {
                if (!ASSOCIATION_PROJECTIONS.contains(canonical)) throw new IllegalArgumentException("No Sparx mapping for relation type: " + canonical);
                canonical = "RELATED_TO"; transport = "Association";
                losses.add(new MappingLoss(edge.id(), "relationType", "BROAD_ASSOCIATION", LossDisposition.TRANSFORMED,
                        edge.relationType() + " is a labelled Association; its original type is retained, not native semantic equivalence."));
            }
            Map<String, String> attributes = new TreeMap<>();
            attributes.put("name", edge.relationType());
            attributes.put("tag:taxonomy.sourceId", edge.id());
            attributes.put("tag:taxonomy.sourceType", edge.relationType());
            attributes.put("tag:taxonomy.relevance", Double.toString(edge.relevance()));
            if (edge.relationCategory() != null) attributes.put("tag:taxonomy.relationCategory", edge.relationCategory());
            String id = identity(namespace, "edge", edge.id());
            relations.add(new Relation(id, transport, identity(namespace, "node", edge.sourceId()), identity(namespace, "node", edge.targetId()),
                    attributes, Map.of("canonicalType", canonical, "direction", "Source -> Destination")));
            relationEvidence.add(Map.of("sourceId", edge.id(), "exportGuid", id, "source", edge.sourceId(), "target", edge.targetId(),
                    "sourceType", edge.relationType(), "transportType", transport, "attributes", attributes));
        }
        losses.add(new MappingLoss(null, "layout", "DISPLAY_ONLY_LAYOUT", LossDisposition.UNSUPPORTED,
                "Diagram coordinates, colors and display-only hierarchy are not EA diagram XML. Source parent IDs remain attributes."));
        String modelId = identity(namespace, "model", "working-view");
        var document = new ExchangeDocument(SparxMappingProfile.PROFILE, SparxMappingProfile.VERSION, null, false, null,
                artifacts, relations, List.of(), Map.of("identifier", modelId, "title", graph.title() == null ? "Architecture" : graph.title()), losses);
        byte[] xmi = new SparxXmiCodec().write(document);
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("profile", PROFILE); manifest.put("exchangeProfile", SparxMappingProfile.PROFILE + "@" + SparxMappingProfile.VERSION);
        manifest.put("mode", "fresh-copy"); manifest.put("sourceAuthority", "client-provided-working-view");
        manifest.put("productCompatibility", "not-certified"); manifest.put("syncCheckpointChanged", false);
        manifest.put("deliveryNamespace", namespace.toString()); manifest.put("elements", nodeEvidence); manifest.put("relations", relationEvidence);
        manifest.put("losses", losses); manifest.put("sha256", digest(xmi));
        byte[] json = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        String instructions = "Sparx EA / XMI — experimental fresh-copy handoff\n\n"
                + "Extract architecture.xmi and inspect manifest.json, including every mapping loss, before importing.\n"
                + "This exports exactly the supplied working view, without analysis or persistence.\n"
                + "GUIDs belong to this delivery. Repeated fresh exports are separate copies, not updates.\n"
                + "Original IDs are evidence tags, not authorized Taxonomy integration identities.\n"
                + "For bidirectional updates use Tool Integrations and its reviewed connection scope.\n"
                + "Import into a backed-up test model with diagram import disabled.\n"
                + "No specific Enterprise Architect version has been certified. No EA diagram layout is promised.\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "architecture.xmi", xmi); entry(zip, "manifest.json", json);
            entry(zip, "README.txt", instructions.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static Map<String, DiagramNode> validate(DiagramModel graph) {
        if (graph == null || graph.nodes() == null || graph.edges() == null || graph.nodes().isEmpty()
                || (long) graph.nodes().size() + graph.edges().size() > ExchangeXml.MAX_ARTIFACTS)
            throw new IllegalArgumentException("Sparx handoff needs a nonempty graph with at most " + ExchangeXml.MAX_ARTIFACTS + " objects and relations");
        Map<String, DiagramNode> nodes = new TreeMap<>();
        for (DiagramNode node : graph.nodes()) {
            if (node == null || !validId(node.id()) || node.type() == null || nodes.putIfAbsent(node.id(), node) != null)
                throw new IllegalArgumentException("Invalid or duplicate source element identity/type");
            score(node.relevance());
        }
        for (DiagramNode node : nodes.values()) {
            Set<String> seen = new HashSet<>(); DiagramNode next = node;
            while (next.parentId() != null) {
                if (!seen.add(next.id()) || seen.size() > 80 || (next = nodes.get(next.parentId())) == null)
                    throw new IllegalArgumentException("Invalid or cyclic source parent reference");
            }
        }
        Set<String> edges = new HashSet<>();
        for (DiagramEdge edge : graph.edges()) {
            if (edge == null || !validId(edge.id()) || !edges.add(edge.id()) || !nodes.containsKey(edge.sourceId())
                    || !nodes.containsKey(edge.targetId()) || edge.relationType() == null)
                throw new IllegalArgumentException("Invalid or duplicate source relation identity/reference/type");
            score(edge.relevance());
        }
        return nodes;
    }
    private static boolean validId(String id) { return id != null && !id.isBlank() && id.length() <= 2048; }
    private static void score(double score) {
        if (!Double.isFinite(score) || score < 0 || score > 1) throw new IllegalArgumentException("Relevance must be finite and in [0,1]");
    }
    private static String identity(UUID namespace, String kind, String id) { return SparxMappingProfile.externalId(namespace, kind + "\u0000" + id); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0));
        zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
    }
}
