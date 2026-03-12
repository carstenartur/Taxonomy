/*******************************************************************************
 * Copyright (c) 2026 Carsten Hammer.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Carsten Hammer
 *******************************************************************************/
package com.taxonomy.dsl.storage.jgit;

import java.io.IOException;

import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.ReflogReader;
import org.hibernate.SessionFactory;

/**
 * A Git repository stored in a relational database via Hibernate.
 * <p>
 * This implementation extends the DFS (Distributed File System) repository
 * abstraction, replacing in-memory or filesystem storage with database-backed
 * storage using Hibernate ORM.
 * <p>
 * Objects, refs, and pack data are stored in database tables. The reftable
 * format is used for reference storage, persisted as pack extensions in the
 * database.
 * <p>
 * Adapted from {@code sandbox-jgit-storage-hibernate} module
 * in the {@code carstenartur/sandbox} repository.
 */
public class HibernateRepository extends DfsRepository {

    private final HibernateObjDatabase objdb;
    private final HibernateRefDatabase refdb;
    private final SessionFactory sessionFactory;
    private final String repositoryName;

    /**
     * Create a new database-backed repository.
     *
     * @param sessionFactory  Hibernate session factory for database access
     * @param repositoryName  logical name to partition data in the database
     */
    public HibernateRepository(SessionFactory sessionFactory, String repositoryName) {
        super(new Builder(repositoryName));
        this.sessionFactory = sessionFactory;
        this.repositoryName = repositoryName;
        this.objdb = new HibernateObjDatabase(this, new DfsReaderOptions(),
                sessionFactory, repositoryName);
        this.refdb = new HibernateRefDatabase(this);
    }

    @Override
    public HibernateObjDatabase getObjectDatabase() {
        return objdb;
    }

    @Override
    public RefDatabase getRefDatabase() {
        return refdb;
    }

    /**
     * Get the repository name used for database partitioning.
     *
     * @return the repository name
     */
    public String getRepositoryName() {
        return repositoryName;
    }

    /**
     * Get the Hibernate session factory.
     *
     * @return the session factory
     */
    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    @Override
    public ReflogReader getReflogReader(String refName) throws IOException {
        // Simplified: reflog entries are stored in the reftable pack extension
        // in the database via HibernateObjDatabase. Full reflog support
        // requires HibernateReflogReader (see sandbox module).
        return null;
    }

    /** Internal builder for DfsRepository. */
    private static class Builder extends
            org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder<Builder, HibernateRepository> {

        Builder(String name) {
            setRepositoryDescription(new DfsRepositoryDescription(name));
        }

        @Override
        public HibernateRepository build() {
            throw new UnsupportedOperationException("Use HibernateRepository(SessionFactory, String) constructor");
        }
    }
}
