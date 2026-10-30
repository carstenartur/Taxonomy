package com.taxonomy.workspace.repository;

import org.springframework.orm.jpa.JpaTransactionManager;

/** Shared real database wiring for the workspace-owned repository mutation fence. */
public final class RepositoryMutationFenceFixture {
    private RepositoryMutationFenceFixture() { }

    /** Wire the production repository owner against this real test database, in the caller's transaction. */
    public static com.taxonomy.workspace.service.RepositoryMutationFence repositoryFence(
            jakarta.persistence.EntityManagerFactory factory, String... repositoryIds) {
        var transactions = new JpaTransactionManager(factory);
        var em = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (String repositoryId : repositoryIds) {
                if (!em.createQuery("select r from SystemRepository r where r.repositoryId=:id")
                        .setParameter("id", repositoryId).getResultList().isEmpty()) continue;
                var owner = new com.taxonomy.workspace.model.SystemRepository();
                owner.setRepositoryId(repositoryId);
                owner.setStorageRepositoryName("fixture-" + repositoryId);
                owner.setTopologyMode(com.taxonomy.workspace.model.RepositoryTopologyMode.INTERNAL_SHARED);
                owner.setCreatedAt(java.time.Instant.EPOCH);
                em.persist(owner);
            }
        });
        var repository = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em)
                .getRepository(com.taxonomy.workspace.repository.SystemRepositoryRepository.class);
        var service = new com.taxonomy.workspace.service.SystemRepositoryService(repository);
        var proxy = new org.springframework.aop.framework.ProxyFactory(service);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (com.taxonomy.workspace.service.RepositoryMutationFence) proxy.getProxy();
    }
}
