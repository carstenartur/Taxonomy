package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.AdmbwQeaExport;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Connector;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Delivery;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Element;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.View;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeFile;
import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Adds the checked views to a copy. Catalogue rows and existing project content are only read. */
final class AdmbwQeaWriter {
    private static final String LINK_GEOMETRY = "SX=0;SY=0;EX=0;EY=0;EDGE=2;$LLB=;LLT=;LMT=;LMB=;LRT=;LRB=;IRHS=;ILHS=;";

    ExchangeFile write(Delivery delivery, String rootPackageGuid, byte[] basis) {
        if (!delivery.projectCode().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}") || !delivery.commit().matches("[a-f0-9]{40}"))
            throw fail("QEA_ADMBW_SOURCE", "An exact Git checkpoint and a valid project code are required");
        if (!delivery.views().stream().map(View::viewpoint).toList().equals(List.of("C1-S1", "R7")))
            throw fail("QEA_ADMBW_VIEW", "Delivery requires the checked C1-S1 and R7 views");
        long itemCount = delivery.views().stream().mapToLong(v -> (long) v.elements().size() + v.connectors().size()).sum();
        if (itemCount > QeaDatabase.MAX_ROWS) throw fail("QEA_LIMIT", "The selected delivery exceeds the supported item budget");

        String root = QeaIds.guid(rootPackageGuid, "QEA_ADMBW_ROOT");
        var snapshot = new QeaDatabase().readAdmbw(basis);
        var roots = snapshot.rows("t_package").stream().filter(r -> matchesGuid(r, "ea_guid", root)).toList();
        if (roots.size() != 1 || roots.getFirst().number("parent_id") != 0)
            throw fail("QEA_ADMBW_ROOT", "Select the GUID of one existing model root package in the QEA basis");
        var projects = snapshot.rows("usys_system").stream().filter(r -> r.text("property").equalsIgnoreCase("ProjectGUID")).toList();
        if (projects.size() != 1) throw fail("QEA_MODEL_IDENTITY", "The basis needs a unique EA project GUID");
        QeaIds.guid(projects.getFirst().text("value"), "QEA_MODEL_IDENTITY");
        UUID namespace = stableUuid(snapshot.sha256() + "\0" + root + "\0" + delivery.projectCode() + "\0" + delivery.commit());
        byte[] content = QeaWriteDatabase.writeCopy(basis, true, database -> {
            var copy = new DeliveryCopy(snapshot, database, namespace);
            for (View view : delivery.views()) copy.writeView(view, roots.getFirst().id("package_id"), delivery.commit());
        });
        return new ExchangeFile(AdmbwQeaExport.MEDIA_TYPE, delivery.projectCode() + "-admbw.qea", content, List.of());
    }

    private record PackageKey(long parent, String name) { }
    private record Occurrence(long objectId, String duid) { }

    private static final class DeliveryCopy {
        private final Snapshot basis;
        private final QeaWriteDatabase database;
        private final QeaWriteTags tags;
        private final UUID namespace;
        private final Map<PackageKey, List<Long>> packages = new HashMap<>();
        private final Map<String, List<Row>> objects = new HashMap<>();
        private final Map<String, List<Row>> stereotypes = new HashMap<>();
        private final Set<String> guids = new HashSet<>();
        private final Set<Long> referencedObjectIds = new HashSet<>();
        private final Set<Long> referencedConnectorIds = new HashSet<>();

        DeliveryCopy(Snapshot basis, QeaWriteDatabase database, UUID namespace) {
            this.basis = basis;
            this.database = database;
            this.namespace = namespace;
            tags = new QeaWriteTags(basis, database, namespace);
            for (Row row : basis.rows("t_package")) {
                var key = new PackageKey(row.number("parent_id"), row.text("name").toLowerCase(Locale.ROOT));
                packages.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row.id("package_id"));
            }
            for (Row row : basis.rows("t_object")) {
                objects.computeIfAbsent(key(row.text("ea_guid")), ignored -> new ArrayList<>()).add(row);
                referencedObjectIds.add(row.number("parentid"));
            }
            for (Row row : basis.rows("t_objectproperties")) referencedObjectIds.add(row.number("object_id"));
            for (Row row : basis.rows("t_diagramobjects")) referencedObjectIds.add(row.number("object_id"));
            for (Row row : basis.rows("t_connector")) {
                referencedObjectIds.add(row.number("start_object_id"));
                referencedObjectIds.add(row.number("end_object_id"));
            }
            for (Row row : basis.rows("t_connectortag")) referencedConnectorIds.add(row.number("elementid"));
            for (Row row : basis.rows("t_xref")) if (row.text("name").equalsIgnoreCase("Stereotypes"))
                stereotypes.computeIfAbsent(key(row.text("client")), ignored -> new ArrayList<>()).add(row);
            for (String table : List.of("t_package", "t_object", "t_diagram", "t_connector", "t_objectproperties", "t_xref")) {
                for (Row row : basis.rows(table))
                    guids.add(key(row.text(table.equals("t_xref") ? "xrefid" : "ea_guid")));
            }
        }

        void writeView(View view, long rootId, String commit) throws SQLException {
            long packageId = packagePath(rootId, view.packagePath());
            for (Row row : basis.rows("t_diagram")) {
                if (row.number("package_id") == packageId && row.text("name").equalsIgnoreCase(view.name()))
                    throw fail("QEA_ADMBW_DIAGRAM_EXISTS", "This diagram already exists in the target package. Select the original QEA basis for a fresh delivery");
            }
            long diagramId = database.next("t_diagram", "diagram_id");
            database.insert("t_diagram", Map.of("diagram_id", diagramId, "package_id", packageId,
                    "ea_guid", guid("diagram:" + view.viewpoint()), "name", view.name(), "diagram_type", "Custom",
                    "styleex", "MDGDgm=" + AdmbwQeaProfile.DIAGRAM_TYPES.get(view.viewpoint()) + ";",
                    "notes", "Taxonomy Git checkpoint " + commit + "\nADMBw 2026.05 / FFFmLV 2.0\nManual review required"));
            var elements = new HashMap<String, Element>();
            var occurrences = new HashMap<String, Occurrence>();
            var byObject = new HashMap<Long, Occurrence>();
            int index = 0;
            for (Element element : view.elements()) {
                AdmbwQeaProfile.validateElement(view.viewpoint(), element);
                if (elements.putIfAbsent(element.ref(), element) != null)
                    throw fail("QEA_ADMBW_ELEMENT", "An ADMBw viewpoint contains a duplicate element reference");
                long objectId = element.catalogueGuid() == null ? newElement(element, packageId) : existingElement(element);
                Occurrence occurrence = place(diagramId, objectId, index++);
                if (byObject.putIfAbsent(objectId, occurrence) != null)
                    throw fail("QEA_ADMBW_CATALOGUE", "Multiple viewpoint elements refer to the same catalogue GUID");
                occurrences.put(element.ref(), occurrence);
            }

            var relationships = new HashSet<Connector>();
            for (var connector : view.connectors()) {
                if (!relationships.add(connector)) throw fail("QEA_ADMBW_RELATION", "An ADMBw viewpoint contains a duplicate connector");
                String type = AdmbwQeaProfile.connectorType(connector, elements.get(connector.client()), elements.get(connector.supplier()));
                var from = occurrences.get(connector.client());
                var to = occurrences.get(connector.supplier());
                long id;
                do { id = database.next("t_connector", "connector_id"); }
                while (referencedConnectorIds.contains(id));
                String guid = guid("connector:" + view.viewpoint() + ":" + id);
                database.insert("t_connector", Map.of("connector_id", id, "ea_guid", guid, "connector_type", type,
                        "stereotype", connector.stereotype(), "start_object_id", from.objectId(), "end_object_id", to.objectId(),
                        "direction", "Source -> Destination"));
                tags.newAdmbwStereotype(guid, connector.stereotype(), true);
                link(diagramId, id, from, to, false);
            }
            // Existing links between reused catalogue members may be shown by EA unless hidden on the new view.
            for (Row row : basis.rows("t_connector")) {
                var from = byObject.get(row.number("start_object_id"));
                var to = byObject.get(row.number("end_object_id"));
                if (from != null && to != null) link(diagramId, row.id("connector_id"), from, to, true);
            }
        }

        private long packagePath(long parent, List<String> path) throws SQLException {
            for (String name : path) {
                var key = new PackageKey(parent, name.toLowerCase(Locale.ROOT));
                var existing = packages.getOrDefault(key, List.of());
                if (existing.size() > 1) throw fail("QEA_ADMBW_PACKAGE", "A target package name is ambiguous in the supplied QEA basis");
                if (!existing.isEmpty()) {
                    parent = existing.getFirst();
                    continue;
                }
                long id = database.next("t_package", "package_id");
                String guid = guid("package:" + id);
                database.insert("t_package", Map.of("package_id", id, "parent_id", parent, "ea_guid", guid, "name", name));
                database.insert("t_object", Map.of("object_id", allocateObject(),
                        "object_type", "Package", "package_id", parent, "parentid", 0, "pdata1", Long.toString(id),
                        "ea_guid", guid, "name", name));
                packages.put(key, List.of(id));
                parent = id;
            }
            return parent;
        }

        private long existingElement(Element element) {
            String guid = QeaIds.guid(element.catalogueGuid(), "QEA_ADMBW_CATALOGUE");
            var matches = objects.getOrDefault(guid, List.of());
            if (matches.size() != 1)
                throw fail("QEA_ADMBW_CATALOGUE", "The QEA basis must contain exactly one element with catalogue GUID " + guid);
            Row row = matches.getFirst();
            String nativeType = AdmbwQeaProfile.ELEMENT_TYPES.get(element.stereotype());
            if (!row.text("object_type").equals(nativeType) || !row.text("stereotype").equals(element.stereotype())
                    || stereotypes.getOrDefault(guid, List.of()).stream().noneMatch(x -> x.text("type").equals("element property")
                    && x.text("description").contains(QeaWriteTags.admbwDescription(element.stereotype()))))
                throw fail("QEA_ADMBW_CATALOGUE", "Catalogue GUID " + guid + " has the wrong native type or ADMBw stereotype");
            long id = row.id("object_id");
            if (nativeType.equals("Part")) {
                long parentId = row.number("parentid");
                if (parentId == id || basis.rows("t_object").stream().noneMatch(parent -> parent.id("object_id") == parentId))
                    throw fail("QEA_ADMBW_PART_OWNER", "The selected EA Part " + guid + " needs an existing owning element");
            }
            return id;
        }

        private long allocateObject() throws SQLException {
            // Reserve IDs mentioned in existing parent, tag or connector rows, including dangling references.
            long id;
            do { id = database.next("t_object", "object_id"); }
            while (referencedObjectIds.contains(id));
            return id;
        }

        private long newElement(Element element, long packageId) throws SQLException {
            long id = allocateObject();
            String guid = guid("element:" + element.ref());
            database.insert("t_object", Map.of("object_id", id, "object_type", AdmbwQeaProfile.ELEMENT_TYPES.get(element.stereotype()),
                    "package_id", packageId, "parentid", 0, "ea_guid", guid, "name", element.name(),
                    "note", element.notes(), "stereotype", element.stereotype()));
            tags.newAdmbwStereotype(guid, element.stereotype(), false);
            if (AdmbwQeaProfile.requirement(element.stereotype())) {
                var evidence = new LinkedHashMap<String, String>();
                evidence.put("Taxonomy.SourceRef", element.ref());
                evidence.put("Taxonomy.RequirementVersion", element.requirementVersion().toString());
                evidence.put("Taxonomy.RequirementContentSha256", element.requirementContentHash());
                tags.tags(id, guid, false, evidence);
            }
            return id;
        }

        private Occurrence place(long diagramId, long objectId, int position) throws SQLException {
            long instance = database.next("t_diagramobjects", "instance_id");
            String duid = String.format(Locale.ROOT, "%08X", instance);
            long left = 30 + position % 4L * 240, top = -30 - position / 4L * 140;
            database.insert("t_diagramobjects", Map.of("diagram_id", diagramId, "object_id", objectId,
                    "instance_id", instance, "objectstyle", "DUID=" + duid + ";",
                    "rectleft", left, "rectright", left + 200, "recttop", top, "rectbottom", top - 100,
                    "sequence", position));
            return new Occurrence(objectId, duid);
        }

        private void link(long diagramId, long connectorId, Occurrence from, Occurrence to, boolean hidden) throws SQLException {
            database.insert("t_diagramlinks", Map.of("diagramid", diagramId, "connectorid", connectorId,
                    "instance_id", database.next("t_diagramlinks", "instance_id"), "hidden", hidden ? 1 : 0,
                    "geometry", LINK_GEOMETRY, "style", "Mode=2;EOID=" + to.duid() + ";SOID=" + from.duid() + ";Color=-1;LWidth=0;"));
        }

        private String guid(String purpose) {
            String guid = QeaIds.guid(stableUuid(namespace + "\0" + purpose).toString());
            if (!guids.add(guid)) throw fail("QEA_DUPLICATE_IDENTITY", "A generated GUID collides with another native row");
            return guid;
        }
    }

    private static UUID stableUuid(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }

    private static String key(String value) {
        String upper = value.strip().toUpperCase(Locale.ROOT);
        return upper.length() == 36 ? "{" + upper + "}" : upper;
    }

    private static boolean matchesGuid(Row row, String field, String guid) { return key(row.text(field)).equals(guid); }
}
