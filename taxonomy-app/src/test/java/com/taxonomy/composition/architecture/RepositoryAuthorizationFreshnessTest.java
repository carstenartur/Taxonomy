package com.taxonomy.composition.architecture;

import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryMembership;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Retained first-level caches must not supply the final policy decision or overwrite newer catalogue data. */
@SpringBootTest
class RepositoryAuthorizationFreshnessTest {
    @Autowired SystemRepositoryService repositories;
    @Autowired RepositoryMembershipService memberships;
    @Autowired EntityManagerFactory entityManagers;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshOwnerReadBypassesRetainedMetadataAndMembershipWithoutClearingTheOuterContext(boolean archive) {
        var fixture = repository();
        try (var retained = entityManagers.createEntityManager()) {
            var captured = retained.find(SystemRepository.class, fixture.repository().getId());
            var capturedMember = retained.find(RepositoryMembership.class, fixture.member().getId());
            // Change committed state before opening the retained transaction, so this is deterministic without worker locks.
            if (archive) {
                var changed = repositories.getRepository(captured.getRepositoryId());
                changed.setLifecycleState(RepositoryLifecycleState.ARCHIVED);
                repositories.save(changed);
            } else {
                memberships.assignRole(captured.getRepositoryId(), "alice", RepositoryRole.READER, "owner");
            }

            inRetainedTransaction(retained, () -> {
                assertThat(captured.getLifecycleState()).isEqualTo(RepositoryLifecycleState.ACTIVE);
                assertThat(capturedMember.getRole()).isEqualTo(RepositoryRole.CONTRIBUTOR);
                assertThat(memberships.canContribute(captured, "alice")).as("the retained context still contains the obsolete policy")
                        .isTrue();

                repositories.readCurrentAuthorization(captured.getRepositoryId(), current -> {
                    assertThat(current).isNotSameAs(captured);
                    assertThat(memberships.canContribute(current, "alice")).isFalse();
                    assertThat(memberships.canRead(current, "alice")).isEqualTo(!archive);
                });

                assertThat(retained.find(SystemRepository.class, captured.getId())).isSameAs(captured);
                assertThat(retained.find(RepositoryMembership.class, capturedMember.getId())).isSameAs(capturedMember);
            });
        }
    }

    @Test
    void timestampOnlyPublicationUpdatePreservesConcurrentPolicyAndDoesNotDirtyTheCapturedEntity() {
        var fixture = repository();
        Instant publishedAt = Instant.parse("2026-10-30T12:00:00Z");
        try (var retained = entityManagers.createEntityManager()) {
            var captured = retained.find(SystemRepository.class, fixture.repository().getId());
            var changed = repositories.getRepository(captured.getRepositoryId());
            changed.setVisibility(RepositoryVisibility.PUBLIC);
            changed.setDescription("Policy edited after the source snapshot");
            repositories.save(changed);

            inRetainedTransaction(retained, () -> {
                repositories.recordLastPush(captured.getRepositoryId(), publishedAt);
                assertThat(captured.getVisibility()).isEqualTo(RepositoryVisibility.PRIVATE);
                assertThat(captured.getLastPushAt()).isNull();
                assertThat(retained.find(SystemRepository.class, captured.getId())).isSameAs(captured);
            });
        }
        var actual = repositories.getRepository(fixture.repository().getRepositoryId());
        assertThat(actual.getVisibility()).isEqualTo(RepositoryVisibility.PUBLIC);
        assertThat(actual.getDescription()).isEqualTo("Policy edited after the source snapshot");
        assertThat(actual.getLastPushAt()).isEqualTo(publishedAt);
    }

    private Fixture repository() {
        String id = UUID.randomUUID().toString();
        var repository = repositories.createCentralRepository("Authority freshness " + id, "authority-" + id,
                "Original policy", RepositoryVisibility.PRIVATE, "owner", "draft");
        repository = repositories.markProvisioningReady(repository.getRepositoryId());
        var member = memberships.assignRole(repository.getRepositoryId(), "alice", RepositoryRole.CONTRIBUTOR, "owner");
        return new Fixture(repository, member);
    }

    private void inRetainedTransaction(EntityManager retained, Runnable action) {
        assertThat(TransactionSynchronizationManager.hasResource(entityManagers)).isFalse();
        TransactionSynchronizationManager.bindResource(entityManagers, new EntityManagerHolder(retained));
        try {
            new TransactionTemplate(transactions).executeWithoutResult(ignored -> action.run());
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagers);
        }
    }

    private record Fixture(SystemRepository repository, RepositoryMembership member) { }
}
