package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.AdmbwQeaExport.Basis;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Reference;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Root;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.ServiceSpecification;
import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;
import static com.taxonomy.plugins.sparx.QeaIds.key;

/** A reference is selectable only under an identified model root in these exact basis bytes. */
final class AdmbwQeaReferences {
    private final Snapshot basis;
    private final Map<Long, Location> locations = new HashMap<>();
    private final Map<Long, Row> packages;
    private final Map<Long, Row> objects;
    private final Map<String, Integer> objectGuids;
    private final Map<String, List<Row>> stereotypes = new HashMap<>();

    AdmbwQeaReferences(Snapshot basis) {
        this.basis = basis;
        packages = byId(basis.rows("t_package"), "package_id");
        objects = byId(basis.rows("t_object"), "object_id");
        objectGuids = countGuids(basis.rows("t_object"));
        for (Row xref : basis.rows("t_xref")) {
            if ("Stereotypes".equalsIgnoreCase(xref.text("name"))) {
                stereotypes.computeIfAbsent(key(xref.text("client")), ignored -> new ArrayList<>()).add(xref);
            }
        }
    }

    Basis inspect() {
        List<Row> projects = basis.rows("usys_system").stream()
                .filter(row -> "ProjectGUID".equalsIgnoreCase(row.text("property"))).toList();
        if (projects.size() != 1 || !QeaIds.original(key(projects.getFirst().text("value"))))
            throw fail("QEA_MODEL_IDENTITY", "The basis must contain exactly one original nonzero EA project GUID");
        List<Root> roots = new ArrayList<>();
        var packageGuids = countGuids(basis.rows("t_package"));
        for (Row pkg : packages.values()) {
            String guid = key(pkg.text("ea_guid"));
            if (modelRoot(pkg) && QeaIds.original(guid)) {
                if (packageGuids.get(guid) != 1) throw fail("QEA_ADMBW_ROOT", "Ambiguous model root GUID");
                roots.add(new Root(guid, name(pkg)));
            }
        }
        if (roots.isEmpty()) throw fail("QEA_ADMBW_ROOT", "The basis contains no selectable model root");
        roots.sort(Comparator.comparing(Root::guid));
        List<Reference> references = new ArrayList<>();
        List<ServiceSpecification> specifications = new ArrayList<>();
        Map<Long, List<Row>> sourceTags = new HashMap<>();
        for (Row tag : basis.rows("t_objectproperties")) {
            sourceTags.computeIfAbsent(tag.id("object_id"), ignored -> new ArrayList<>()).add(tag);
        }
        long pathCharacters = 0;
        for (Row object : objects.values()) {
            String type = object.text("stereotype");
            String guid = key(object.text("ea_guid"));
            boolean specification = "ServiceSpecification".equals(type);
            if (!(specification || AdmbwQeaProfile.REFERENCE_TYPES.contains(type))
                    || !AdmbwQeaProfile.nativeIdentity(object, type, stereotypes.getOrDefault(guid, List.of()))) continue;
            var location = location(object);
            // Disconnected example/test packages can have placeholder root identifiers.
            if (!QeaIds.original(location.rootGuid())) continue;
            uniqueObjectGuid(object);
            for (String segment : location.names()) pathCharacters += segment.length() + 1L;
            if (pathCharacters > QeaDatabase.MAX_TEXT)
                throw fail("QEA_LIMIT", "The reference inventory exceeds the supported package-path budget");
            if (specification) {
                var tags = sourceTags.getOrDefault(object.id("object_id"), List.of());
                String source = tags.size() == 1 ? tags.getFirst().text("value").strip() : null;
                specifications.add(new ServiceSpecification(guid, name(object), location.names(), location.rootGuid(), source));
                continue;
            }
            String ownerGuid = "Part".equals(object.text("object_type")) ? ownerGuid(object, location.rootGuid()) : null;
            references.add(new Reference(guid, type, name(object), object.text("note"), location.names(), location.rootGuid(), ownerGuid));
        }
        references.sort(Comparator.comparing(Reference::guid));
        specifications.sort(Comparator.comparing(ServiceSpecification::guid));
        return new Basis(basis.sha256(), key(projects.getFirst().text("value")), roots, references, specifications);
    }

    private String ownerGuid(Row part, String rootGuid) {
        var visited = new HashSet<Long>();
        visited.add(part.id("object_id"));
        long ownerId = part.number("parentid");
        String immediate = null;
        do {
            Row owner = objects.get(ownerId);
            if (owner == null || !visited.add(ownerId) || !location(owner).rootGuid().equals(rootGuid))
                throw fail("QEA_ADMBW_PART_OWNER", "An EA Part needs an acyclic chain of owners under the same model root");
            if (visited.size() > QeaDatabase.MAX_DEPTH)
                throw fail("QEA_LIMIT", "The reference ownership hierarchy exceeds the supported depth");
            String guid = uniqueObjectGuid(owner);
            if (immediate == null) immediate = guid;
            ownerId = owner.number("parentid");
        } while (ownerId != 0);
        return immediate;
    }

    private record Location(String rootGuid, List<String> names) { }

    private Location location(Row object) {
        return locations.computeIfAbsent(object.id("package_id"), this::packageLocation);
    }

    private Location packageLocation(long id) {
        var visited = new HashSet<Long>();
        var path = new ArrayList<String>();
        while (visited.size() < QeaDatabase.MAX_DEPTH) {
            Row pkg = packages.get(id);
            if (pkg == null || !visited.add(id))
                throw fail("QEA_REFERENCE_INVALID", "Reference package ancestry is missing or cyclic");
            path.add(name(pkg));
            if (modelRoot(pkg)) {
                Collections.reverse(path);
                return new Location(key(pkg.text("ea_guid")), path);
            }
            id = pkg.id("parent_id");
        }
        throw fail("QEA_LIMIT", "The reference package hierarchy exceeds the supported depth");
    }

    private String uniqueObjectGuid(Row row) {
        String guid = key(row.text("ea_guid"));
        if (!QeaIds.original(guid) || objectGuids.get(guid) != 1)
            throw fail("QEA_ADMBW_CATALOGUE", "A reference needs a unique original nonzero EA object GUID");
        return guid;
    }

    private static boolean modelRoot(Row row) {
        return !row.text("parent_id").isEmpty() && row.number("parent_id") == 0;
    }

    private static String name(Row row) {
        if (row.text("name").isBlank()) throw fail("QEA_REFERENCE_INVALID", "A reference or its package has no name");
        return row.text("name");
    }

    private static Map<Long, Row> byId(List<Row> rows, String field) {
        Map<Long, Row> result = new HashMap<>();
        for (Row row : rows) {
            if (result.putIfAbsent(row.id(field), row) != null)
                throw fail("QEA_REFERENCE_INVALID", "Ambiguous native row identity in " + row.table());
        }
        return result;
    }

    private static Map<String, Integer> countGuids(List<Row> rows) {
        Map<String, Integer> result = new HashMap<>();
        for (Row row : rows) result.merge(key(row.text("ea_guid")), 1, Integer::sum);
        return result;
    }
}
