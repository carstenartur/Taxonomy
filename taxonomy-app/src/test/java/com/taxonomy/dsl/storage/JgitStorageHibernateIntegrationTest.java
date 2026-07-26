package com.taxonomy.dsl.storage;

import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import jakarta.persistence.EntityManagerFactory;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ReflogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration coverage for Taxonomy consuming jgit-storage-hibernate-core. */
@SpringBootTest
@TestPropertySource(properties = {
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key=",
        "embedding.enabled=false"
})
class JgitStorageHibernateIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private HibernateRepositoryFactory storageFactory;

    @Test
    void registersAllCoreStorageEntitiesInTheApplicationPersistenceUnit() {
        Set<Class<?>> managedTypes = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entityType -> entityType.getJavaType())
                .collect(Collectors.toSet());

        assertThat(managedTypes).containsAll(CoreEntities.annotatedClasses());
    }

    @Test
    void persistsDslAndQueryableReflogAcrossRepositoryHandleReopen() throws Exception {
        RepositoryName repositoryName = uniqueRepositoryName("taxonomy-it-reopen-");
        String commitId;
        try {
            try (DslGitRepository repository =
                         new DslGitRepository(storageFactory, repositoryName.value())) {
                commitId = repository.commitDsl(
                        "draft", sampleDsl("first"), "integration@test", "initial version");

                ReflogEntry reflog = repository.getGitRepository()
                        .getReflogReader(Constants.R_HEADS + "draft")
                        .getLastEntry();
                assertThat(reflog).isNotNull();
                assertThat(reflog.getNewId().name()).isEqualTo(commitId);
                assertThat(reflog.getComment()).contains("commit: initial version");
            }

            try (DslGitRepository reopened =
                         new DslGitRepository(storageFactory, repositoryName.value())) {
                assertThat(reopened.getHeadCommit("draft")).isEqualTo(commitId);
                assertThat(reopened.getDslAtHead("draft")).isEqualTo(sampleDsl("first"));
                assertThat(reopened.getDslHistory("draft")).hasSize(1);
            }
        } finally {
            storageFactory.deleteRepository(repositoryName);
        }
    }

    @Test
    void repositoryDeletionIsScopedToOneLogicalRepository() throws Exception {
        RepositoryName firstName = uniqueRepositoryName("taxonomy-it-first-");
        RepositoryName secondName = uniqueRepositoryName("taxonomy-it-second-");
        try {
            try (DslGitRepository first = new DslGitRepository(storageFactory, firstName.value());
                 DslGitRepository second = new DslGitRepository(storageFactory, secondName.value())) {
                first.commitDsl("draft", sampleDsl("first"), "integration@test", "first");
                second.commitDsl("draft", sampleDsl("second"), "integration@test", "second");
            }

            RepositoryDeletionResult deletion = storageFactory.deleteRepository(firstName);
            assertThat(deletion.packRows()).isPositive();
            assertThat(deletion.reflogRows()).isPositive();

            try (DslGitRepository deleted = new DslGitRepository(storageFactory, firstName.value());
                 DslGitRepository survivor = new DslGitRepository(storageFactory, secondName.value())) {
                assertThat(deleted.getBranchNames()).isEmpty();
                assertThat(survivor.getDslAtHead("draft")).isEqualTo(sampleDsl("second"));
            }
        } finally {
            storageFactory.deleteRepository(firstName);
            storageFactory.deleteRepository(secondName);
        }
    }

    private static RepositoryName uniqueRepositoryName(String prefix) {
        return new RepositoryName(prefix + UUID.randomUUID());
    }

    private static String sampleDsl(String title) {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.0";
                  namespace: "integration";
                }

                element CP-1023 type Capability {
                  title: "%s";
                }
                """.formatted(title);
    }
}
