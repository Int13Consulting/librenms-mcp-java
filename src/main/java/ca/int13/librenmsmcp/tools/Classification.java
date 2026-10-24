package ca.int13.librenmsmcp.tools;

import ca.int13.librenmsmcp.authz.Access;

/** The approved security classification of a tool, from {@code contracts/tool-classification.json}. */
public record Classification(Access access, boolean destructive, boolean retrySafe) {}
