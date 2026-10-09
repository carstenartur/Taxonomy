package com.taxonomy.templates.api;

import java.io.IOException;
import java.io.InputStream;

/** Opens a fresh stream for a contributed seed; the caller closes it. */
@FunctionalInterface
public interface TemplateContent {
    InputStream open() throws IOException;
}
