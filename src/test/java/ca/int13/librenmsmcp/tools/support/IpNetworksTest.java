package ca.int13.librenmsmcp.tools.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Expected values are Python {@code ipaddress.ip_network(q, strict=False)} results. */
class IpNetworksTest {
    @ParameterizedTest
    @CsvSource({
        "192.0.2.1, 4, 192.0.2.1, 32",
        "10.0.0.5/24, 4, 10.0.0.0, 24",
        "10.0.0.0/255.255.255.0, 4, 10.0.0.0, 24",
        "10.0.0.0/0.0.0.255, 4, 10.0.0.0, 24",
        "10.0.0.0/024, 4, 10.0.0.0, 24",
        "255.255.255.255/0, 4, 0.0.0.0, 0",
        "fe80::1, 6, , 128",
        "::ffff:1.2.3.4/120, 6, , 120",
    })
    void acceptsWhatPythonAccepts(String query, int version, String address, int prefix) {
        IpNetworks.Network n = IpNetworks.parse(query).orElseThrow();
        assertThat(n.version()).isEqualTo(version);
        assertThat(n.prefixLength()).isEqualTo(prefix);
        if (version == 4) {
            assertThat(n.networkAddress()).isEqualTo(address);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "010.0.0.1",
                "1.2.3",
                "10.0.0.0/33",
                "00:11:22:33:44:55",
                "10.0.0.0/",
                "/24",
                "1.2.3.4/32/1",
                " 1.2.3.4",
                "10.0.0.0/255.0.255.0",
                "1.2.3.4/+8",
                "١.2.3.4",
                "all",
                "fe80::/129",
                "[::1]"
            })
    void rejectsWhatPythonRejects(String query) {
        assertThat(IpNetworks.parse(query)).isEmpty();
    }
}
