package com.nato.taxonomy.dsl.ast;

/**
 * Tracks the source file location of a parsed DSL element.
 */
public record SourceLocation(String file, int line, int column) {

    public SourceLocation(int line, int column) {
        this(null, line, column);
    }

    @Override
    public String toString() {
        if (file != null) {
            return file + ":" + line + ":" + column;
        }
        return line + ":" + column;
    }
}
