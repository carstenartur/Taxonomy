package com.taxonomy.backup;

import java.util.*;

/** An explicitly approved identity mapping. Applying ownership remains a separate audited action. */
public final class PrincipalMapping {
    private final Map<PrincipalId, PrincipalId> targets;
    private final PrincipalId approvedBy;
    private final String rationale;

    PrincipalMapping(Map<PrincipalId, PrincipalId> targets, PrincipalId approvedBy, String rationale) {
        this.targets = Map.copyOf(targets);
        this.approvedBy = Objects.requireNonNull(approvedBy);
        this.rationale = BackupChecks.text(rationale, "mapping rationale");
    }
    public Optional<PrincipalId> targetOf(PrincipalId source) { return Optional.ofNullable(targets.get(source)); }
    public Map<PrincipalId, PrincipalId> targets() { return targets; }
    public PrincipalId approvedBy() { return approvedBy; }
    public String rationale() { return rationale; }

    public static PrincipalMappingPlan preview(SourceIdentitySet sources, TargetIdentityContext targets) {
        var byBinding = new HashMap<IdentityBinding, List<TargetIdentityContext.Identity>>();
        var enabled = new HashSet<PrincipalId>();
        for (var target : targets.identities()) {
            if (target.loginEnabled()) enabled.add(target.id());
            for (var binding : target.bindings()) byBinding.computeIfAbsent(binding, ignored -> new ArrayList<>()).add(target);
        }
        var entries = new LinkedHashMap<PrincipalId, PrincipalMappingPlan.Entry>();
        for (var source : sources.identities()) {
            var candidates = new HashSet<PrincipalId>();
            boolean disabled = false;
            for (var binding : source.bindings()) {
                for (var target : byBinding.getOrDefault(binding, List.of())) {
                    candidates.add(target.id());
                    disabled |= !target.loginEnabled();
                }
            }
            var status = candidates.isEmpty() ? PrincipalMappingPlan.Status.UNRESOLVED
                    : candidates.size() != 1 || disabled ? PrincipalMappingPlan.Status.CONFLICT : PrincipalMappingPlan.Status.MATCHED;
            entries.put(source.id(), new PrincipalMappingPlan.Entry(status, candidates));
        }
        return new PrincipalMappingPlan(entries, enabled, null);
    }
}
