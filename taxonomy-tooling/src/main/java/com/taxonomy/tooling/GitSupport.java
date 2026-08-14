package com.taxonomy.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class GitSupport {

    private GitSupport() {
    }

    static String require(Path root, String... arguments) {
        Result result = run(root, arguments);
        if (result.exitCode() != 0) {
            String detail = !result.stderr().isBlank()
                    ? result.stderr().strip()
                    : !result.stdout().isBlank()
                            ? result.stdout().strip()
                            : "unknown Git error";
            throw new IllegalArgumentException(
                    "git " + String.join(" ", arguments) + " failed: " + detail);
        }
        return result.stdout();
    }

    static Result run(Path root, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .start();
            String stdout = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(
                    process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), stdout, stderr);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Cannot execute Git: " + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Git command was interrupted", error);
        }
    }

    record Result(int exitCode, String stdout, String stderr) {
    }
}
