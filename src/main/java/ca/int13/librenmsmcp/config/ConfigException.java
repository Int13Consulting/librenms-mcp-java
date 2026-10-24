package ca.int13.librenmsmcp.config;

import java.util.List;

/** One or more configuration problems found at startup. */
public final class ConfigException extends RuntimeException {
    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("Invalid configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
