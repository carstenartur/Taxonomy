package com.taxonomy.extension.api.plugin;

import com.taxonomy.shared.extension.TaxonomyExtension;
import java.util.List;

/** ServiceLoader entry point. Implementations must not start unowned background work. */
public interface TaxonomyPlugin extends AutoCloseable {
    List<TaxonomyExtension> extensions();
    /** Release owned resources after all admitted calls have completed; never delete user data. */
    @Override default void close() { }
}
