package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.plugins.sparx.QeaDatabase.Row;
import com.taxonomy.plugins.sparx.QeaDatabase.Snapshot;
import com.taxonomy.plugins.sparx.QeaWriteModel.NativeObject;
import com.taxonomy.sparx.mapping.SparxSemanticTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFailure.fail;

/** Changes only an owned copy of the exact reviewed basis. Missing objects never authorize deletion. */
final class QeaWriter {
    ExchangeFile write(BasedOutboundRequest request) {
        validateRequest(request);
        byte[] bytes = request.baseContent();
        if (!QeaDatabase.digest(bytes).equals(request.baseSha256())) throw fail("QEA_BASIS_CHANGED", "The supplied bytes do not match the frozen basis SHA-256");
        Snapshot basis = new QeaDatabase().read(bytes);
        ExchangeDocument before;
        Row originalRoot = null;
        if (request.mode() == FileExportMode.UPDATE_COPY) {
            before = new QeaReader("2").read(bytes, request.outbound().expectedExternalVersion());
            if (!before.metadata().get("identifier").equals(QeaIds.guid(request.outbound().document().metadata().get("identifier"))))
                throw fail("QEA_MODEL_IDENTITY", "UPDATE_COPY must retain the basis model identity");
        } else {
            validateTemplate(basis);
            originalRoot = basis.rows("t_package").getFirst();
            String root = QeaIds.guid(request.outbound().document().metadata().get("rootIdentifier"), "QEA_MODEL_IDENTITY");
            var rootValues = new HashMap<>(originalRoot.values()); rootValues.put("ea_guid", root);
            Row renamedRoot = new Row("t_package", Map.copyOf(rootValues), originalRoot.binaryFields());
            var tables = new HashMap<>(basis.tables()); tables.put("t_package", List.of(renamedRoot));
            basis = new Snapshot(Map.copyOf(tables), basis.omittedTables(), basis.sha256(), basis.bytes());
            before = new ExchangeDocument("sparx-qea", "2", null, false, "Empty template", List.of(new Artifact(root, ArtifactKind.SPECIFICATION,
                    "Package", renamedRoot.text("name"), renamedRoot.text("notes"), renamedRoot.evidence(), Map.of())), List.of(),
                    List.of(new Placement("package:" + root, request.outbound().document().metadata().get("identifier"), null, root, 0, Map.of("placementKind", "package"))),
                    request.outbound().document().metadata(), List.of());
        }
        var model = new QeaWriteModel(request, basis, before);
        Path directory = null;
        try {
            directory = Files.createTempDirectory("taxonomy-qea-write-");
            Path copy = directory.resolve("copy.qea"); Files.write(copy, bytes);
            boolean changed;
            var losses = new ArrayList<MappingLoss>();
            try (var database = new QeaWriteDatabase(copy)) {
                if (request.mode() == FileExportMode.NEW_PROJECT) database.validateEmptyTemplate();
                model.allocate(database);
                if (request.mode() == FileExportMode.NEW_PROJECT) {
                    List<Row> identities = basis.rows("usys_system").stream().filter(r -> r.text("property").equalsIgnoreCase("ProjectGUID")).toList();
                    if (identities.isEmpty()) database.insert("usys_system", Map.of("property", "ProjectGUID", "value", model.model));
                    else database.update("usys_system", "property", identities.getFirst().text("property"), Map.of("value", model.model));
                    database.update("t_package", "package_id", originalRoot.id("package_id"), Map.of("ea_guid", model.root));
                }
                var tags = new QeaWriteTags(basis, database, request.identityNamespace());
                List<Artifact> ordered = model.selected.values().stream().sorted(Comparator
                        .comparingInt((Artifact a) -> a.kind() == ArtifactKind.SPECIFICATION ? model.depth(a.id()) : QeaDatabase.MAX_DEPTH + 1)
                        .thenComparing(Artifact::id)).toList();
                for (Artifact artifact : ordered) artifact(artifact, model, database, tags);
                for (var entry : model.parents.entrySet()) {
                    if (!model.parentChanges.contains(entry.getKey())) continue;
                    NativeObject owner = model.nativeObjects.get(entry.getKey());
                    if (owner == null || owner.original() == null) continue;
                    String column = owner.kind() == ArtifactKind.SPECIFICATION ? "parent_id" : "package_id";
                    var fields = delta(owner.original(), Map.of(column, model.packageId(entry.getKey())));
                    database.update(owner.table(), owner.key(), owner.id(), fields);
                    Row mirror = model.mirrors.get(entry.getKey());
                    if (mirror != null) database.update("t_object", "object_id", mirror.id("object_id"), delta(mirror, Map.of("package_id", model.packageId(entry.getKey()))));
                }
                for (Relation relation : model.relations.values()) relation(relation, model, database, tags);
                new QeaWritePlacements(basis, before, model, database, losses).write(request.outbound().document().placements());
                changed = database.dirty(); database.commit();
            }
            if (Files.size(copy) > QeaDatabase.MAX_BYTES) throw fail("QEA_LIMIT", "The native QEA output exceeds 32 MiB");
            byte[] result = changed ? Files.readAllBytes(copy) : bytes;
            ExchangeDocument verified = new QeaReader("2").read(result, null);
            if (!verified.metadata().get("identifier").equals(model.model)) throw fail("QEA_WRITE_CONFLICT", "Native read-back changed the reviewed model identity");
            verifyReviewed(model, verified);
            losses.add(new MappingLoss(model.model, "scope", "QEA_COPY_PRESERVED", LossDisposition.PRESERVED_EXTENSION,
                    "The supplied SQLite copy retains unselected rows, unknown data and native layout; no missing item authorizes deletion"));
            return new ExchangeFile(SparxQeaWriterConnector.MEDIA_TYPE, "taxonomy-" + (request.mode() == FileExportMode.NEW_PROJECT ? "project" : "updated-copy") + ".qea", result, losses);
        } catch (SQLException invalid) { throw fail("QEA_WRITE_REJECTED", "Native serialization failed schema, reference, work-budget or size validation; no file was delivered"); }
        catch (IOException unavailable) { throw fail("QEA_WRITE_UNAVAILABLE", "Unable to create or read the owned temporary QEA output"); }
        finally {
            if (directory != null) try {
                for (String name : List.of("copy.qea-journal", "copy.qea-wal", "copy.qea-shm", "copy.qea")) Files.deleteIfExists(directory.resolve(name));
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) { throw fail("QEA_WRITE_UNAVAILABLE", "Unable to remove the owned temporary QEA output"); }
        }
    }

    private static void validateRequest(BasedOutboundRequest request) {
        if (request == null || request.outbound().context() == null || request.outbound().document() == null)
            throw fail("QEA_PROFILE", "A reviewed based export request is required");
        var context = request.outbound().context(); var document = request.outbound().document();
        if (!context.profile().equals("sparx-qea") || !context.profileVersion().equals("2") || !document.profile().equals("sparx-qea") || !document.profileVersion().equals("2"))
            throw fail("QEA_PROFILE", "Native writing requires sparx-qea profile version 2");
        if (!SparxQeaWriterConnector.MEDIA_TYPES.contains(request.baseMediaType())) throw fail("QEA_MEDIA_TYPE", "A supported SQLite QEA basis is required");
        QeaIds.guid(document.metadata().get("identifier"), "QEA_MODEL_IDENTITY");
        if ((long) document.artifacts().size() + document.relations().size() + document.placements().size() > QeaDatabase.MAX_ROWS)
            throw fail("QEA_LIMIT", "The selected export exceeds the supported item budget");
        long text = 0;
        for (Artifact a : document.artifacts()) text += bounded(a.title()) + bounded(a.text()) + bounded(a.attributes()) + bounded(a.extensions());
        for (Relation r : document.relations()) text += bounded(r.attributes()) + bounded(r.extensions());
        for (Placement p : document.placements()) text += bounded(p.attributes());
        if (text > QeaDatabase.MAX_TEXT) throw fail("QEA_LIMIT", "The reviewed export exceeds the supported retained-text budget");
    }
    private static long bounded(Map<String, String> values) { long sum = 0; for (var entry : values.entrySet()) sum += bounded(entry.getKey()) + bounded(entry.getValue()); return sum; }
    private static int bounded(String value) {
        if (value != null && value.length() > QeaDatabase.MAX_CELL) throw fail("QEA_LIMIT", "A reviewed scalar exceeds the supported cell size");
        return value == null ? 0 : value.length();
    }
    private static void validateTemplate(Snapshot basis) {
        for (String table : List.of("t_object", "t_connector", "t_diagram", "t_diagramobjects", "t_objectproperties", "t_connectortag", "t_xref"))
            if (!basis.rows(table).isEmpty()) throw fail("QEA_TEMPLATE_NOT_EMPTY", "NEW_PROJECT requires an empty native template, without model objects, mirrors, tags or references");
        if (basis.rows("t_package").size() != 1 || basis.rows("t_package").getFirst().number("parent_id") != 0)
            throw fail("QEA_TEMPLATE_NOT_EMPTY", "The supported empty template has exactly one root package");
        Row root = basis.rows("t_package").getFirst(); root.id("package_id"); QeaIds.guid(root.text("ea_guid"));
        List<Row> identities = basis.rows("usys_system").stream().filter(r -> r.text("property").equalsIgnoreCase("ProjectGUID")).toList();
        if (identities.size() > 1) throw fail("QEA_MODEL_IDENTITY", "A template cannot have conflicting ProjectGUID declarations");
        if (identities.size() == 1) QeaIds.guid(identities.getFirst().text("value"), "QEA_MODEL_IDENTITY");
    }

    private static void artifact(Artifact artifact, QeaWriteModel model, QeaWriteDatabase database, QeaWriteTags tags) throws SQLException {
        NativeObject nativeObject = model.nativeObjects.get(artifact.id()); Row old = nativeObject.original();
        Artifact before = model.beforeArtifacts.get(artifact.id());
        if (old != null && unchanged(artifact, before)) return;
        var fields = new LinkedHashMap<String, Object>(); fields.put("name", artifact.title());
        fields.put(artifact.kind() == ArtifactKind.SPECIFICATION || artifact.kind() == ArtifactKind.VIEW ? "notes" : "note", artifact.text());
        fields.put(artifact.kind() == ArtifactKind.SPECIFICATION ? "parent_id" : "package_id", model.packageId(artifact.id()));
        String canonical = null;
        if (artifact.kind() == ArtifactKind.ELEMENT) {
            canonical = artifact.extensions().get("canonicalType");
            if (!SparxSemanticTypes.elementTypes().containsKey(Objects.toString(canonical, "")))
                throw fail("QEA_ELEMENT_MAPPING_REQUIRED", "A changed or new element needs a supported canonical type");
            String transport = SparxSemanticTypes.umlType(canonical);
            boolean retainedTransport = old != null && before != null && Objects.equals(before.extensions().get("canonicalType"), canonical)
                    && Objects.equals(before.type(), artifact.type()) && !QeaSemanticProfile.declared(artifact.attributes(), artifact.extensions());
            if (old == null && !transport.equals(artifact.type())) throw fail("QEA_ELEMENT_MAPPING_REQUIRED", "A new element must use its declared UML transport");
            String nativeType = retainedTransport ? old.text("object_type") : transport;
            fields.put("object_type", nativeType);
            if (!nativeType.equals(transport)) canonical = null; // A title edit does not silently retype an existing generic Node/System.
            if (canonical != null) {
                QeaWriteTags.checkExistingStereotype(old == null ? "" : old.text("stereotype"));
                fields.put("stereotype", SparxSemanticTypes.taxonomyStereotype(canonical));
            }
        } else if (artifact.kind() == ArtifactKind.REQUIREMENT) {
            if (!"Requirement".equals(artifact.type())) throw fail("QEA_KIND_UNSUPPORTED", "Requirements require the native Requirement type");
            fields.put("object_type", "Requirement");
        }
        if (old == null) {
            fields.put(nativeObject.key(), nativeObject.id()); fields.put("ea_guid", artifact.id());
            if (nativeObject.table().equals("t_object") && database.hasColumn("t_object", "parentid")) fields.put("parentid", 0);
            if (artifact.kind() == ArtifactKind.VIEW) fields.put("diagram_type", "Logical");
            database.insert(nativeObject.table(), fields);
        } else database.update(nativeObject.table(), nativeObject.key(), nativeObject.id(), delta(old, fields));
        if (artifact.kind() == ArtifactKind.SPECIFICATION) {
            Row mirror = model.mirrors.get(artifact.id());
            if (old == null) {
                long id = database.allocate("t_object", "object_id", QeaWriteModel.frozenId(artifact.attributes(), "t_object", "object_id", artifact.id()));
                var values = new LinkedHashMap<String, Object>();
                values.put("object_id", id); values.put("object_type", "Package"); values.put("name", artifact.title()); values.put("note", artifact.text());
                values.put("package_id", model.packageId(artifact.id())); values.put("ea_guid", artifact.id()); values.put("pdata1", Long.toString(nativeObject.id()));
                if (database.hasColumn("t_object", "parentid")) values.put("parentid", 0);
                database.insert("t_object", values);
                var strings = new HashMap<String, String>(); values.forEach((key, value) -> strings.put(key, value.toString()));
                mirror = new Row("t_object", Map.copyOf(strings), Set.of()); model.mirrors.put(artifact.id(), mirror);
            } else if (mirror != null) database.update("t_object", "object_id", mirror.id("object_id"), delta(mirror,
                    Map.of("name", artifact.title(), "note", artifact.text(), "package_id", model.packageId(artifact.id()))));
            Map<String, String> selectedTags = QeaSemanticProfile.tags(artifact.attributes(), artifact.extensions(), null, false, null);
            if (!selectedTags.isEmpty()) {
                if (mirror == null) throw fail("QEA_TAG_UNSUPPORTED", "The root package has no native object mirror for tagged values");
                tags.tags(mirror.id("object_id"), mirror.text("ea_guid"), false, selectedTags);
            }
        } else if (artifact.kind() == ArtifactKind.VIEW) {
            if (artifact.attributes().keySet().stream().anyMatch(k -> k.startsWith("tag:"))) throw fail("QEA_TAG_UNSUPPORTED", "Native diagram tags are outside the supported writable scope");
        } else {
            tags.tags(nativeObject.id(), artifact.id(), false, QeaSemanticProfile.tags(artifact.attributes(), artifact.extensions(), canonical, false, null));
            if (canonical != null) tags.stereotype(artifact.id(), canonical, false);
        }
    }
    private static boolean unchanged(Artifact artifact, Artifact before) {
        return before != null && artifact.kind() == before.kind() && Objects.equals(artifact.type(), before.type())
                && artifact.title().equals(before.title()) && artifact.text().equals(before.text())
                && Objects.equals(artifact.extensions().get("canonicalType"), before.extensions().get("canonicalType"))
                && selectedTagsUnchanged(artifact.attributes(), artifact.extensions(), before.attributes(), before.extensions());
    }
    private static boolean selectedTagsUnchanged(Map<String, String> attributes, Map<String, String> extensions, Map<String, String> oldAttributes, Map<String, String> oldExtensions) {
        return attributes.entrySet().stream().filter(e -> e.getKey().startsWith("tag:")).allMatch(e -> e.getValue().equals(oldAttributes.get(e.getKey())))
                && extensions.entrySet().stream().filter(e -> e.getKey().startsWith("taxonomy:")).allMatch(e -> e.getValue().equals(oldExtensions.get(e.getKey())));
    }
    private static void relation(Relation relation, QeaWriteModel model, QeaWriteDatabase database, QeaWriteTags tags) throws SQLException {
        NativeObject nativeObject = model.nativeObjects.get(relation.id()); Row old = nativeObject.original(); Relation before = model.beforeRelations.get(relation.id());
        if (old != null && before != null && relation.type().equals(before.type()) && relation.source().equals(before.source()) && relation.target().equals(before.target())
                && relation.extensions().equals(before.extensions()) && relation.attributes().equals(before.attributes())) return;
        if (relation.extensions().getOrDefault("nativeProjection", "ARCHITECTURE_RELATION").equals("PRESERVE_ONLY")) {
            if (old == null) throw fail("QEA_RELATION_MAPPING_REQUIRED", "A new connector cannot be preserve-only");
            return;
        }
        String canonical = relation.extensions().get("canonicalType"), direction = relation.extensions().get("direction");
        if (!SparxSemanticTypes.relationTypes().containsKey(Objects.toString(canonical, "")))
            throw fail("QEA_RELATION_MAPPING_REQUIRED", "A changed or new connector needs a supported explicit Taxonomy meaning");
        QeaSemanticProfile.direction(canonical, direction);
        String transport = SparxSemanticTypes.relationTransport(canonical);
        if (old == null && !transport.equals(relation.type())) throw fail("QEA_RELATION_MAPPING_REQUIRED", "A new connector must use its declared UML transport");
        QeaWriteTags.checkExistingStereotype(old == null ? "" : old.text("stereotype"));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("connector_type", QeaNativeRelations.nativeType(transport)); fields.put("start_object_id", model.objectId(relation.source())); fields.put("end_object_id", model.objectId(relation.target()));
        fields.put("stereotype", SparxSemanticTypes.taxonomyStereotype(canonical)); fields.put("direction", direction);
        fields.put("sourceisaggregate", QeaNativeRelations.sourceAggregation(transport, direction)); fields.put("destisaggregate", QeaNativeRelations.destinationAggregation(transport, direction));
        if (old == null) { fields.put("connector_id", nativeObject.id()); fields.put("ea_guid", relation.id()); database.insert("t_connector", fields); }
        else database.update("t_connector", "connector_id", nativeObject.id(), delta(old, fields));
        tags.tags(nativeObject.id(), relation.id(), true, QeaSemanticProfile.tags(relation.attributes(), relation.extensions(), canonical, true, direction));
        tags.stereotype(relation.id(), canonical, true);
    }
    static Map<String, Object> delta(Row row, Map<String, Object> requested) {
        var changed = new LinkedHashMap<String, Object>();
        requested.forEach((key, value) -> {
            if (!row.text(key).equals(Objects.toString(value, "")) && !(value instanceof Number number && number.longValue() == 0 && row.text(key).isEmpty())) {
                if (row.binaryFields().contains(key)) throw fail("QEA_BINARY_FIELD_UNSUPPORTED", "A native binary field cannot be replaced by projected scalar values");
                changed.put(key, value);
            }
        });
        return changed;
    }
    private static void verifyReviewed(QeaWriteModel model, ExchangeDocument verified) {
        Map<String, Artifact> artifacts = new HashMap<>(); verified.artifacts().forEach(a -> artifacts.put(a.id(), a));
        for (Artifact selected : model.selected.values()) {
            Artifact found = artifacts.get(selected.id());
            if (found == null || found.kind() != selected.kind() || !found.title().equals(selected.title()) || !found.text().equals(selected.text()))
                throw fail("QEA_WRITE_CONFLICT", "Native read-back did not retain the reviewed artifact identity and values");
            if (selected.kind() == ArtifactKind.ELEMENT && selected.extensions().containsKey("canonicalType")
                    && !Objects.equals(selected.extensions().get("canonicalType"), found.extensions().get("canonicalType")))
                throw fail("QEA_WRITE_CONFLICT", "Native read-back did not retain the reviewed element meaning");
        }
        Map<String, Relation> relations = new HashMap<>(); verified.relations().forEach(r -> relations.put(r.id(), r));
        for (Relation selected : model.relations.values()) {
            if (selected.extensions().getOrDefault("nativeProjection", "").equals("PRESERVE_ONLY")) continue;
            Relation found = relations.get(selected.id());
            if (found == null || !found.source().equals(selected.source()) || !found.target().equals(selected.target())
                    || !Objects.equals(found.extensions().get("canonicalType"), selected.extensions().get("canonicalType")))
                throw fail("QEA_WRITE_CONFLICT", "Native read-back did not retain the reviewed relation meaning and endpoints");
        }
    }
}
