package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.*;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import java.util.*;

/** Neutral package projection. Diagram occurrences never assign package membership. */
final class IntegrationPackageProjection {
    private IntegrationPackageProjection() {}
    static List<ArchitectureCommand> commands(Connection connection, String dsl, Map<String, Artifact> current,
            Map<String, Artifact> selected, List<Identity> mappings, IntegrationDomainAdapter domain) {
        Map<String, Identity> known = new HashMap<>(); mappings.forEach(m -> known.put(m.externalId(), m));
        Map<String, String> nativeIds = new HashMap<>();
        for (Artifact a : selected.values()) if (a.kind() == ArtifactKind.SPECIFICATION || a.kind() == ArtifactKind.ELEMENT)
            nativeIds.put(a.id(), domain.businessId(connection, known.get(ExchangeItems.key(a)), a));
        List<ArchitectureCommand> commands = new ArrayList<>();
        ArchitectureDslCommands transformer = new ArchitectureDslCommands();
        String preview = dsl;
        for (Artifact a : selected.values()) if (a.kind() == ArtifactKind.SPECIFICATION) {
            String id = nativeIds.get(a.id());
            boolean exists = transformer.model(preview).getPackages().stream().anyMatch(p -> p.id().equals(id));
            ArchitectureCommand command = exists ? new UpdateArchitecturePackage(id, Map.of("title", a.title(), "description", a.text()))
                    : new CreateArchitecturePackage(id, Map.of("title", a.title(), "description", a.text()));
            if (!exists || !ExchangeItems.fields(a).equals(ExchangeItems.fields(current.get(ExchangeItems.key(a))))) {
                commands.add(command); preview = transformer.apply(preview, command).dsl();
                commands.add(new SetExchangeProperties("package", id, Map.of("x-exchange-id", a.id(), "x-exchange-connection", connection.id().toString())));
            }
        }
        CanonicalArchitectureModel model = transformer.model(preview);
        Map<String, PackagePlacement> members = new LinkedHashMap<>();
        model.getPackages().forEach(p -> members.put("PACKAGE:" + p.id(), new PackagePlacement(PackageMemberKind.PACKAGE, p.id(), p.parentId(), p.position())));
        model.getElements().stream().filter(e -> e.getPackageId() != null).forEach(e -> members.put("ELEMENT:" + e.getId(), new PackagePlacement(PackageMemberKind.ELEMENT, e.getId(), e.getPackageId(), e.getPackagePosition())));
        Map<String, Artifact> occurrences = new HashMap<>();
        Set<String> viewContainers = viewContainers(current, mappings);
        selected.values().stream().filter(a -> a.kind() == ArtifactKind.VIEW).map(Artifact::id).forEach(viewContainers::add);
        selected.values().stream().filter(a -> isPackagePlacement(a, viewContainers)).forEach(a -> occurrences.put(a.id(), a));
        Map<String, Integer> nativePositions = nativePositions(occurrences.values(), nativeIds.keySet());
        Map<String, PackagePlacement> deltas = new LinkedHashMap<>();
        for (Artifact a : occurrences.values()) {
            if (ExchangeItems.fields(a).equals(ExchangeItems.fields(current.get(ExchangeItems.key(a))))) continue;
            String nativeId = nativeIds.get(a.extensions().get("artifact"));
            if (nativeId == null) continue; // Requirement membership remains exchange evidence.
            Artifact artifact = selected.values().stream().filter(v -> v.id().equals(a.extensions().get("artifact")) && v.kind() != ArtifactKind.PLACEMENT).findFirst().orElseThrow();
            PackageMemberKind kind = artifact.kind() == ArtifactKind.SPECIFICATION ? PackageMemberKind.PACKAGE : PackageMemberKind.ELEMENT;
            String parentOccurrence = a.extensions().get("parent");
            String parent = "";
            if (parentOccurrence != null && !parentOccurrence.isEmpty()) {
                Artifact occurrence = occurrences.get(parentOccurrence);
                if (occurrence == null) throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422, "Package parent occurrence is outside the reviewed scope");
                parent = nativeIds.get(occurrence.extensions().get("artifact"));
                if (parent == null) throw new IntegrationProblem("SPARX_ENDPOINT_KIND_UNMAPPED", 422, "Package parent is not native");
            }
            deltas.put(kind + ":" + nativeId, new PackagePlacement(kind, nativeId, parent, nativePositions.get(a.id())));
        }
        for (var entry : current.entrySet()) if (isPackagePlacement(entry.getValue(), viewContainers) && !selected.containsKey(entry.getKey())) {
            String external = entry.getValue().extensions().get("artifact");
            Identity prior = known.values().stream().filter(m -> m.internal() != null && m.internal().id().equals(external)).findFirst().orElse(null);
            if (prior != null && prior.internal().kind() == ArtifactKind.ELEMENT)
                deltas.put("ELEMENT:" + prior.businessIdentity(), new PackagePlacement(PackageMemberKind.ELEMENT, prior.businessIdentity(), null, -1));
        }
        Set<String> touched = new LinkedHashSet<>();
        for (var entry : deltas.entrySet()) {
            PackagePlacement old = members.get(entry.getKey()); if (old != null) touched.add(old.parentPackageId());
            if (entry.getValue().parentPackageId() != null) touched.add(entry.getValue().parentPackageId());
        }
        if (!deltas.isEmpty()) {
            Map<String, List<PackagePlacement>> scopes = new LinkedHashMap<>();
            for (String parent : touched) scopes.put(parent, new ArrayList<>(members.entrySet().stream()
                    .filter(e -> parent.equals(e.getValue().parentPackageId()) && !deltas.containsKey(e.getKey()))
                    .map(Map.Entry::getValue).sorted(Comparator.comparingInt(PackagePlacement::position)).toList()));
            deltas.values().stream().filter(p -> p.parentPackageId() != null).sorted(Comparator.comparingInt(PackagePlacement::position).thenComparing(PackagePlacement::memberId))
                    .forEach(p -> { var siblings = scopes.get(p.parentPackageId()); siblings.add(Math.min(p.position(), siblings.size()), p); });
            List<PackagePlacement> finalState = new ArrayList<>();
            scopes.forEach((parent, siblings) -> { for (int i = 0; i < siblings.size(); i++) {
                var p = siblings.get(i); finalState.add(new PackagePlacement(p.kind(), p.memberId(), parent, i));
            }});
            deltas.values().stream().filter(p -> p.parentPackageId() == null).forEach(finalState::add);
            commands.add(new SetArchitecturePackagePlacements(finalState, touched));
        }
        List<ArchitecturePackage> removed = model.getPackages().stream().filter(p -> mappings.stream().anyMatch(m -> p.id().equals(m.businessIdentity())
                && m.internal() != null && m.internal().kind() == ArtifactKind.SPECIFICATION && !selected.containsKey(m.externalId()))).toList();
        removed.stream().sorted(Comparator.comparingInt((ArchitecturePackage p) -> depth(p, model)).reversed())
                .forEach(p -> commands.add(new DeleteArchitecturePackage(p.id())));
        return commands;
    }
    static void synchronize(Connection connection, String dsl, List<Identity> mappings, Map<String, Artifact> items,
                            String modelId, boolean includeNew) {
        synchronize(connection, dsl, mappings, items, modelId, includeNew, null);
    }
    static void synchronize(Connection connection, String dsl, List<Identity> mappings, Map<String, Artifact> items,
                            String modelId, boolean includeNew, FileExportProfile fileProfile) {
        CanonicalArchitectureModel model = new ArchitectureDslCommands().model(dsl);
        Map<String, String> external = new HashMap<>();
        for (Identity mapping : mappings) if (!mapping.removed() && mapping.internal() != null
                && Set.of(ArtifactKind.ELEMENT, ArtifactKind.SPECIFICATION).contains(mapping.internal().kind()))
            external.put(mapping.businessIdentity(), mapping.internal().id());
        if (includeNew) {
            model.getElements().forEach(e -> external.putIfAbsent(e.getId(), IntegrationDomainAdapter.externalId(connection, "export-element:" + e.getId(), fileProfile)));
            model.getPackages().forEach(p -> external.putIfAbsent(p.id(), IntegrationDomainAdapter.externalId(connection, "export-package:" + p.id(), fileProfile)));
        }
        Set<String> existingPackages = model.getPackages().stream().map(ArchitecturePackage::id).collect(java.util.stream.Collectors.toSet());
        for (Identity mapping : mappings) if (mapping.internal() != null && mapping.internal().kind() == ArtifactKind.SPECIFICATION && !existingPackages.contains(mapping.businessIdentity()))
            items.remove(mapping.externalId());
        for (ArchitecturePackage pkg : model.getPackages()) {
            String id = external.get(pkg.id()); if (id == null) continue;
            Artifact previous = items.get("SPECIFICATION:" + id);
            items.put("SPECIFICATION:" + id, new Artifact(id, ArtifactKind.SPECIFICATION, fileProfile == null ? "Package" : fileProfile.packageType(), pkg.title(), pkg.description() == null ? "" : pkg.description(),
                    previous == null ? Map.of() : previous.attributes(), previous == null ? Map.of() : previous.extensions()));
        }
        Map<String, Artifact> occurrences = new HashMap<>();
        Set<String> viewContainers = viewContainers(items, mappings);
        for (Artifact a : items.values()) if (isPackagePlacement(a, viewContainers)) occurrences.put(a.extensions().get("artifact"), a);
        Map<String, String> placementIds = new HashMap<>();
        external.forEach((nativeId, id) -> placementIds.put(nativeId, occurrences.containsKey(id) ? occurrences.get(id).id()
                : fileProfile != null ? (existingPackages.contains(nativeId) ? "package:" : "member:") + id
                : com.taxonomy.interop.sparx.SparxSnapshots.isSparx(connection.connectorId()) ? "placement:" + id : IntegrationDomainAdapter.externalId(connection, "export-placement:" + nativeId)));
        List<PackagePlacement> members = new ArrayList<>();
        model.getPackages().forEach(p -> members.add(new PackagePlacement(PackageMemberKind.PACKAGE, p.id(), p.parentId(), p.position())));
        model.getElements().forEach(e -> {
            if (e.getPackageId() != null) members.add(new PackagePlacement(PackageMemberKind.ELEMENT, e.getId(), e.getPackageId(), e.getPackagePosition()));
            else if (external.containsKey(e.getId())) {
                Artifact prior = occurrences.get(external.get(e.getId()));
                if (prior != null) items.remove(ExchangeItems.key(prior));
            }
        });
        Map<String, Integer> sourcePositions = sourcePositions(members, occurrences.values(), external, placementIds, modelId);
        for (PackagePlacement member : members) {
            String id = external.get(member.memberId()); if (id == null) continue;
            Artifact prior = occurrences.get(id);
            String container = prior == null ? modelId : prior.extensions().get("container");
            if (container == null) continue;
            String parent = member.parentPackageId().isEmpty() ? "" : placementIds.get(member.parentPackageId());
            if (parent == null) continue;
            Map<String, String> extension = new TreeMap<>(prior == null ? Map.of() : prior.extensions());
            extension.put("artifact", id); extension.put("parent", parent); extension.put("container", container);
            extension.put("position", Integer.toString(sourcePositions.get(member.memberId())));
            Artifact placement = new Artifact(placementIds.get(member.memberId()), ArtifactKind.PLACEMENT, "placement", "", "",
                    prior == null ? Map.of() : prior.attributes(), extension);
            items.put(ExchangeItems.key(placement), placement);
        }
    }

    /** Requirement occurrences remain source evidence and do not occupy native package positions. */
    private static Map<String, Integer> nativePositions(Collection<Artifact> occurrences, Set<String> nativeIds) {
        Map<OccurrenceScope, List<Artifact>> scopes = new HashMap<>();
        for (Artifact a : occurrences) if (nativeIds.contains(a.extensions().get("artifact")))
            scopes.computeIfAbsent(OccurrenceScope.of(a), ignored -> new ArrayList<>()).add(a);
        Map<String, Integer> positions = new HashMap<>();
        scopes.values().forEach(siblings -> {
            siblings.sort(Comparator.comparingInt(IntegrationPackageProjection::position).thenComparing(Artifact::id));
            for (int i = 0; i < siblings.size(); i++) positions.put(siblings.get(i).id(), i);
        });
        return positions;
    }

    /** Expand native ordering into the original source slots without rewriting opaque membership evidence. */
    private static Map<String, Integer> sourcePositions(List<PackagePlacement> members, Collection<Artifact> occurrences,
            Map<String, String> external, Map<String, String> placementIds, String modelId) {
        Set<String> nativeIds = new HashSet<>(external.values());
        Map<String, Artifact> prior = new HashMap<>();
        Map<OccurrenceScope, SortedSet<Integer>> slots = new HashMap<>();
        Map<OccurrenceScope, Integer> next = new HashMap<>();
        for (Artifact a : occurrences) {
            prior.put(a.extensions().get("artifact"), a);
            OccurrenceScope scope = OccurrenceScope.of(a);
            next.merge(scope, position(a) + 1, Math::max);
            if (nativeIds.contains(a.extensions().get("artifact")))
                slots.computeIfAbsent(scope, ignored -> new TreeSet<>()).add(position(a));
        }
        Map<OccurrenceScope, List<PackagePlacement>> scopes = new HashMap<>();
        for (PackagePlacement member : members) {
            String id = external.get(member.memberId()); if (id == null) continue;
            Artifact a = prior.get(id);
            String container = a == null ? modelId : a.extensions().get("container");
            String parent = member.parentPackageId().isEmpty() ? "" : placementIds.get(member.parentPackageId());
            if (container != null && parent != null)
                scopes.computeIfAbsent(new OccurrenceScope(container, parent), ignored -> new ArrayList<>()).add(member);
        }
        Map<String, Integer> positions = new HashMap<>();
        scopes.forEach((scope, siblings) -> {
            siblings.sort(Comparator.comparingInt(PackagePlacement::position).thenComparing(PackagePlacement::memberId));
            Iterator<Integer> available = slots.getOrDefault(scope, Collections.emptySortedSet()).iterator();
            int appended = next.getOrDefault(scope, 0);
            for (PackagePlacement member : siblings)
                positions.put(member.memberId(), available.hasNext() ? available.next() : appended++);
        });
        return positions;
    }

    private static int position(Artifact occurrence) { return Integer.parseInt(occurrence.extensions().get("position")); }

    private record OccurrenceScope(String container, String parent) {
        static OccurrenceScope of(Artifact a) {
            return new OccurrenceScope(a.extensions().get("container"), Objects.requireNonNullElse(a.extensions().get("parent"), ""));
        }
    }

    static Set<String> viewContainers(Map<String, Artifact> items, List<Identity> mappings) {
        Set<String> views = new HashSet<>();
        items.values().stream().filter(a -> a.kind() == ArtifactKind.VIEW).map(Artifact::id).forEach(views::add);
        // A locally removed view still identifies its retained source occurrences as diagram evidence.
        for (Identity mapping : mappings) for (Artifact a : new Artifact[]{mapping.internal(), mapping.external()})
            if (a != null && a.kind() == ArtifactKind.VIEW) views.add(a.id());
        return views;
    }

    static String modelContainer(Map<String, Artifact> items, List<Identity> mappings) {
        Set<String> views = viewContainers(items, mappings);
        Artifact metadata = items.get("METADATA:package");
        String identifier = metadata == null ? null : metadata.attributes().get("identifier");
        if (identifier != null && !identifier.isBlank() && !views.contains(identifier)) return identifier;
        return items.values().stream().filter(a -> isPackagePlacement(a, views))
                .map(a -> a.extensions().get("container")).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static boolean isPackagePlacement(Artifact artifact, Set<String> views) {
        return artifact.kind() == ArtifactKind.PLACEMENT && !views.contains(artifact.extensions().get("container"));
    }

    private static int depth(ArchitecturePackage p, CanonicalArchitectureModel model) {
        Map<String, ArchitecturePackage> packages = new HashMap<>(); model.getPackages().forEach(v -> packages.put(v.id(), v));
        int depth = 0; for (; p != null && depth <= 80; p = packages.get(p.parentId())) depth++;
        return depth;
    }
}
