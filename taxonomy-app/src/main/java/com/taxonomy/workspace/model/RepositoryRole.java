package com.taxonomy.workspace.model;

/** Repository-scoped authorization role, ordered by capability rather than application role. */
public enum RepositoryRole {
    READER,
    CONTRIBUTOR,
    MAINTAINER,
    OWNER;

    public boolean grants(RepositoryRole requiredRole) {
        if (requiredRole == null) {
            return false;
        }
        return switch (requiredRole) {
            case READER -> true;
            case CONTRIBUTOR -> this == CONTRIBUTOR
                    || this == MAINTAINER
                    || this == OWNER;
            case MAINTAINER -> this == MAINTAINER || this == OWNER;
            case OWNER -> this == OWNER;
        };
    }
}
