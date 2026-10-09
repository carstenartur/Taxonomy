package com.taxonomy.composition.plugins;

import com.taxonomy.shared.features.FeatureAssembly;

import com.taxonomy.backup.runtime.BackupManifestCodec;

import com.taxonomy.backup.*;
import com.taxonomy.backup.runtime.BackupFeaturePrerequisites;
import com.taxonomy.extension.api.plugin.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PluginRecoveryContractTest {
    private static final Set<String> ALL=Set.of("templates","reporting","architecture","analysis","portfolio","interop");
    private static final PluginIdentity TEMPLATE=new PluginIdentity("taxonomy.feature.templates","1.4.1-SNAPSHOT","a".repeat(64));
    @Test void incompleteInstallationsCannotClaimCompleteBackups() {
        assertThatCode(() -> prerequisites(ALL,Map.of()).requireCapture()).doesNotThrowAnyException();
        assertThatThrownBy(() -> prerequisites(Set.of(),Map.of()).requireCapture())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("templates").hasMessageContaining("analysis");
    }
    @Test void matchingArtifactRoundtripsAndAReplacementIsRejectedBeforeRestore() throws Exception {
        var required=prerequisites(ALL,Map.of("templates",TEMPLATE));
        var manifest=manifest(required.dependencies(),Set.of(BackupFeaturePrerequisites.PROTOCOL));
        var codec=new BackupManifestCodec();
        var decoded=codec.read(codec.write(manifest));
        assertThatCode(() -> required.requireRestore(decoded)).doesNotThrowAnyException();
        assertThatThrownBy(() -> prerequisites(ALL,Map.of("templates",new PluginIdentity(TEMPLATE.id(),TEMPLATE.version(),"b".repeat(64)))).requireRestore(decoded))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact is unavailable");
        assertThatThrownBy(() -> prerequisites(Set.of(),Map.of()).requireRestore(decoded)).hasMessageContaining("artifact is unavailable");
    }
    @Test void legacyArchiveIsReadableButCannotSmuggleUndeclaredArtifactPrerequisites() throws Exception {
        var available=prerequisites(ALL,Map.of());
        assertThatCode(() -> available.requireRestore(manifest(List.of(),Set.of()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> available.requireRestore(manifest(List.of(BackupFeaturePrerequisites.encode(TEMPLATE)),Set.of())))
                .hasMessageContaining("protocol is missing");
        assertThatThrownBy(() -> available.requireRestore(manifest(List.of("taxonomy-plugin-v1:broken"),Set.of(BackupFeaturePrerequisites.PROTOCOL))))
                .hasMessageContaining("Malformed");
    }
    @Test void providerArtifactsAreRequiredButStatelessDynamicFormatsDoNotBlockDataRestore() throws Exception {
        var provider=new PluginIdentity("example.provider","1.0.0","c".repeat(64));
        var dynamic=new PluginIdentity("example.renderer","1.0.0","d".repeat(64));
        var snapshot=new CatalogSnapshot(7,List.of(
                new PluginDescriptor(provider,">=1.0.0",List.of(),Set.of(),PluginMode.STARTUP),
                new PluginDescriptor(dynamic,">=1.0.0",List.of(),Set.of(),PluginMode.DYNAMIC)),List.of());
        var captured=new BackupFeaturePrerequisites(new FeatureAssembly.FeatureSet(ALL),snapshot);
        assertThat(captured.dependencies()).containsExactly(BackupFeaturePrerequisites.encode(provider));
        var manifest=manifest(captured.dependencies(),Set.of(BackupFeaturePrerequisites.PROTOCOL));
        assertThatCode(() -> captured.requireRestore(manifest)).doesNotThrowAnyException();
        assertThatThrownBy(() -> prerequisites(ALL,Map.of()).requireRestore(manifest))
                .hasMessageContaining("example.provider");
    }
    private BackupFeaturePrerequisites prerequisites(Set<String> features,Map<String,PluginIdentity> identities) {
        return new BackupFeaturePrerequisites(new FeatureAssembly.FeatureSet(features,identities));
    }
    private BackupManifest manifest(List<String> dependencies,Set<String> features) throws Exception {
        BackupManifest m;
        try(var stream=getClass().getResourceAsStream("/backup/current-state-v1.json")) {m=new BackupManifestCodec().read(stream.readAllBytes());}
        return new BackupManifest(m.formatVersion(),m.applicationVersion(),m.build(),m.backupId(),m.sourceInstallationId(),m.request(),
                m.captureStartedAt(),m.captureCompletedAt(),m.consistencyEvidence(),features,m.components(),m.repositories(),m.entries(),dependencies,m.omissions());
    }
}
