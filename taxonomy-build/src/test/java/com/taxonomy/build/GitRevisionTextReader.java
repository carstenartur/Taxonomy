package com.taxonomy.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/** Reads one repository file from a Git revision without invoking a shell. */
final class GitRevisionTextReader implements FrontendApiBoundaryPolicy.RevisionTextReader {

    private final Path repositoryRoot;

    GitRevisionTextReader(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    @Override
    public Optional<String> read(String revision, String repositoryPath) {
        ProcessBuilder builder = new ProcessBuilder(
                "git", "show", revision + ":" + repositoryPath);
        builder.directory(repositoryRoot.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return Optional.of(output);
            }
            if (output.contains("does not exist")
                    || output.contains("exists on disk, but not in")) {
                return Optional.empty();
            }
            throw new IllegalArgumentException(
                    "git show failed for " + revision + ":" + repositoryPath
                            + ": " + output.strip());
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot execute git show for " + revision + ":"
                            + repositoryPath, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while reading Git baseline " + revision, error);
        }
    }
}
