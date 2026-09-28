package com.taxonomy.interop.planning;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureCommand.ImportRequirementPlanning;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.model.ArchitectureRequirement;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.planning.PlanningEntry;
import com.taxonomy.dsl.planning.PlanningInformation;
import com.taxonomy.exchange.PlanningEnvelope;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.ExchangeItems;
import com.taxonomy.interop.IntegrationPortfolioPort.RequirementApplyPlan;
import com.taxonomy.interop.IntegrationProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Maps existing canonical planning data to transport values; never stores a second planning model. */
public final class PlanningExchangeBridge {
    private final CanonicalArchitectureModel model;
    private final PlanningInformation profiles;
    public PlanningExchangeBridge(String dsl) { this(dsl, new PlanningInformation()); }
    public PlanningExchangeBridge(String dsl, PlanningInformation profiles) {
        this.model = new ArchitectureDslCommands().model(dsl); this.profiles = profiles;
    }
    public Artifact overlay(Artifact artifact, String projectKey, String requirementKey) {
        if (projectKey == null || projectKey.isBlank() || requirementKey == null || requirementKey.isBlank())
            throw problem("An explicit project and requirement identity is required for planning exchange");
        var matches = model.getRequirements().stream().filter(r -> java.util.Objects.equals(projectKey, r.getExtensions().get("x-project-key"))
                && requirementKey.equals(r.getExtensions().get("x-requirement-key"))).toList();
        if (matches.size() > 1) throw problem("Ambiguous canonical requirement for planning information");
        if (matches.isEmpty()) {
            if (artifact.extensions().containsKey(PlanningEnvelope.EXTENSION)) throw problem("Canonical requirement for retained planning information is missing");
            return artifact;
        }
        return overlayCanonical(artifact, matches.getFirst());
    }
    public Artifact overlayCanonical(Artifact artifact, ArchitectureRequirement requirement) {
        List<PlanningEnvelope.Entry> entries = new ArrayList<>();
        for (var view : profiles.read(model, requirement.getId())) {
            if (!view.supported() && !"UNSUPPORTED_PROFILE_VERSION".equals(view.problem()))
                throw problem("Invalid canonical planning data must be resolved before exporting this requirement");
            var entry = view.entry();
            entries.add(new PlanningEnvelope.Entry(entry.id(), entry.profile(), entry.version(), entry.origin(), entry.values()));
        }
        if (entries.isEmpty() && !artifact.extensions().containsKey(PlanningEnvelope.EXTENSION)) return artifact;
        return withPayload(artifact, PlanningEnvelope.write(entries));
    }
    /** Validate known profiles before a preview; unknown versions remain un-interpreted transport data. */
    public static void validateInbound(ExchangeDocument document) {
        PlanningInformation profiles = new PlanningInformation();
        for (Artifact artifact : document.artifacts()) if (artifact.kind() == ArtifactKind.REQUIREMENT && artifact.extensions().containsKey(PlanningEnvelope.EXTENSION))
            for (var entry : PlanningEnvelope.read(artifact.extensions().get(PlanningEnvelope.EXTENSION)))
                profiles.validatePortable(domain(entry));
    }
    public static boolean hasEntries(Map<String, Artifact> selected) {
        return selected.values().stream().filter(artifact -> artifact.kind() == ArtifactKind.REQUIREMENT)
                .anyMatch(artifact -> artifact.extensions().containsKey(PlanningEnvelope.EXTENSION));
    }
    public static List<ArchitectureCommand> commands(Map<String, Artifact> selected, Map<String, RequirementApplyPlan> plans) {
        List<ArchitectureCommand> commands = new ArrayList<>();
        for (Artifact artifact : selected.values()) if (artifact.kind() == ArtifactKind.REQUIREMENT && artifact.extensions().containsKey(PlanningEnvelope.EXTENSION)) {
            RequirementApplyPlan plan = plans.get(ExchangeItems.key(artifact));
            if (plan == null) throw problem("An exact canonical requirement identity is required for planning import");
            for (var entry : PlanningEnvelope.read(artifact.extensions().get(PlanningEnvelope.EXTENSION)))
                commands.add(new ImportRequirementPlanning(plan.canonicalIdentity(), domain(entry)));
            if (commands.size() > 2000) throw problem("Planning imports are bounded to 2000 entry commands; reduce the reviewed batch");
        }
        return List.copyOf(commands);
    }
    /** Only the selected LOCAL result gains retained entries. The observed external baseline stays untouched. */
    public static Artifact retainOmissions(Artifact selected, Artifact local) {
        if (selected == null || selected.kind() != ArtifactKind.REQUIREMENT || local == null || !local.extensions().containsKey(PlanningEnvelope.EXTENSION)) return selected;
        Map<String, PlanningEnvelope.Entry> entries = new TreeMap<>();
        for (var entry : PlanningEnvelope.read(local.extensions().get(PlanningEnvelope.EXTENSION))) entries.put(entry.id(), entry);
        if (selected.extensions().containsKey(PlanningEnvelope.EXTENSION))
            for (var entry : PlanningEnvelope.read(selected.extensions().get(PlanningEnvelope.EXTENSION))) entries.put(entry.id(), entry);
        return withPayload(selected, PlanningEnvelope.write(new ArrayList<>(entries.values())));
    }
    /** Add a loss notice without rewriting observations or claiming acknowledgement of locally retained fields. */
    public static ExchangeDocument describeInbound(ExchangeDocument document, Map<String, Artifact> current) {
        List<MappingLoss> losses = new ArrayList<>(document.losses());
        for (Artifact artifact : document.artifacts()) if (artifact.kind() == ArtifactKind.REQUIREMENT) {
            Artifact local = current.get(ExchangeItems.key(artifact));
            Artifact retained = retainOmissions(artifact, local);
            String incoming = artifact.extensions().get(PlanningEnvelope.EXTENSION);
            if (!samePayload(incoming, retained.extensions().get(PlanningEnvelope.EXTENSION)))
                losses.add(new MappingLoss(artifact.id(), PlanningEnvelope.ATTRIBUTE, "PLANNING_OMISSION_RETAINED", LossDisposition.PRESERVED_EXTENSION,
                        "Missing external planning entries are retained locally, not deleted or acknowledged as present in the external system."));
            if (incoming != null) {
                PlanningInformation registry = new PlanningInformation();
                for (var entry : PlanningEnvelope.read(incoming)) if (!registry.supports(entry.profile(), entry.version()))
                    losses.add(new MappingLoss(artifact.id(), entry.id(), "UNSUPPORTED_PLANNING_PROFILE_VERSION", LossDisposition.PRESERVED_EXTENSION,
                            "The profile version is retained read-only and has no effect on planning or compliance evaluation."));
            }
        }
        return new ExchangeDocument(document.profile(), document.profileVersion(), document.externalVersion(), document.completeScope(), document.source(),
                document.artifacts(), document.relations(), document.placements(), document.metadata(), List.copyOf(losses));
    }
    private static boolean samePayload(String a, String b) {
        if (a == null || b == null) return a == b;
        return PlanningEnvelope.read(a).equals(PlanningEnvelope.read(b));
    }
    private static PlanningEntry domain(PlanningEnvelope.Entry entry) { return new PlanningEntry(entry.id(), entry.profile(), entry.version(), entry.origin(), entry.values()); }
    private static Artifact withPayload(Artifact artifact, String payload) {
        Map<String, String> extensions = new TreeMap<>(artifact.extensions()); extensions.put(PlanningEnvelope.EXTENSION, payload);
        return new Artifact(artifact.id(), artifact.kind(), artifact.type(), artifact.title(), artifact.text(), artifact.attributes(), extensions);
    }
    private static IntegrationProblem problem(String message) { return new IntegrationProblem("PLANNING_MAPPING_REQUIRED", 422, message); }
}
