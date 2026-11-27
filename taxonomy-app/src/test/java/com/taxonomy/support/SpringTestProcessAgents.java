package com.taxonomy.support;

import org.mockito.Mockito;

import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JVM agents for child processes that construct Spring test contexts with Mockito overrides. */
public final class SpringTestProcessAgents {
    private SpringTestProcessAgents() { }

    public static List<String> arguments() throws URISyntaxException {
        var agents = new ArrayList<String>();
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-javaagent:")) {
                String jar = argument.substring("-javaagent:".length()).split("=", 2)[0];
                if (Path.of(jar).getFileName().toString().contains("jacoco")) agents.add(argument);
            }
        }
        // Load the exact Mockito version on the child test classpath before any mocks are created.
        Path mockito = Path.of(Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        agents.add("-javaagent:" + mockito);
        return agents;
    }
}
