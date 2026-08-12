package com.taxonomy.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Reads repository files and paths from one Git revision without invoking a shell. */
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

    @Override
    public Set<String> paths(String revision, String repositoryPathPrefix) {
        ProcessBuilder builder = new ProcessBuilder(
                "git", "-c", "core.quotepath=false",
                "ls-tree", "-r", "-z", "--name-only",
                revision, "--", repositoryPathPrefix);
        builder.directory(repositoryRoot.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalArgumentException(
                        "git ls-tree failed for " + revision + ":"
                                + repositoryPathPrefix + ": " + output.strip());
            }
            Set<String> paths = new TreeSet<>();
            int start = 0;
            while (start < output.length()) {
                int end = output.indexOf(0, start);
                if (end < 0) {
                    end = output.length();
                }
                if (end > start) {
                    paths.add(output.substring(start, end));
                }
                start = end + 1;
            }
            return Collections.unmodifiableSet(paths);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "cannot execute git ls-tree for " + revision + ":"
                            + repositoryPathPrefix, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while listing Git baseline " + revision, error);
        }
    }
}
