package ca.int13.librenmsmcp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.config.Config;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyEngineTest {
    record Tool(String name, Access access, boolean destructive, Set<String> tags) implements PolicyEngine.Subject {}

    private static final Tool READ =
            new Tool("devices_list", Access.READ, false, Set.of("librenms", "devices", "read-only"));
    private static final Tool OPERATE =
            new Tool("alert_acknowledge", Access.OPERATE, false, Set.of("librenms", "alert"));
    private static final Tool ADMIN = new Tool("device_update", Access.ADMIN, false, Set.of("librenms", "devices"));
    private static final Tool DESTRUCTIVE =
            new Tool("device_delete", Access.ADMIN, true, Set.of("librenms", "devices"));

    private static PolicyEngine engine(boolean readOnly, Set<String> disabled, boolean allowDestructive) {
        return new PolicyEngine(new Config.Policy(readOnly, disabled, allowDestructive, Role.READER));
    }

    private static Principal as(Role role) {
        return new Principal("u", "c", role, "test");
    }

    @ParameterizedTest(name = "{0} → read={1} operate={2} admin={3}")
    @CsvSource({"READER,true,false,false", "OPERATOR,true,true,false", "ADMIN,true,true,true"})
    void rolesAreCumulative(Role role, boolean read, boolean operate, boolean admin) {
        PolicyEngine p = engine(false, Set.of(), false);
        assertThat(p.decide(as(role), READ).allowed()).isEqualTo(read);
        assertThat(p.decide(as(role), OPERATE).allowed()).isEqualTo(operate);
        assertThat(p.decide(as(role), ADMIN).allowed()).isEqualTo(admin);
    }

    @Test
    void readOnlyModeOverridesEveryRole() {
        PolicyEngine p = engine(true, Set.of(), true);
        assertThat(p.decide(as(Role.ADMIN), READ).allowed()).isTrue();
        Decision d = p.decide(as(Role.ADMIN), OPERATE);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("the server is in read-only mode");
        assertThat(p.decide(as(Role.ADMIN), DESTRUCTIVE).allowed()).isFalse();
    }

    @Test
    void disabledTagsHideMatchingTools() {
        PolicyEngine p = engine(false, Set.of("devices"), true);
        assertThat(p.decide(as(Role.ADMIN), READ).reason()).isEqualTo("the tool is disabled on this server");
        assertThat(p.decide(as(Role.ADMIN), OPERATE).allowed()).isTrue();
    }

    @Test
    void destructiveToolsNeedTheServerSwitchAndAdmin() {
        assertThat(engine(false, Set.of(), false)
                        .decide(as(Role.ADMIN), DESTRUCTIVE)
                        .reason())
                .isEqualTo("destructive tools are disabled on this server");
        assertThat(engine(false, Set.of(), true)
                        .decide(as(Role.ADMIN), DESTRUCTIVE)
                        .allowed())
                .isTrue();
        assertThat(engine(false, Set.of(), true)
                        .decide(as(Role.OPERATOR), DESTRUCTIVE)
                        .allowed())
                .isFalse();
    }

    @Test
    void noPrincipalIsAlwaysDenied() {
        assertThat(engine(false, Set.of(), true).decide(null, READ).allowed()).isFalse();
    }
}
