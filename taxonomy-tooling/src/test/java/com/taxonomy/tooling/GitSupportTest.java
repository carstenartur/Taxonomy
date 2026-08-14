package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class GitSupportTest {

    @Test
    void drainsLargeStandardOutputAndErrorStreamsConcurrently(
            @TempDir Path root) throws Exception {
        TestGit.run(root, "init", "-q");
        String noisyAlias = "alias.noisy=!f() { i=0; "
                + "while [ \"$i\" -lt 20000 ]; do "
                + "printf \"stdout-%s\\n\" \"$i\"; "
                + "printf \"stderr-%s\\n\" \"$i\" >&2; "
                + "i=$((i+1)); done; }; f";

        GitSupport.Result result = assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                () -> GitSupport.run(root, "-c", noisyAlias, "noisy"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("stdout-19999");
        assertThat(result.stderr()).contains("stderr-19999");
    }
}
