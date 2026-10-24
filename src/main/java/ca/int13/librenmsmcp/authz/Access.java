package ca.int13.librenmsmcp.authz;

/** The minimum role a tool requires, as classified in {@code contracts/tool-classification.json}. */
public enum Access {
    READ,
    OPERATE,
    ADMIN
}
