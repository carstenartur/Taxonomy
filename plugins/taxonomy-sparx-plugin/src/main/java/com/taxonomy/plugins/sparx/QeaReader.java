package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;
import com.taxonomy.sparx.mapping.QeaOperationSupport;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Projects a fixed EA subset. Raw source evidence never authorizes a canonical change. */
final class QeaReader {
    private final String version;
    QeaReader() { this(SparxQeaConnector.VERSION); }
    QeaReader(String version) { this.version = version; }
    private static final Set<String> STEREOTYPED_BASES = Set.of("Class", "Component", "Node", "Activity", "Actor", "UseCase", "Interface", "Artifact");
    private static final Map<String, String> GENERIC_ELEMENTS = Map.of("Component", "Component", "Node", "System",
            "Activity", "Process", "Actor", "BusinessRole");
    private static final Map<String, String> GENERIC_RELATIONS = Map.of("Dependency", "DEPENDS_ON", "Association", "RELATED_TO");
    private final Map<String, Draft> artifacts = new TreeMap<>();
    private final Map<Long, Draft> packages = new TreeMap<>();
    private final Map<Long, Draft> objects = new TreeMap<>();
    private final Map<Long, Draft> diagrams = new TreeMap<>();
    private final Map<Long, Row> objectRows = new TreeMap<>();
    private final Map<String, Draft> artifactGuids = new HashMap<>();
    private final Map<Long, ConnectorDraft> connectors = new TreeMap<>();
    private final Map<String, ConnectorDraft> connectorGuids = new HashMap<>();
    private final Map<String, String> identities = new HashMap<>();
    private final List<Relation> relations = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private final List<MappingLoss> losses = new ArrayList<>();
    private String model;

    private static final class Draft {
        final String id;
        final ArtifactKind kind;
        final String type;
        final String title;
        final String text;
        final Row row;
        final Map<String, String> attributes = new TreeMap<>();
        final Map<String, String> extensions = new TreeMap<>();
        Draft(String id, ArtifactKind kind, String type, Row row, String notes) {
            this.id = id; this.kind = kind; this.type = type; this.row = row;
            title = row.text("name"); text = row.text(notes); attributes.putAll(row.evidence());
            if (!row.text("stereotype").isBlank()) extensions.put("stereotype", row.text("stereotype"));
        }
        Artifact freeze() { return new Artifact(id, kind, type, title, text, attributes, extensions); }
    }

    private static final class ConnectorDraft {
        final String id;
        final String source;
        final String target;
        final Row row;
        String type;
        final Map<String, String> attributes = new TreeMap<>();
        final Map<String, String> extensions = new TreeMap<>();
        ConnectorDraft(String id, String source, String target, Row row) {
            this.id = id; this.source = source; this.target = target; this.row = row; this.type = row.text("connector_type");
            attributes.putAll(row.evidence());
            extensions.put("direction", row.text("direction").isEmpty() ? "Unspecified" : row.text("direction"));
            if (!row.text("stereotype").isEmpty()) extensions.put("stereotype", row.text("stereotype"));
        }
        Relation freeze() { return new Relation(id, type, source, target, attributes, extensions); }
    }
    private record EvidenceTarget(String id, Map<String, String> attributes, Map<String, String> extensions) { }

    ExchangeDocument read(byte[] bytes, String externalVersion) {
        Snapshot snapshot = new QeaDatabase().read(bytes);
        List<Row> models = snapshot.rows("usys_system").stream().filter(r -> r.text("property").equalsIgnoreCase("ProjectGUID")).toList();
        if (models.size() != 1) throw fail("QEA_MODEL_IDENTITY", "Expected exactly one usys_system ProjectGUID value; no filename/hash identity fallback is used");
        model = QeaIds.guid(models.getFirst().text("value"), "QEA_MODEL_IDENTITY");
        claim(model, "model");
        for (Row row : snapshot.rows("usys_system")) if (!row.text("property").equalsIgnoreCase("ProjectGUID"))
            loss(model, "usys_system:" + row.text("property"), "QEA_SYSTEM_PROPERTY_UNSUPPORTED", LossDisposition.UNSUPPORTED,
                    "Only ProjectGUID is projected from system properties; retain the original QEA for other settings");
        for (Row row : snapshot.rows("t_package")) {
            Draft draft = add(row, ArtifactKind.SPECIFICATION, "Package", "notes");
            put(packages, row.id("package_id"), draft);
        }
        validatePackageTree();
        for (Row row : snapshot.rows("t_object")) {
            if (objectRows.putIfAbsent(row.id("object_id"), row) != null) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated t_object local identity");
            if (row.text("object_type").equals("Package")) continue;
            ArtifactKind kind = row.text("object_type").equals("Requirement") ? ArtifactKind.REQUIREMENT : ArtifactKind.ELEMENT;
            put(objects, row.id("object_id"), add(row, kind, row.text("object_type"), "note"));
        }
        for (Row row : snapshot.rows("t_object")) if (row.text("object_type").equals("Package")) packageAlias(row);
        for (Row row : snapshot.rows("t_diagram")) put(diagrams, row.id("diagram_id"), add(row, ArtifactKind.VIEW, "Diagram", "notes"));
        for (Row row : snapshot.rows("t_connector")) connector(row);
        tags(snapshot.rows("t_objectproperties"), false);
        tags(snapshot.rows("t_connectortag"), true);
        crossReferences(snapshot.rows("t_xref"));
        for (Draft draft : artifacts.values()) if (draft.kind == ArtifactKind.ELEMENT) {
            String stereotype = draft.extensions.getOrDefault("stereotype", "");
            String named = stereotype.startsWith("Taxonomy::") ? stereotype.substring(10) : stereotype;
            String canonical = stereotype.isEmpty() ? GENERIC_ELEMENTS.get(draft.type)
                    : STEREOTYPED_BASES.contains(draft.type) && QeaSelectionValidator.ELEMENT_TYPES.contains(named) ? named : null;
            if (version.equals("2") && QeaSemanticProfile.declared(draft.attributes, draft.extensions)) {
                try { canonical = QeaSemanticProfile.readElement(draft.type, draft.attributes, draft.extensions); }
                catch (QeaFailure invalid) {
                    canonical = null;
                    loss(draft.id, "type", "QEA_TAXONOMY_DECLARATION_INVALID", LossDisposition.UNSUPPORTED,
                            "Taxonomy tags, UML transport and qualified stereotype contradict each other or are incomplete");
                }
            }
            if (canonical != null) draft.extensions.put("canonicalType", canonical);
            else loss(draft.id, "type", "QEA_ELEMENT_UNMAPPED", LossDisposition.UNSUPPORTED, "Choose an explicit Taxonomy type or reject this source element");
        }
        for (ConnectorDraft connector : connectors.values()) {
            String canonical = connector.extensions.getOrDefault("stereotype", "").isEmpty() ? GENERIC_RELATIONS.get(connector.row.text("connector_type")) : null;
            if (version.equals("2") && QeaSemanticProfile.declared(connector.attributes, connector.extensions)) {
                try {
                    String transport = QeaNativeRelations.transport(connector.row);
                    canonical = QeaSemanticProfile.readRelation(transport, connector.attributes, connector.extensions);
                    connector.type = transport;
                }
                catch (QeaFailure invalid) {
                    canonical = null;
                    loss(connector.id, "type", "QEA_TAXONOMY_DECLARATION_INVALID", LossDisposition.UNSUPPORTED,
                            "Taxonomy relation tags, UML transport, stereotype and direction must all agree");
                }
            }
            if (canonical != null && !connector.extensions.get("direction").equals("Bi-Directional")) connector.extensions.put("canonicalType", canonical);
            else loss(connector.id, "type", "QEA_RELATION_UNMAPPED", LossDisposition.UNSUPPORTED,
                    "Original connector type and endpoints require an explicit mapping or preserve-only review");
            relations.add(connector.freeze());
            for (String field : connector.row.binaryFields()) loss(connector.id, field, "QEA_BINARY_FIELD", LossDisposition.UNSUPPORTED,
                    "Binary field retained only as length and SHA-256 digest");
        }
        implicitReferences();
        packagePlacements();
        diagramPlacements(snapshot.rows("t_diagramobjects"));
        diagramLinks(snapshot.rows("t_diagramlinks"));
        for (String table : snapshot.omittedTables()) loss(model, table, "QEA_TABLE_UNSUPPORTED", LossDisposition.UNSUPPORTED,
                "This table was not projected; retain the original QEA file for its contents");
        loss(model, "scope", "QEA_PARTIAL_SCOPE", LossDisposition.UNSUPPORTED,
                "This bounded table projection is not a complete EA repository and cannot authorize deletion of unmentioned objects");
        for (Draft artifact : artifacts.values()) {
            loss(artifact.id, artifact.row.table(), "QEA_SCALAR_EVIDENCE", LossDisposition.PRESERVED_EXTENSION,
                    "Non-null source scalars are retained in ea: attributes; only declared fields are projected");
            for (String field : artifact.row.binaryFields()) loss(artifact.id, field, "QEA_BINARY_FIELD", LossDisposition.UNSUPPORTED,
                    "Binary field retained only as length and SHA-256 digest");
        }
        relations.sort(Comparator.comparing(Relation::id));
        placements.sort(Comparator.comparing(Placement::id));
        losses.sort(Comparator.comparing(MappingLoss::artifactId).thenComparing(MappingLoss::field).thenComparing(MappingLoss::code).thenComparing(MappingLoss::detail));
        var metadata = new TreeMap<>(Map.of("identifier", model, "sourceSha256", snapshot.sha256(), "sourceBytes", Integer.toString(snapshot.bytes()),
                "sourceFormat", "qea", "mappingProfile", "sparx-qea@" + version, "compatibilityEvidence", "synthetic-fixtures-only"));
        if (version.equals("2")) {
            metadata.put("operationSupport", QeaOperationSupport.ID);
            QeaOperationSupport.OUTCOMES.forEach((operation, outcome) -> metadata.put("operation:" + operation, outcome));
            List<Draft> roots = packages.values().stream().filter(p -> p.row.number("parent_id") == 0).toList();
            if (roots.size() == 1) metadata.put("rootIdentifier", roots.getFirst().id);
        }
        return new ExchangeDocument(SparxQeaConnector.PROFILE, version, externalVersion, false,
                "QEA SQLite snapshot; SHA-256 " + snapshot.sha256(), artifacts.values().stream().map(Draft::freeze).toList(), relations, placements,
                metadata, losses);
    }

    private Draft add(Row row, ArtifactKind kind, String type, String notes) {
        String id = QeaIds.guid(row.text("ea_guid"));
        claim(id, row.table());
        Draft draft = new Draft(id, kind, type, row, notes);
        artifacts.put(id, draft);
        artifactGuids.put(id, draft);
        return draft;
    }
    private void claim(String guid, String owner) {
        if (identities.putIfAbsent(guid, owner) != null) throw fail("QEA_DUPLICATE_IDENTITY", "Two source objects claim the same EA GUID");
    }
    private static void put(Map<Long, Draft> index, long id, Draft value) {
        if (index.putIfAbsent(id, value) != null) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated local row identity in " + value.row.table());
    }

    private void packageAlias(Row row) {
        String guid = QeaIds.guid(row.text("ea_guid"));
        Draft byGuid = artifacts.get(guid);
        if (byGuid != null && byGuid.kind != ArtifactKind.SPECIFICATION) throw fail("QEA_DUPLICATE_IDENTITY", "A package mirror GUID belongs to a non-package object");
        Draft byId = row.text("pdata1").matches("[0-9]+") ? packages.get(row.number("pdata1")) : null;
        if (byGuid != null && byId != null && byGuid != byId) throw fail("QEA_PACKAGE_ALIAS", "Package mirror GUID and PDATA1 disagree");
        Draft owner = byId == null ? byGuid : byId;
        if (owner == null || owner.extensions.containsKey("ea:packageObjectGuid")) throw fail("QEA_PACKAGE_ALIAS", "Package mirror must resolve to one distinct t_package row");
        if (!guid.equals(owner.id)) claim(guid, "package mirror");
        artifactGuids.put(guid, owner);
        owner.extensions.put("ea:packageObjectGuid", guid);
        owner.attributes.putAll(row.evidence());
        if (!row.text("stereotype").isBlank()) owner.extensions.put("stereotype", row.text("stereotype"));
        put(objects, row.id("object_id"), owner);
    }

    private void validatePackageTree() {
        for (Long start : packages.keySet()) {
            Set<Long> seen = new HashSet<>();
            long id = start;
            while (id != 0 && packages.containsKey(id)) {
                if (!seen.add(id)) throw fail("QEA_PACKAGE_CYCLE", "Package hierarchy contains a cycle");
                if (seen.size() > QeaDatabase.MAX_DEPTH) throw fail("QEA_LIMIT", "Package hierarchy exceeds 128 levels");
                id = packages.get(id).row.number("parent_id");
            }
        }
    }

    private String reference(Map<Long, Draft> index, long local, String kind, String owner, String field) {
        Draft value = index.get(local);
        if (value != null) return value.id;
        loss(owner, field, "QEA_UNRESOLVED_REFERENCE", LossDisposition.UNSUPPORTED,
                "Referenced " + kind + " local ID " + local + " is absent from the snapshot");
        return "unresolved:" + kind + ":" + local;
    }

    private void connector(Row row) {
        String id = QeaIds.guid(row.text("ea_guid"));
        claim(id, "t_connector");
        String source = reference(objects, row.number("start_object_id"), "object", id, "Start_Object_ID");
        String target = reference(objects, row.number("end_object_id"), "object", id, "End_Object_ID");
        var connector = new ConnectorDraft(id, source, target, row);
        if (connectors.putIfAbsent(row.id("connector_id"), connector) != null) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated connector local identity");
        connectorGuids.put(id, connector);
    }

    private void tags(List<Row> rows, boolean connectorTags) {
        Map<String, List<Row>> groups = new TreeMap<>();
        Map<String, EvidenceTarget> targets = new HashMap<>();
        Set<Long> localIds = new HashSet<>();
        for (Row row : rows) {
            long local = row.id("propertyid");
            if (!localIds.add(local)) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated tag local identity");
            EvidenceTarget target;
            if (connectorTags) {
                ConnectorDraft owner = connectors.get(row.number("elementid"));
                target = owner == null ? null : new EvidenceTarget(owner.id, owner.attributes, owner.extensions);
            } else {
                Draft owner = objects.get(row.number("object_id"));
                target = owner == null ? null : new EvidenceTarget(owner.id, owner.attributes, owner.extensions);
            }
            if (target == null) {
                loss(model, row.table() + ":" + local, "QEA_UNRESOLVED_REFERENCE", LossDisposition.UNSUPPORTED,
                        "Tagged value owner is absent; the original row remains in the QEA source file");
                continue;
            }
            evidence(target, row, Long.toString(local));
            String key = target.id() + "\0" + row.text("property");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            targets.put(key, target);
        }
        groups.forEach((key, values) -> {
            EvidenceTarget target = targets.get(key);
            String name = values.getFirst().text("property");
            if (values.size() != 1 || name.isBlank()) {
                loss(target.id(), "tag:" + name, values.size() != 1 ? "QEA_DUPLICATE_TAG" : "QEA_TAG_UNSUPPORTED", LossDisposition.PRESERVED_EXTENSION,
                        "Original tagged-value rows are retained separately; no arbitrary scalar tag was selected");
                return;
            }
            Row row = values.getFirst();
            String value = row.text("value");
            if (value.equals("<memo>") && !row.text("notes").isEmpty()) {
                value = row.text("notes");
                loss(target.id(), "tag:" + name, "QEA_MEMO_TAG", LossDisposition.TRANSFORMED, "EA memo tag text was read from Notes; original fields remain evidence");
            }
            target.attributes().put("tag:" + name, value);
        });
    }

    private void evidence(EvidenceTarget target, Row row, String rowId) {
        row.values().forEach((key, value) -> target.attributes().put("ea:" + row.table() + ":" + rowId + ":" + key, value));
        for (String field : row.binaryFields()) loss(target.id(), row.table() + ":" + rowId + ":" + field,
                "QEA_BINARY_FIELD", LossDisposition.UNSUPPORTED, "Binary field retained only as length and SHA-256 digest");
    }

    private void crossReferences(List<Row> rows) {
        Set<String> xrefIds = new HashSet<>();
        Map<String, Set<String>> stereotypes = new HashMap<>();
        for (Row row : rows.stream().sorted(Comparator.comparing(r -> r.text("xrefid"))).toList()) {
            String id = QeaIds.guid(row.text("xrefid"));
            if (!xrefIds.add(id)) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated cross-reference GUID");
            String client;
            try { client = QeaIds.guid(row.text("client")); }
            catch (QeaFailure invalid) {
                loss(model, "t_xref:" + id, "QEA_UNRESOLVED_REFERENCE", LossDisposition.UNSUPPORTED, "Cross-reference client is not a supported EA GUID");
                continue;
            }
            Draft artifact = artifactGuids.get(client);
            ConnectorDraft connector = connectorGuids.get(client);
            EvidenceTarget target = artifact != null ? new EvidenceTarget(artifact.id, artifact.attributes, artifact.extensions)
                    : connector != null ? new EvidenceTarget(connector.id, connector.attributes, connector.extensions) : null;
            if (target == null) {
                loss(model, "t_xref:" + id, "QEA_UNRESOLVED_REFERENCE", LossDisposition.UNSUPPORTED, "Cross-reference owner is outside the supported projection");
                continue;
            }
            evidence(target, row, id);
            loss(target.id(), "t_xref:" + id, "QEA_XREF_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "Cross-reference row is retained; only recognized stereotype declarations affect the mapping preview");
            if (!row.text("type").equalsIgnoreCase(artifact != null ? "element property" : "connector property")) {
                loss(target.id(), "t_xref:" + id, "QEA_XREF_KIND_UNSUPPORTED", LossDisposition.PRESERVED_EXTENSION,
                        "Cross-reference kind does not describe the resolved owner kind; no stereotype interpretation was applied");
                continue;
            }
            if (!row.text("name").equalsIgnoreCase("Stereotypes")) continue;
            Set<String> names = stereotypes.computeIfAbsent(target.id(), ignored -> new TreeSet<>());
            if (names.isEmpty() && !target.extensions().getOrDefault("stereotype", "").isEmpty())
                names.addAll(Arrays.asList(target.extensions().get("stereotype").split(",")));
            String description = row.text("description");
            int offset = 0;
            boolean parsed = false;
            while (offset < description.length()) {
                int start = description.indexOf("@STEREO;", offset);
                if (start < 0) break;
                int end = description.indexOf("@ENDSTEREO;", start + 8);
                if (end < 0) break;
                Map<String, String> fields = new HashMap<>();
                for (String entry : description.substring(start + 8, end).split(";")) {
                    int equal = entry.indexOf('=');
                    if (equal > 0) fields.put(entry.substring(0, equal), entry.substring(equal + 1));
                }
                String local = fields.getOrDefault("Name", "");
                String qualified = fields.getOrDefault("FQName", local);
                if (!qualified.isBlank()) {
                    if (!qualified.equals(local)) names.remove(local);
                    names.add(qualified);
                    parsed = true;
                }
                offset = end + 11;
            }
            if (!parsed) {
                names.add("unparsed EA stereotype declaration");
                loss(target.id(), "stereotype", "QEA_STEREOTYPE_UNPARSED", LossDisposition.UNSUPPORTED, "Stereotype declaration requires explicit interpretation");
            }
            target.extensions().put("stereotype", String.join(",", names));
        }
    }

    private void implicitReferences() {
        for (var entry : objectRows.entrySet()) {
            Draft owner = objects.get(entry.getKey());
            if (owner.kind == ArtifactKind.SPECIFICATION) continue;
            for (var reference : QeaImplicitReferences.from(entry.getValue())) {
                String target;
                if (reference.guid()) {
                    String guid;
                    try { guid = QeaIds.guid(reference.value()); }
                    catch (QeaFailure invalid) {
                        loss(owner.id, reference.field(), "QEA_REFERENCE_UNSUPPORTED", LossDisposition.PRESERVED_EXTENSION,
                                "Field is not a valid EA GUID; raw value is retained without inventing a relation");
                        continue;
                    }
                    Draft resolved = artifactGuids.get(guid);
                    target = resolved == null ? guid : resolved.id;
                    if (resolved == null) loss(owner.id, reference.field(), "QEA_UNRESOLVED_REFERENCE", LossDisposition.UNSUPPORTED,
                            "Implicit GUID reference is outside the supported snapshot");
                } else target = reference(objects, entry.getValue().number(reference.field()), "object", owner.id, reference.field());
                String source = owner.id;
                if (reference.incoming()) { source = target; target = owner.id; }
                String id = "implicit:" + UUID.nameUUIDFromBytes((model + "\0" + source + "\0" + reference.type() + "\0" + target + "\0" + reference.field()).getBytes(StandardCharsets.UTF_8));
                relations.add(new Relation(id, reference.type(), source, target, Map.of("ea:sourceTable", "t_object", "ea:sourceColumn", reference.field()),
                        Map.of("direction", "Source -> Destination", "ea:implicit", "true")));
                loss(id, reference.field(), "QEA_IMPLICIT_RELATION", LossDisposition.PRESERVED_EXTENSION,
                        "Source-field relationship has no automatic canonical mapping; explicitly map, reject or preserve it");
            }
        }
    }

    private void packagePlacements() {
        Map<String, Integer> positions = new HashMap<>();
        for (Draft pkg : packages.values()) place(pkg, pkg.row.number("parent_id"), "package:" + pkg.id, positions);
        for (var entry : objects.entrySet()) if (entry.getValue().kind != ArtifactKind.SPECIFICATION)
            place(entry.getValue(), objectRows.get(entry.getKey()).number("package_id"), "member:" + entry.getValue().id, positions);
        for (Draft diagram : diagrams.values()) place(diagram, diagram.row.number("package_id"), "member:" + diagram.id, positions);
    }
    private void place(Draft artifact, long parent, String id, Map<String, Integer> positions) {
        String parentId = parent == 0 ? null : "package:" + reference(packages, parent, "package", artifact.id, "Package_ID");
        int position = positions.merge(parentId == null ? "" : parentId, 1, Integer::sum) - 1;
        placements.add(new Placement(id, model, parentId, artifact.id, position, Map.of("placementKind", "package")));
    }

    private void diagramPlacements(List<Row> rows) {
        Set<String> identities = new HashSet<>();
        Map<String, Integer> positions = new HashMap<>();
        for (Row row : rows.stream().sorted(Comparator.comparingLong((Row r) -> r.number("diagram_id")).thenComparingLong(r -> r.number("object_id"))).toList()) {
            String view = reference(diagrams, row.number("diagram_id"), "diagram", model, "Diagram_ID");
            String object = reference(objects, row.number("object_id"), "object", view, "Object_ID");
            String id = "diagram:" + view + ":" + object;
            if (!row.text("instance_id").isBlank()) id += ":" + row.id("instance_id");
            if (!identities.add(id)) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated diagram member needs a distinct Instance_ID");
            var attributes = new TreeMap<>(row.evidence()); attributes.put("placementKind", "diagram");
            placements.add(new Placement(id, view, null, object, positions.merge(view, 1, Integer::sum) - 1, attributes));
            loss(id, "layout", "QEA_DIAGRAM_LAYOUT_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "Original diagram scalars are retained as evidence; native view projection imports membership only");
            Draft member = artifacts.get(object);
            if (member != null && member.kind != ArtifactKind.ELEMENT) loss(id, "member", "QEA_DIAGRAM_MEMBER_UNSUPPORTED", LossDisposition.PRESERVED_EXTENSION,
                    "Non-element diagram occurrence is retained as evidence; the native architecture view projects elements only");
        }
    }

    /** View-scoped native occurrences refer to connector GUIDs, never duplicate semantic relations. */
    private void diagramLinks(List<Row> rows) {
        Set<String> identities = new HashSet<>();
        for (Row row : rows) {
            Draft view = diagrams.get(row.number("diagramid"));
            ConnectorDraft connector = connectors.get(row.number("connectorid"));
            if (view == null || connector == null) {
                loss(model, "t_diagramlinks:" + row.text("diagramid") + ":" + row.text("connectorid"),
                        "QEA_UNRESOLVED_REFERENCE", LossDisposition.UNSUPPORTED,
                        "Diagram link owner or connector is absent; preserve the original source copy");
                continue;
            }
            String identity = row.text("instance_id").isBlank() ? "connector-" + connector.id : Long.toString(row.id("instance_id"));
            if (!identities.add(view.id + ":" + identity))
                throw fail("QEA_DUPLICATE_IDENTITY", "Repeated diagram link needs a distinct native Instance_ID");
            evidence(new EvidenceTarget(view.id, view.attributes, view.extensions), row, identity);
            view.attributes.put("ea:t_diagramlinks:" + identity + ":connectorGuid", connector.id);
            loss(view.id, "t_diagramlinks:" + identity, "QEA_DIAGRAM_LINK_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "Connector identity, visibility and routing are view-scoped native evidence; edits require an explicit link-aware writer");
        }
    }

    private void loss(String artifact, String field, String code, LossDisposition disposition, String detail) {
        losses.add(new MappingLoss(artifact, field, code, disposition, detail));
    }
}
