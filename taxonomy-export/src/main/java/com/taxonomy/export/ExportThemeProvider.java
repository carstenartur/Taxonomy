package com.taxonomy.export;

/** Supplies presentation only; renderers capture one immutable theme per document. */
@FunctionalInterface
public interface ExportThemeProvider {
    ExportTheme current();

    static ExportThemeProvider defaults() {
        return ExportTheme::defaults;
    }
}
