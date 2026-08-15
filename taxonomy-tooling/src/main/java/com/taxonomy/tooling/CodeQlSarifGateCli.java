package com.taxonomy.tooling;

import java.nio.file.Path;

/** Standalone dependency-free entry point for the CodeQL SARIF release gate. */
public final class CodeQlSarifGateCli {

    private CodeQlSarifGateCli() {
    }

    public static void main(String[] arguments) {
        int exitCode = CodeQlSarifGate.run(
                arguments,
                Path.of("."),
                System.out,
                System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
