package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Existing diagram layout is opaque. New occurrences have explicit, bounded native rectangles. */
final class QeaWritePlacements {
    private static final Set<String> LAYOUT = Set.of("rectleft", "rectright", "recttop", "rectbottom", "sequence", "objectstyle", "instance_id");
    private final Snapshot basis;
    private final Map<String, Placement> before = new HashMap<>();
    private final QeaWriteModel model;
    private final QeaWriteDatabase database;
    private final List<MappingLoss> losses;
    private final Set<Long> allocated = new HashSet<>();
    private final Set<String> withoutInstance = new HashSet<>();

    QeaWritePlacements(Snapshot basis, ExchangeDocument before, QeaWriteModel model, QeaWriteDatabase database, List<MappingLoss> losses) {
        this.basis = basis; this.model = model; this.database = database; this.losses = losses;
        before.placements().forEach(p -> this.before.put(p.id(), p));
        for (Row row : basis.rows("t_diagramobjects")) {
            if (!row.text("instance_id").isBlank()) allocated.add(row.id("instance_id"));
            else withoutInstance.add(row.number("diagram_id") + ":" + row.number("object_id"));
        }
    }

    void write(List<Placement> selected) throws SQLException {
        for (Placement placement : selected) {
            if (placement.attributes().containsKey("operation"))
                throw fail("QEA_OPERATION_UNSUPPORTED", "Explicit occurrence operations (including deletion) are unsupported; omission never authorizes deletion");
        }
        // Reserve the whole selection before automatic allocation can consume a later frozen identity.
        Set<Long> frozenInstances = new HashSet<>();
        if (database.hasColumn("t_diagramobjects", "instance_id")) for (Placement placement : selected) {
            if (placement.containerId().equals(model.model) || before.containsKey(placement.id())) continue;
            String frozen = placement.attributes().get("ea:t_diagramobjects:instance_id");
            if (frozen != null && !frozen.isBlank()) frozenInstances.add(number(frozen, true));
        }
        for (Placement placement : selected.stream().sorted(Comparator.comparing(Placement::id)).toList()) {
            if (placement.containerId().equals(model.model)) continue;
            String view = QeaWriteModel.reference(placement.containerId()), member = QeaWriteModel.reference(placement.artifactId());
            if (model.kind(view) != ArtifactKind.VIEW || placement.parentId() != null && !placement.parentId().isEmpty()
                    || placement.attributes().containsKey("placementKind") && !placement.attributes().get("placementKind").equals("diagram"))
                throw fail("QEA_SELECTION_DEPENDENCY", "A diagram occurrence needs a selected or existing view, member and no package parent");
            long diagramId = model.nativeObjects.get(view).id(), objectId = model.objectId(member);
            Placement old = before.get(placement.id());
            if (old != null) {
                if (!old.containerId().equals(view) || !old.artifactId().equals(member))
                    throw fail("QEA_IDENTITY_CONFLICT", "An existing diagram occurrence cannot be reassigned to another owner or member");
                checkExistingLayout(placement, old.attributes());
                continue;
            }
            var values = new LinkedHashMap<String, Object>(); values.put("diagram_id", diagramId); values.put("object_id", objectId);
            String frozenInstance = placement.attributes().get("ea:t_diagramobjects:instance_id");
            if (database.hasColumn("t_diagramobjects", "instance_id")) {
                long instance = frozenInstance == null || frozenInstance.isBlank() ? automaticInstance(frozenInstances) : number(frozenInstance, true);
                if (!allocated.add(instance)) {
                    Row existing = basis.rows("t_diagramobjects").stream().filter(r -> !r.text("instance_id").isBlank() && r.id("instance_id") == instance).findFirst().orElse(null);
                    if (existing == null || existing.number("diagram_id") != diagramId || existing.number("object_id") != objectId)
                        throw fail("QEA_DUPLICATE_IDENTITY", "A frozen diagram instance identity belongs to another occurrence");
                    checkExistingLayout(placement, existing.evidence()); continue;
                }
                values.put("instance_id", instance);
            } else {
                if (frozenInstance != null && !frozenInstance.isBlank()) throw fail("QEA_SCHEMA_UNSUPPORTED", "The template cannot store frozen diagram instance identities");
                if (!withoutInstance.add(diagramId + ":" + objectId)) throw fail("QEA_DUPLICATE_IDENTITY", "Repeated diagram membership requires native Instance_ID support");
            }
            long left = coordinate(placement, "rectleft", 10 + (placement.position() % 5L) * 190);
            long top = coordinate(placement, "recttop", -20 - (placement.position() / 5L) * 120);
            long right = coordinate(placement, "rectright", left + 160), bottom = coordinate(placement, "rectbottom", top - 80);
            if (right <= left || bottom >= top) throw fail("QEA_LAYOUT_UNSUPPORTED", "A new native rectangle requires positive width and height in EA coordinates");
            values.put("rectleft", left); values.put("rectright", right); values.put("recttop", top); values.put("rectbottom", bottom);
            values.put("sequence", placement.attributes().containsKey("ea:t_diagramobjects:sequence")
                    ? number(placement.attributes().get("ea:t_diagramobjects:sequence"), false) : placement.position());
            if (database.hasColumn("t_diagramobjects", "objectstyle")) values.put("objectstyle", placement.attributes().getOrDefault("ea:t_diagramobjects:objectstyle", ""));
            database.insert("t_diagramobjects", values);
            losses.add(new MappingLoss(placement.id(), "layout", "QEA_NEW_DIAGRAM_LAYOUT", LossDisposition.TRANSFORMED,
                    "The new occurrence uses its frozen native rectangle or a simple deterministic grid; existing geometry and routing are unchanged"));
        }
    }
    private long automaticInstance(Set<Long> frozenInstances) throws SQLException {
        long instance;
        do { instance = database.next("t_diagramobjects", "instance_id"); }
        while (allocated.contains(instance) || frozenInstances.contains(instance));
        return instance;
    }
    private static void checkExistingLayout(Placement placement, Map<String, String> prior) {
        for (String field : LAYOUT) {
            String value = placement.attributes().get("ea:t_diagramobjects:" + field);
            if (value != null && !value.equals(prior.get("ea:t_diagramobjects:" + field)))
                throw fail("QEA_LAYOUT_UNSUPPORTED", "Changing an existing native diagram layout requires an explicit layout-aware writer");
        }
    }
    private static long coordinate(Placement placement, String field, long fallback) {
        String value = placement.attributes().get("ea:t_diagramobjects:" + field);
        if (value == null) return fallback;
        try { long number = Long.parseLong(value); if (number < -1_000_000 || number > 1_000_000) throw new NumberFormatException(); return number; }
        catch (NumberFormatException invalid) { throw fail("QEA_LAYOUT_UNSUPPORTED", "Native diagram coordinates must be bounded integers"); }
    }
    private static long number(String value, boolean positive) {
        try { long number = Long.parseLong(value); if (number < (positive ? 1 : 0) || number > Integer.MAX_VALUE) throw new NumberFormatException(); return number; }
        catch (NumberFormatException invalid) { throw fail("QEA_REFERENCE_INVALID", "Native instance and sequence identities must be bounded integers"); }
    }
}
