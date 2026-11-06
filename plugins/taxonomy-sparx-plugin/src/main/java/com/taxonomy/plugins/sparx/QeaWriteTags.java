package com.taxonomy.plugins.sparx;

import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;
import com.taxonomy.sparx.mapping.SparxSemanticTypes;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Only selected tags and a single unambiguous Taxonomy stereotype declaration are writable. */
final class QeaWriteTags {
    private final Snapshot basis;
    private final QeaWriteDatabase database;
    private final UUID namespace;
    private final Set<String> auxiliaryGuids = new HashSet<>();

    QeaWriteTags(Snapshot basis, QeaWriteDatabase database, UUID namespace) {
        this.basis = basis; this.database = database; this.namespace = namespace;
        for (String table : List.of("t_objectproperties", "t_connectortag", "t_xref")) for (Row row : basis.rows(table)) {
            String guid = row.text(table.equals("t_xref") ? "xrefid" : "ea_guid");
            if (!guid.isBlank()) try { auxiliaryGuids.add(QeaIds.guid(guid)); }
            catch (QeaFailure opaque) { /* Tag helper GUIDs are raw evidence, not projected object identities. */ }
        }
    }

    void tags(long owner, String ownerGuid, boolean connector, Map<String, String> values) throws SQLException {
        String table = connector ? "t_connectortag" : "t_objectproperties", ownerColumn = connector ? "elementid" : "object_id";
        for (var entry : values.entrySet()) {
            List<Row> found = basis.rows(table).stream().filter(r -> r.number(ownerColumn) == owner && r.text("property").equals(entry.getKey())).toList();
            if (found.size() > 1) throw fail("QEA_AMBIGUOUS_TAG", "The selected tag name identifies multiple native rows");
            Row old = found.isEmpty() ? null : found.getFirst();
            String current = old == null ? null : old.text("value").equals("<memo>") ? old.text("notes") : old.text("value");
            if (Objects.equals(current, entry.getValue())) continue;
            if (old != null && (old.binaryFields().contains("value") || old.binaryFields().contains("notes")))
                throw fail("QEA_BINARY_FIELD_UNSUPPORTED", "A binary tagged value cannot be replaced by scalar evidence");
            boolean memo = old != null && old.text("value").equals("<memo>") || entry.getValue().length() > 255;
            if (old == null) {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("propertyid", database.next(table, "propertyid")); fields.put(ownerColumn, owner);
                fields.put("property", entry.getKey()); fields.put("value", memo ? "<memo>" : entry.getValue());
                if (memo) fields.put("notes", entry.getValue());
                else if (database.hasColumn(table, "notes")) fields.put("notes", "");
                if (database.hasColumn(table, "ea_guid")) fields.put("ea_guid", auxiliary("tag:" + ownerGuid + ":" + entry.getKey()));
                database.insert(table, fields);
            } else {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("value", memo ? "<memo>" : entry.getValue());
                if (memo) fields.put("notes", entry.getValue());
                database.update(table, "propertyid", old.id("propertyid"), fields);
            }
        }
    }

    void stereotype(String ownerGuid, String canonical, boolean connector) throws SQLException {
        String owner = QeaIds.guid(ownerGuid);
        List<Row> found = basis.rows("t_xref").stream().filter(r -> r.text("name").equalsIgnoreCase("Stereotypes")
                && sameGuid(r.text("client"), owner)).toList();
        if (found.size() > 1) throw fail("QEA_STEREOTYPE_UNSUPPORTED", "Multiple stereotype cross references require explicit native editing");
        String description = "@STEREO;Name=" + canonical + ";FQName=" + SparxSemanticTypes.taxonomyStereotype(canonical) + ";@ENDSTEREO;";
        String kind = connector ? "connector property" : "element property";
        if (found.isEmpty()) {
            database.insert("t_xref", Map.of("xrefid", auxiliary("stereotype:" + owner), "name", "Stereotypes", "type", kind,
                    "client", owner, "description", description));
        } else {
            Row old = found.getFirst();
            if (old.text("description").equals(description) && old.text("type").equalsIgnoreCase(kind)) return;
            if (!old.text("type").equalsIgnoreCase(kind) || !ownedStereotype(old.text("description")))
                throw fail("QEA_STEREOTYPE_UNSUPPORTED", "A mixed or foreign stereotype cross reference cannot be overwritten");
            if (old.binaryFields().contains("description")) throw fail("QEA_BINARY_FIELD_UNSUPPORTED", "A binary cross reference cannot be replaced");
            database.update("t_xref", "xrefid", old.text("xrefid"), Map.of("description", description));
        }
    }
    static void checkExistingStereotype(String stereotype) {
        if (stereotype == null || stereotype.isBlank()) return;
        String canonical = stereotype.startsWith("Taxonomy::") ? stereotype.substring(10) : stereotype;
        if (!SparxSemanticTypes.elementTypes().containsKey(canonical) && !SparxSemanticTypes.relationTypes().containsKey(canonical))
            throw fail("QEA_STEREOTYPE_UNSUPPORTED", "A foreign or mixed stereotype requires an explicit native mapping");
    }
    private static boolean ownedStereotype(String description) {
        if (!description.startsWith("@STEREO;") || !description.endsWith("@ENDSTEREO;")
                || description.indexOf("@STEREO;", 1) >= 0) return false;
        var values = new HashMap<String, String>();
        for (String item : description.substring(8, description.length() - 11).split(";")) {
            int equal = item.indexOf('='); if (equal < 1) continue;
            String key = item.substring(0, equal);
            if (!Set.of("Name", "FQName").contains(key) || values.put(key, item.substring(equal + 1)) != null) return false;
        }
        String qualified = values.getOrDefault("FQName", ""), name = values.getOrDefault("Name", "");
        if (!qualified.equals("Taxonomy::" + name)) return false;
        return SparxSemanticTypes.elementTypes().containsKey(name) || SparxSemanticTypes.relationTypes().containsKey(name);
    }
    private static boolean sameGuid(String text, String guid) {
        try { return QeaIds.guid(text).equals(guid); } catch (QeaFailure invalid) { return false; }
    }
    private String auxiliary(String purpose) {
        String guid = QeaIds.guid(UUID.nameUUIDFromBytes((namespace + "\0" + purpose).getBytes(StandardCharsets.UTF_8)).toString());
        if (!auxiliaryGuids.add(guid)) throw fail("QEA_DUPLICATE_IDENTITY", "A new helper identity collides with an existing native row");
        return guid;
    }
}
