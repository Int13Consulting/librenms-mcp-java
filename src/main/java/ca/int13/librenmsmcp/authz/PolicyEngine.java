package ca.int13.librenmsmcp.authz;

import ca.int13.librenmsmcp.config.Config;
import java.util.Collections;
import java.util.Set;

/**
 * Decides whether a principal may see and call a tool. The same decision governs listing, search and
 * every call, so a tool hidden from a caller can never be invoked by them.
 *
 * <p>Checks run in a fixed order: read-only mode, disabled tags, role, destructive gate. A null
 * principal is always denied.
 */
public final class PolicyEngine {
    /** What the policy needs to know about a tool. */
    public interface Subject {
        String name();

        Access access();

        boolean destructive();

        Set<String> tags();
    }

    private final Config.Policy policy;

    public PolicyEngine(Config.Policy policy) {
        this.policy = policy;
    }

    public Decision decide(Principal principal, Subject tool) {
        if (principal == null) {
            return Decision.deny("not authenticated");
        }
        if (policy.readOnly() && tool.access() != Access.READ) {
            return Decision.deny("the server is in read-only mode");
        }
        if (!Collections.disjoint(policy.disabledTags(), tool.tags())) {
            return Decision.deny("the tool is disabled on this server");
        }
        if (!principal.role().permits(tool.access())) {
            return Decision.deny(
                    "requires the " + tool.access().name().toLowerCase(java.util.Locale.ROOT) + " permission");
        }
        if (tool.destructive() && !policy.allowDestructive()) {
            return Decision.deny("destructive tools are disabled on this server");
        }
        return Decision.ALLOW;
    }
}
