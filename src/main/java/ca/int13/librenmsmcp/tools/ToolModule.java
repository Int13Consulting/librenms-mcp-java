package ca.int13.librenmsmcp.tools;

/** A group of tool handlers, one per upstream module. */
public interface ToolModule {
    void register(ToolBinder binder);
}
