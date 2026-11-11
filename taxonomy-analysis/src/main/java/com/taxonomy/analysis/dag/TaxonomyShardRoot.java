package com.taxonomy.analysis.dag;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated taxonomy root identity used for execution routing and data sharding.
 *
 * <p>The code is the catalogue's virtual root code (see the eight default roots
 * {@code BP, BR, CP, CI, CO, CR, IP, UA}). Membership in the active catalogue is
 * validated by {@link com.taxonomy.dto.AnalysisScope#validateRoots}; this type only
 * guarantees a bounded, destination-safe identifier so roots are never carried as
 * arbitrary free-form strings inside task contracts.</p>
 */
public record TaxonomyShardRoot(String code) implements Comparable<TaxonomyShardRoot> {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    /** The eight catalogue roots in their catalogue order. */
    public static final List<TaxonomyShardRoot> DEFAULT_ROOTS =
            com.taxonomy.dsl.model.CatalogueDefinition.standard().rootCodes().stream().map(TaxonomyShardRoot::of).toList();

    public boolean catalogueIdentifier() { return code.matches("[A-Z][A-Z0-9_]{0,63}"); }

    public TaxonomyShardRoot {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid taxonomy root identifier");
        }
    }

    public static TaxonomyShardRoot of(String code) {
        return new TaxonomyShardRoot(code);
    }

    public boolean defaultCatalogueRoot() {
        return DEFAULT_ROOTS.contains(this);
    }

    @Override
    public int compareTo(TaxonomyShardRoot other) {
        return code.compareTo(other.code);
    }

    @Override
    public String toString() {
        return code;
    }
}
