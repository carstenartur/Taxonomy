package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class BackupCoverageInventoryTest {
    @Test void classifiesEveryApplicationEntityAndRefusesNewUnclassifiedData() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> persistent = new HashSet<>();
        scanner.findCandidateComponents("com.taxonomy").forEach(bean -> persistent.add(bean.getBeanClassName()));
        assertThat(persistent).isNotEmpty();
        var inventory = BackupCoverageInventory.load();
        assertThatCode(() -> inventory.requireClassified(persistent)).doesNotThrowAnyException();
        persistent.add("com.taxonomy.future.UnclassifiedPersistentData");
        assertThatThrownBy(() -> inventory.requireClassified(persistent))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UnclassifiedPersistentData");
    }
    @Test void includesStorageOutsideJpaWithoutTreatingGitTablesAsSecondTruth() throws Exception {
        var categories = BackupCoverageInventory.load().categories();
        assertThat(categories).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo("storage.jgit.objects-refs-reflogs");
            assertThat(c.rule()).isEqualTo(BackupStorageRule.GIT_PRIMARY);
        });
        assertThat(categories).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo("external.identity-providers");
            assertThat(c.rule()).isEqualTo(BackupStorageRule.EXTERNAL_DEPENDENCY);
        });
    }
}
