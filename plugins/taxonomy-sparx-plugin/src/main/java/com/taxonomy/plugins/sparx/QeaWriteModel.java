package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Native identity and containment indexes. Reviewed object GUIDs are never generated here. */
final class QeaWriteModel {
    record NativeObject(ArtifactKind kind, String table, String key, long id, Row original) { }
    final Map<String, NativeObject> nativeObjects = new TreeMap<>();
    final Map<String, Row> mirrors = new HashMap<>();
    final Map<String, Artifact> beforeArtifacts = new HashMap<>();
    final Map<String, Relation> beforeRelations = new HashMap<>();
    final Map<String, Placement> placements = new HashMap<>();
    final Map<String, String> parents = new HashMap<>();
    final Set<String> parentChanges = new HashSet<>();
    final Map<String, Artifact> selected = new TreeMap<>();
    final Map<String, Relation> relations = new TreeMap<>();
    private final Set<String> occupied = new HashSet<>();
    final String model;
    final String root;
    final FileExportMode mode;

    QeaWriteModel(BasedOutboundRequest request, Snapshot basis, ExchangeDocument before) {
        ExchangeDocument document = request.outbound().document();
        model = QeaIds.guid(document.metadata().get("identifier"), "QEA_MODEL_IDENTITY"); mode = request.mode(); occupied.add(model);
        var packageGuids = new HashMap<Long, String>();
        for (Row row : basis.rows("t_package")) {
            String guid = add(row, ArtifactKind.SPECIFICATION, "package_id"); packageGuids.put(row.id("package_id"), guid);
        }
        root = mode == FileExportMode.NEW_PROJECT ? QeaIds.guid(basis.rows("t_package").getFirst().text("ea_guid")) : null;
        for (Row row : basis.rows("t_package")) parents.put(QeaIds.guid(row.text("ea_guid")), parent(packageGuids, row.number("parent_id")));
        for (Row row : basis.rows("t_object")) {
            String guid = QeaIds.guid(row.text("ea_guid"));
            if (row.text("object_type").equals("Package")) {
                String owner = row.text("pdata1").matches("[0-9]+") ? packageGuids.get(row.number("pdata1")) : null;
                if (owner == null && nativeObjects.containsKey(guid) && nativeObjects.get(guid).kind() == ArtifactKind.SPECIFICATION) owner = guid;
                if (owner == null || mirrors.put(owner, row) != null) throw fail("QEA_PACKAGE_ALIAS", "A package mirror must identify one existing package");
                if (!owner.equals(guid) && !occupied.add(guid)) duplicate();
            } else {
                add(row, row.text("object_type").equals("Requirement") ? ArtifactKind.REQUIREMENT : ArtifactKind.ELEMENT, "object_id");
                parents.put(guid, parent(packageGuids, row.number("package_id")));
            }
        }
        for (Row row : basis.rows("t_diagram")) {
            String guid = add(row, ArtifactKind.VIEW, "diagram_id"); parents.put(guid, parent(packageGuids, row.number("package_id")));
        }
        for (Row row : basis.rows("t_connector")) add(row, ArtifactKind.RELATION, "connector_id");
        before.artifacts().forEach(a -> beforeArtifacts.put(a.id(), a));
        before.relations().forEach(r -> beforeRelations.put(r.id(), r));
        before.placements().forEach(p -> placements.put(p.id(), p));
        Set<String> ids = new HashSet<>();
        for (Artifact artifact : document.artifacts()) {
            String id = QeaIds.guid(artifact.id());
            if (!ids.add(id)) duplicate();
            if (!Set.of(ArtifactKind.SPECIFICATION, ArtifactKind.ELEMENT, ArtifactKind.REQUIREMENT, ArtifactKind.VIEW).contains(artifact.kind()))
                throw fail("QEA_KIND_UNSUPPORTED", "The native writer supports packages, elements, requirements and views");
            NativeObject nativeObject = nativeObjects.get(id);
            if (nativeObject != null && nativeObject.kind() != artifact.kind()) throw fail("QEA_IDENTITY_CONFLICT", "An existing native identity cannot change object kind");
            if (nativeObject == null && !occupied.add(id)) duplicate();
            selected.put(id, new Artifact(id, artifact.kind(), artifact.type(), artifact.title(), artifact.text(), artifact.attributes(), artifact.extensions()));
            parents.putIfAbsent(id, mode == FileExportMode.NEW_PROJECT ? root : defaultPackage());
        }
        for (Relation relation : document.relations()) {
            if (relation.id().startsWith("implicit:")) {
                Relation prior = beforeRelations.get(relation.id());
                if (prior != null && prior.type().equals(relation.type()) && prior.source().equals(relation.source()) && prior.target().equals(relation.target())
                        && prior.attributes().equals(relation.attributes()) && prior.extensions().equals(relation.extensions())) continue;
                throw fail("QEA_IMPLICIT_REFERENCE_UNSUPPORTED", "Implicit references cannot be invented or rewritten as native connectors");
            }
            String id = QeaIds.guid(relation.id());
            if (!ids.add(id)) duplicate();
            NativeObject nativeObject = nativeObjects.get(id);
            if (nativeObject != null && nativeObject.kind() != ArtifactKind.RELATION) throw fail("QEA_IDENTITY_CONFLICT", "An existing GUID belongs to a different native object kind");
            if (nativeObject == null && !occupied.add(id)) duplicate();
            Relation prior = beforeRelations.get(id);
            if (prior != null && prior.type().equals(relation.type()) && prior.source().equals(relation.source()) && prior.target().equals(relation.target())
                    && prior.attributes().equals(relation.attributes()) && prior.extensions().equals(relation.extensions())) continue;
            relations.put(id, new Relation(id, relation.type(), reference(relation.source()), reference(relation.target()), relation.attributes(), relation.extensions()));
        }
        Set<String> selectedPlacements = new HashSet<>();
        for (Placement placement : document.placements()) {
            if (!selectedPlacements.add(placement.id())) duplicate();
            placements.put(placement.id(), placement);
        }
        Set<String> placed = new HashSet<>();
        for (Placement placement : document.placements()) if (placement.containerId().equals(model)) {
            String member = reference(placement.artifactId());
            parentChanges.add(member);
            if (!placed.add(member)) throw fail("QEA_SELECTION_DEPENDENCY", "An object cannot have two selected package parents");
            kind(member);
            String parent = "";
            if (placement.parentId() != null && !placement.parentId().isEmpty()) {
                Placement occurrence = placements.get(placement.parentId());
                if (occurrence == null || !occurrence.containerId().equals(model) || kind(reference(occurrence.artifactId())) != ArtifactKind.SPECIFICATION)
                    throw fail("QEA_SELECTION_DEPENDENCY", "The selected package parent is unavailable");
                parent = reference(occurrence.artifactId());
            } else if (mode == FileExportMode.NEW_PROJECT && !member.equals(root)) parent = root;
            if (placement.attributes().containsKey("placementKind") && !placement.attributes().get("placementKind").equals("package"))
                throw fail("QEA_SELECTION_DEPENDENCY", "Placement kind contradicts the model container");
            parents.put(member, parent);
        }
        for (String id : parents.keySet()) depth(id);
    }
    private String add(Row row, ArtifactKind kind, String key) {
        String guid = QeaIds.guid(row.text("ea_guid"));
        if (!occupied.add(guid)) duplicate();
        nativeObjects.put(guid, new NativeObject(kind, row.table(), key, row.id(key), row)); return guid;
    }
    private static String parent(Map<Long, String> packages, long id) {
        if (id == 0) return "";
        String parent = packages.get(id);
        if (parent == null) throw fail("QEA_SELECTION_DEPENDENCY", "The basis has an unresolved package reference");
        return parent;
    }
    private String defaultPackage() {
        return nativeObjects.entrySet().stream().filter(e -> e.getValue().kind() == ArtifactKind.SPECIFICATION)
                .sorted(Comparator.comparingLong(e -> e.getValue().id())).map(Map.Entry::getKey).findFirst().orElse("");
    }
    ArtifactKind kind(String id) {
        Artifact artifact = selected.get(id); if (artifact != null) return artifact.kind();
        NativeObject known = nativeObjects.get(id);
        if (known == null) throw fail("QEA_SELECTION_DEPENDENCY", "An accepted native reference has no existing or selected object");
        return known.kind();
    }
    long packageId(String member) {
        String parent = parents.getOrDefault(member, ""); if (parent.isEmpty()) return 0;
        NativeObject known = nativeObjects.get(parent);
        if (known == null || known.kind() != ArtifactKind.SPECIFICATION) throw fail("QEA_SELECTION_DEPENDENCY", "Package parent was not allocated");
        return known.id();
    }
    long objectId(String guid) {
        Row mirror = mirrors.get(guid); if (mirror != null) return mirror.id("object_id");
        NativeObject known = nativeObjects.get(guid);
        if (known == null || !known.table().equals("t_object")) throw fail("QEA_SELECTION_DEPENDENCY", "A connector or diagram member has no native object row");
        return known.id();
    }
    int depth(String id) {
        Set<String> seen = new HashSet<>(); String cursor = id;
        while (!cursor.isEmpty()) {
            if (!seen.add(cursor)) throw fail("QEA_PACKAGE_CYCLE", "The selected package hierarchy is cyclic");
            if (seen.size() > QeaDatabase.MAX_DEPTH) throw fail("QEA_LIMIT", "The selected package hierarchy exceeds 128 levels");
            cursor = parents.getOrDefault(cursor, "");
            if (!cursor.isEmpty() && kind(cursor) != ArtifactKind.SPECIFICATION) throw fail("QEA_SELECTION_DEPENDENCY", "A containment parent is not a package");
        }
        return seen.size();
    }
    void allocate(QeaWriteDatabase database) throws SQLException {
        for (Artifact artifact : selected.values()) if (!nativeObjects.containsKey(artifact.id())) {
            String table = switch (artifact.kind()) { case SPECIFICATION -> "t_package"; case VIEW -> "t_diagram"; default -> "t_object"; };
            String key = switch (artifact.kind()) { case SPECIFICATION -> "package_id"; case VIEW -> "diagram_id"; default -> "object_id"; };
            nativeObjects.put(artifact.id(), new NativeObject(artifact.kind(), table, key,
                    database.allocate(table, key, frozenId(artifact.attributes(), table, key, artifact.id())), null));
        }
        for (Relation relation : relations.values()) if (!nativeObjects.containsKey(relation.id()))
            nativeObjects.put(relation.id(), new NativeObject(ArtifactKind.RELATION, "t_connector", "connector_id",
                    database.allocate("t_connector", "connector_id", frozenId(relation.attributes(), "t_connector", "connector_id", relation.id())), null));
    }
    static String frozenId(Map<String, String> attributes, String table, String key, String guid) {
        String original = attributes.get("ea:" + table + ":ea_guid");
        try { return original != null && QeaIds.guid(original).equals(guid) ? attributes.get("ea:" + table + ":" + key) : null; }
        catch (QeaFailure invalid) { return null; } // Unrelated raw evidence is not an allocation instruction.
    }
    static String reference(String id) {
        if (id != null && id.startsWith("unresolved:")) throw fail("QEA_SELECTION_DEPENDENCY", "An unresolved source reference cannot be written");
        return QeaIds.guid(id);
    }
    private static void duplicate() { throw fail("QEA_DUPLICATE_IDENTITY", "Multiple selected or existing objects claim the same native identity"); }
}
