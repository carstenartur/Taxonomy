package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PrincipalMappingTest {
    private final PrincipalId source = PrincipalId.create();
    private final PrincipalId target = PrincipalId.create();
    private IdentityBinding oidc(String issuer, String subject) {
        return new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject);
    }
    private SourceIdentitySet sources(IdentityBinding binding) {
        return new SourceIdentitySet(List.of(new SourceIdentitySet.Identity(source, "alice", "alice@example.org", Set.of(binding))));
    }
    private TargetIdentityContext targets(IdentityBinding binding, boolean loginEnabled) {
        return new TargetIdentityContext(List.of(new TargetIdentityContext.Identity(target, "alice", "alice@example.org", loginEnabled, Set.of(binding))));
    }
    @Test void sameNameAndEmailAtAnotherIssuerNeverMatch() {
        var plan = PrincipalMapping.preview(sources(oidc("https://source.example/", "42")), targets(oidc("https://target.example/", "42"), true));
        assertEquals(PrincipalMappingPlan.Status.UNRESOLVED, plan.entries().get(source).status());
        assertTrue(plan.entries().get(source).candidates().isEmpty());
        assertThrows(IllegalStateException.class, () -> plan.requireResolved());
    }
    @Test void exactIssuerSubjectMatchIsOnlyAMappingProposal() {
        var binding = oidc("https://issuer.example/", "subject-42");
        var plan = PrincipalMapping.preview(sources(binding), targets(binding, true));
        assertEquals(PrincipalMappingPlan.Status.MATCHED, plan.entries().get(source).status());
        assertEquals(Set.of(target), plan.entries().get(source).candidates());
        assertFalse(plan.approved());
        assertThrows(IllegalStateException.class, () -> plan.toApprovedMapping());
    }
    @Test void duplicateIdentityBindingsAndDisabledAccountsDoNotAcquireAccess() {
        var binding = oidc("https://issuer.example/", "subject-42");
        var disabled = PrincipalMapping.preview(sources(binding), targets(binding, false));
        assertEquals(PrincipalMappingPlan.Status.CONFLICT, disabled.entries().get(source).status());
        var duplicate = new TargetIdentityContext(List.of(
                new TargetIdentityContext.Identity(target, "alice", "alice@example.org", true, Set.of(binding)),
                new TargetIdentityContext.Identity(PrincipalId.create(), "different", "other@example.org", true, Set.of(binding))));
        assertEquals(PrincipalMappingPlan.Status.CONFLICT, PrincipalMapping.preview(sources(binding), duplicate).entries().get(source).status());
    }
    @Test void localAccountKeysAreInstallationScopedAndHistoricalIdentityHasNoLogin() {
        var localSource = new IdentityBinding(IdentityBinding.Kind.LOCAL, "installation-source", "local-account-7");
        var localTarget = new IdentityBinding(IdentityBinding.Kind.LOCAL, "installation-target", "local-account-7");
        assertEquals(PrincipalMappingPlan.Status.UNRESOLVED,
                PrincipalMapping.preview(sources(localSource), targets(localTarget, true)).entries().get(source).status());
        var historical = new SourceIdentitySet(List.of(new SourceIdentitySet.Identity(source, "alice", "alice@example.org", Set.of())));
        assertEquals(PrincipalMappingPlan.Status.UNRESOLVED,
                PrincipalMapping.preview(historical, targets(localTarget, true)).entries().get(source).status());
    }
    @Test void conflictingSourceBindingsCannotSilentlyPickOneAccount() {
        var a = oidc("https://issuer.example/", "a");
        var b = oidc("https://issuer.example/", "b");
        var sources = new SourceIdentitySet(List.of(new SourceIdentitySet.Identity(source, "alice", null, Set.of(a, b))));
        var targets = new TargetIdentityContext(List.of(
                new TargetIdentityContext.Identity(target, "alice", null, true, Set.of(a)),
                new TargetIdentityContext.Identity(PrincipalId.create(), "bob", null, true, Set.of(b))));
        assertEquals(PrincipalMappingPlan.Status.CONFLICT, PrincipalMapping.preview(sources, targets).entries().get(source).status());
    }
    @Test void approvalIsExplicitAndKeepsUnmappedIdentitiesQuarantined() {
        var binding = oidc("https://issuer.example/", "subject-42");
        var plan = PrincipalMapping.preview(sources(binding), targets(binding, true));
        var mapping = plan.approve(Map.of(source, target), PrincipalId.create(), "Verified target ownership").toApprovedMapping();
        assertEquals(target, mapping.targetOf(source).orElseThrow());
        assertTrue(mapping.targetOf(PrincipalId.create()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> plan.approve(Map.of(source, PrincipalId.create()), PrincipalId.create(), "unknown target"));
        assertThrows(IllegalArgumentException.class, () -> plan.approve(Map.of(source, target), PrincipalId.create(), ""));
    }
}
