// SPDX-License-Identifier: Apache-2.0
// Selected mapping rules adapted from AAroN ObjectProcessor, commit
// 490c8538c6bf94bd78a9c90cc53c0163f23dd1a4. See META-INF/third-party/aaron.
// Taxonomy uses stable evidence references and omits redundant inverse parent aliases.
package com.taxonomy.plugins.sparx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class QeaImplicitReferences {
    record Reference(String type, String field, String value, boolean guid, boolean incoming) { }
    private QeaImplicitReferences() { }

    static List<Reference> from(QeaDatabase.Row row) {
        var result = new ArrayList<Reference>();
        String type = row.text("object_type");
        if (type.equals("Action")) add(result, row, "BEHAVIOUR", "pdata1");
        if (Set.of("Object", "Action").contains(type)) add(result, row, "CLASSIFIER", "classifier_guid");
        if (Set.of("Object", "Part", "Port").contains(type)) add(result, row, "INSTANCE_OF", "pdata1");
        if (Set.of("Part", "Port").contains(type)) add(result, row, "REUSAGE", "pdata3");
        if (row.number("parentid") > 0) result.add(new Reference(switch (type) {
            case "Port" -> "HAS_PORT";
            case "Part" -> "HAS_PART";
            default -> "EMBEDS";
        }, "parentid", row.text("parentid"), false, true));
        return List.copyOf(result);
    }
    private static void add(List<Reference> into, QeaDatabase.Row row, String type, String field) {
        if (!row.text(field).isBlank() && !row.text(field).equals("0")) into.add(new Reference(type, field, row.text(field), true, false));
    }
}
