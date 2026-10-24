package ca.int13.librenmsmcp;

import ca.int13.librenmsmcp.authz.PolicyEngine;
import ca.int13.librenmsmcp.authz.Principal;
import ca.int13.librenmsmcp.config.Config;
import ca.int13.librenmsmcp.config.ConfigException;
import ca.int13.librenmsmcp.config.ConfigLoader;
import ca.int13.librenmsmcp.http.HttpServer;
import ca.int13.librenmsmcp.librenms.LibreNmsClient;
import ca.int13.librenmsmcp.mcp.McpServers;
import ca.int13.librenmsmcp.mcp.ToolDispatcher;
import ca.int13.librenmsmcp.observability.MetricsServer;
import ca.int13.librenmsmcp.observability.Telemetry;
import ca.int13.librenmsmcp.tools.ToolModule;
import ca.int13.librenmsmcp.tools.ToolRegistry;
import ca.int13.librenmsmcp.tools.alerts.AlertTools;
import ca.int13.librenmsmcp.tools.bills.BillTools;
import ca.int13.librenmsmcp.tools.devices.DeviceTools;
import ca.int13.librenmsmcp.tools.graphs.GraphTools;
import ca.int13.librenmsmcp.tools.health.HealthTools;
import ca.int13.librenmsmcp.tools.inventory.InventoryTools;
import ca.int13.librenmsmcp.tools.locations.LocationTools;
import ca.int13.librenmsmcp.tools.logs.LogTools;
import ca.int13.librenmsmcp.tools.network.NetworkTools;
import ca.int13.librenmsmcp.tools.oxidized.OxidizedTools;
import ca.int13.librenmsmcp.tools.pollers.PollerTools;
import ca.int13.librenmsmcp.tools.ports.PortTools;
import ca.int13.librenmsmcp.tools.portsecurity.PortSecurityTools;
import ca.int13.librenmsmcp.tools.services.ServiceTools;
import ca.int13.librenmsmcp.tools.system.SystemTools;
import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        LibreNmsClient.disableJdkRetries();
        Config config;
        try {
            config = ConfigLoader.fromEnvironment();
        } catch (ConfigException e) {
            log.error(e.getMessage());
            System.exit(2);
            return;
        }
        setLogLevel(config.logLevel());
        String version = version();
        log.info(
                "LibreNMS MCP Server {} starting: transport={} librenms={} policy={} search={}",
                version,
                config.transport(),
                config.librenms(),
                config.policy(),
                config.search());

        var client = new LibreNmsClient(config.librenms());
        ToolRegistry registry = ToolRegistry.build(modules(client));
        var dispatcher = new ToolDispatcher(registry, new PolicyEngine(config.policy()));
        log.info(
                "{} tools served, {} upstream tools not yet ported",
                registry.tools().size(),
                registry.unimplemented().size());

        var stopped = new CountDownLatch(1);
        switch (config.transport()) {
            case HTTP -> {
                MetricsServer metrics = config.http().metricsPort() == 0
                        ? null
                        : new MetricsServer(config.http().host(), config.http().metricsPort(), Telemetry.prometheus());
                if (metrics != null) {
                    log.info("Metrics on http://{}:{}/metrics", config.http().host(), metrics.port());
                }
                var server = new HttpServer(config, dispatcher, client, version).start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    server.close();
                    if (metrics != null) {
                        metrics.close();
                    }
                    stopped.countDown();
                }));
            }
            case STDIO -> {
                var server = McpServers.stdio(
                        dispatcher,
                        config.search(),
                        Principal.local(config.policy().localRole()),
                        version,
                        stopped::countDown);
                stopped.await();
                log.info("stdin closed; shutting down");
                server.close();
                // SDK and HTTP client threads are non-daemon; the session is over, so end the process.
                System.exit(0);
            }
        }
        stopped.await();
    }

    /** The tool modules served; one per upstream module as they are ported. */
    public static List<ToolModule> modules(LibreNmsClient client) {
        return List.of(
                new SystemTools(client),
                new DeviceTools(client),
                new HealthTools(client),
                new InventoryTools(client),
                new PollerTools(client),
                new PortSecurityTools(client),
                new LocationTools(client),
                new LogTools(client),
                new ServiceTools(client),
                new OxidizedTools(client),
                new NetworkTools(client),
                new AlertTools(client),
                new BillTools(client),
                new GraphTools(client),
                new PortTools(client));
    }

    private static void setLogLevel(String level) {
        var root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel(level, Level.INFO));
    }

    static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/librenms-mcp.properties")) {
            var p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "0.0.0");
        } catch (IOException e) {
            return "0.0.0";
        }
    }
}
