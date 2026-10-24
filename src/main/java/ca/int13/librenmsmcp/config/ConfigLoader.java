package ca.int13.librenmsmcp.config;

import ca.int13.librenmsmcp.authz.Role;
import ca.int13.librenmsmcp.config.Config.AuthMode;
import ca.int13.librenmsmcp.config.Config.Transport;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads {@link Config} from environment variables. Any secret may instead be given as a file path in
 * {@code <NAME>_FILE} (Docker/Kubernetes secrets). All problems are collected and reported together
 * so a misconfigured deployment fails once with the full list.
 */
public final class ConfigLoader {
    static final int MIN_API_KEY_LENGTH = 32;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "[::1]", "localhost");
    private static final java.util.regex.Pattern GUID =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");

    private final Map<String, String> env;
    private final List<String> problems = new ArrayList<>();

    private ConfigLoader(Map<String, String> env) {
        this.env = env;
    }

    public static Config fromEnvironment() {
        return load(System.getenv());
    }

    public static Config load(Map<String, String> env) {
        return new ConfigLoader(env).build();
    }

    private Config build() {
        if (present("MCP_HTTP_BEARER_TOKEN")) {
            problems.add("MCP_HTTP_BEARER_TOKEN is not supported; set AUTH_MODE=apikey and MCP_API_KEY instead");
        }

        var librenms = new Config.LibreNms(
                librenmsUrl(),
                secret("LIBRENMS_TOKEN").orElseGet(() -> {
                    problems.add("LIBRENMS_TOKEN (or LIBRENMS_TOKEN_FILE) is required");
                    return "";
                }),
                bool("LIBRENMS_VERIFY_SSL", true),
                Duration.ofSeconds(positiveInt("LIBRENMS_TIMEOUT", 30)));

        Transport transport = transport();
        Config.Http http = http(transport);
        var policy = new Config.Policy(
                bool("READ_ONLY_MODE", true),
                csv("DISABLED_TAGS"),
                bool("ALLOW_DESTRUCTIVE", false),
                role("MCP_LOCAL_ROLE", Role.READER));

        Config.Search search = search();
        String format = value("LOG_FORMAT").orElse("text").toLowerCase(Locale.ROOT);
        if (!format.equals("text") && !format.equals("json")) {
            problems.add("LOG_FORMAT must be text or json, got: " + format);
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new Config(librenms, transport, http, policy, search, logLevel());
    }

    private URI librenmsUrl() {
        String raw = value("LIBRENMS_URL").orElse(null);
        if (raw == null) {
            problems.add("LIBRENMS_URL is required");
            return URI.create("http://invalid");
        }
        URI uri;
        try {
            uri = new URI(raw.replaceAll("/+$", ""));
        } catch (URISyntaxException e) {
            problems.add("LIBRENMS_URL is not a valid URL: " + raw);
            return URI.create("http://invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null) {
            problems.add("LIBRENMS_URL must be an http(s) URL with a host: " + raw);
        } else if (scheme.equals("http") && !bool("LIBRENMS_ALLOW_HTTP", false)) {
            problems.add("LIBRENMS_URL uses plain http, which sends the API token unencrypted;"
                    + " use https or set LIBRENMS_ALLOW_HTTP=true");
        }
        return uri;
    }

    private Transport transport() {
        String raw = value("MCP_TRANSPORT").orElse("http").toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "http", "streamable-http" -> Transport.HTTP;
            case "stdio" -> Transport.STDIO;
            case "sse" -> {
                problems.add("MCP_TRANSPORT=sse is not supported (deprecated in the MCP spec); use http or stdio");
                yield Transport.HTTP;
            }
            default -> {
                problems.add("MCP_TRANSPORT must be http (alias streamable-http) or stdio, got: " + raw);
                yield Transport.HTTP;
            }
        };
    }

    private Config.Http http(Transport transport) {
        String host = value("MCP_HTTP_HOST").orElse("127.0.0.1");
        int port = intValue("MCP_HTTP_PORT", 8000);
        if (port < 1 || port > 65535) {
            problems.add("MCP_HTTP_PORT must be between 1 and 65535, got: " + port);
        }
        Optional<String> apiKey = secret("MCP_API_KEY");
        Set<AuthMode> modes = authModes(transport);
        if (transport == Transport.HTTP) {
            if (modes.contains(AuthMode.NONE) && !LOOPBACK_HOSTS.contains(host)) {
                problems.add("AUTH_MODE=none is only allowed when MCP_HTTP_HOST is a loopback address, got: " + host);
            }
            if (modes.contains(AuthMode.APIKEY)) {
                if (apiKey.isEmpty()) {
                    problems.add("AUTH_MODE=apikey requires MCP_API_KEY (or MCP_API_KEY_FILE)");
                } else if (apiKey.get().length() < MIN_API_KEY_LENGTH) {
                    problems.add("MCP_API_KEY must be at least " + MIN_API_KEY_LENGTH + " characters");
                }
            }
        }
        Optional<URI> publicUrl = publicUrl(modes.contains(AuthMode.ENTRA) && transport == Transport.HTTP);
        Optional<Config.Entra> entra =
                modes.contains(AuthMode.ENTRA) && transport == Transport.HTTP ? Optional.of(entra()) : Optional.empty();
        return new Config.Http(
                host,
                port,
                modes,
                apiKey,
                role("MCP_API_KEY_ROLE", Role.READER),
                positiveInt("MCP_MAX_REQUEST_BYTES", 1024 * 1024),
                publicUrl,
                entra,
                csv("MCP_ALLOWED_ORIGINS"),
                rateLimit(),
                metricsPort(port));
    }

    private Optional<URI> publicUrl(boolean required) {
        Optional<String> raw = value("MCP_PUBLIC_URL");
        if (raw.isEmpty()) {
            if (required) {
                problems.add("AUTH_MODE=entra requires MCP_PUBLIC_URL (the https URL clients use, without /mcp)");
            }
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.get().replaceAll("/+$", ""));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean loopback = uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost());
            if (uri.getHost() == null || !(scheme.equals("https") || (scheme.equals("http") && loopback))) {
                problems.add("MCP_PUBLIC_URL must be an https URL (http only for loopback), got: " + raw.get());
            } else if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null) {
                problems.add("MCP_PUBLIC_URL must not have a path or query (/mcp is appended), got: " + raw.get());
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            problems.add("MCP_PUBLIC_URL is not a valid URL: " + raw.get());
            return Optional.empty();
        }
    }

    private Config.Entra entra() {
        String tenant = guid("ENTRA_TENANT_ID");
        String client = guid("ENTRA_CLIENT_ID");
        URI authority = URI.create("https://login.microsoftonline.com");
        Optional<String> rawAuthority = value("ENTRA_AUTHORITY");
        if (rawAuthority.isPresent()) {
            try {
                authority = new URI(rawAuthority.get().replaceAll("/+$", ""));
                if (!"https".equalsIgnoreCase(authority.getScheme()) || authority.getHost() == null) {
                    problems.add("ENTRA_AUTHORITY must be an https URL, got: " + rawAuthority.get());
                }
            } catch (URISyntaxException e) {
                problems.add("ENTRA_AUTHORITY is not a valid URL: " + rawAuthority.get());
            }
        }
        Set<String> allowed = csv("ENTRA_ALLOWED_CLIENT_IDS");
        allowed.stream()
                .filter(id -> !GUID.matcher(id).matches())
                .forEach(id -> problems.add("ENTRA_ALLOWED_CLIENT_IDS contains a value that is not a GUID: " + id));
        return new Config.Entra(
                authority,
                tenant,
                client,
                value("ENTRA_REQUIRED_SCOPE").orElse("access_as_user"),
                Duration.ofSeconds(nonNegativeInt("ENTRA_CLOCK_SKEW_SECONDS", 60)),
                allowed);
    }

    private String guid(String name) {
        Optional<String> raw = value(name);
        if (raw.isEmpty()) {
            problems.add("AUTH_MODE=entra requires " + name);
            return "";
        }
        if (!GUID.matcher(raw.get()).matches()) {
            problems.add(name + " must be a GUID, got: " + raw.get());
        }
        return raw.get().toLowerCase(Locale.ROOT);
    }

    private Config.Search search() {
        if (!bool("TOOL_SEARCH_ENABLED", false)) {
            return Config.Search.DISABLED; // like upstream, leftover values of a disabled feature are ignored
        }
        String raw = value("TOOL_SEARCH_STRATEGY").orElse("bm25").toLowerCase(Locale.ROOT);
        var strategy = switch (raw) {
            case "bm25" -> ca.int13.librenmsmcp.search.ToolSearch.Strategy.BM25;
            case "regex" -> ca.int13.librenmsmcp.search.ToolSearch.Strategy.REGEX;
            default -> {
                problems.add("TOOL_SEARCH_STRATEGY must be bm25 or regex, got: " + raw);
                yield ca.int13.librenmsmcp.search.ToolSearch.Strategy.BM25;
            }
        };
        return new Config.Search(true, strategy, positiveInt("TOOL_SEARCH_MAX_RESULTS", 5));
    }

    private int metricsPort(int httpPort) {
        int p = intValue("MCP_METRICS_PORT", 0);
        if (p < 0 || p > 65535) {
            problems.add("MCP_METRICS_PORT must be between 1 and 65535 (or 0 for off), got: " + p);
        } else if (p != 0 && p == httpPort) {
            problems.add("MCP_METRICS_PORT must differ from MCP_HTTP_PORT; metrics are kept off the MCP endpoint");
        }
        return p;
    }

    private Config.RateLimit rateLimit() {
        if (!bool("RATE_LIMIT_ENABLED", true)) {
            return Config.RateLimit.DISABLED;
        }
        return new Config.RateLimit(
                positiveInt("RATE_LIMIT_MAX_REQUESTS", 100),
                Duration.ofMinutes(positiveInt("RATE_LIMIT_WINDOW_MINUTES", 1)));
    }

    private Set<AuthMode> authModes(Transport transport) {
        Set<String> raw = csv("AUTH_MODE");
        if (transport == Transport.STDIO) {
            return EnumSet.noneOf(AuthMode.class);
        }
        if (raw.isEmpty()) {
            problems.add(
                    "AUTH_MODE is required for the http transport (entra, apikey, or none for loopback-only development)");
            return EnumSet.noneOf(AuthMode.class);
        }
        Set<AuthMode> modes = EnumSet.noneOf(AuthMode.class);
        for (String mode : raw) {
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "apikey" -> modes.add(AuthMode.APIKEY);
                case "none" -> modes.add(AuthMode.NONE);
                case "entra" -> modes.add(AuthMode.ENTRA);
                default -> problems.add("AUTH_MODE contains an unknown mode: " + mode);
            }
        }
        if (modes.contains(AuthMode.NONE) && modes.size() > 1) {
            problems.add("AUTH_MODE=none cannot be combined with other modes");
        }
        return modes;
    }

    private String logLevel() {
        String raw = value("LOG_LEVEL").orElse("INFO").toUpperCase(Locale.ROOT);
        // Like upstream, an unknown level falls back to INFO instead of failing startup.
        return Set.of("TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR").contains(raw)
                ? raw.replace("WARNING", "WARN")
                : "INFO";
    }

    // --- primitives ---------------------------------------------------------------------------

    private Optional<String> value(String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.strip());
    }

    private boolean present(String name) {
        return value(name).isPresent();
    }

    /** A secret from {@code NAME} or the file named by {@code NAME_FILE}, never both. */
    private Optional<String> secret(String name) {
        Optional<String> direct = value(name);
        Optional<String> file = value(name + "_FILE");
        if (direct.isPresent() && file.isPresent()) {
            problems.add("set only one of " + name + " and " + name + "_FILE");
            return direct;
        }
        if (file.isEmpty()) {
            return direct;
        }
        try {
            String content = Files.readString(Path.of(file.get())).strip();
            if (content.isEmpty()) {
                problems.add(name + "_FILE points to an empty file: " + file.get());
                return Optional.empty();
            }
            return Optional.of(content);
        } catch (IOException e) {
            problems.add(name + "_FILE cannot be read: " + file.get());
            return Optional.empty();
        }
    }

    private boolean bool(String name, boolean defaultValue) {
        Optional<String> raw = value(name);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        return switch (raw.get().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> {
                problems.add(name + " must be true or false, got: " + raw.get());
                yield defaultValue;
            }
        };
    }

    private int intValue(String name, int defaultValue) {
        Optional<String> raw = value(name);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.get());
        } catch (NumberFormatException e) {
            problems.add(name + " must be an integer, got: " + raw.get());
            return defaultValue;
        }
    }

    private int nonNegativeInt(String name, int defaultValue) {
        int v = intValue(name, defaultValue);
        if (v < 0) {
            problems.add(name + " must not be negative, got: " + v);
            return defaultValue;
        }
        return v;
    }

    private int positiveInt(String name, int defaultValue) {
        int v = intValue(name, defaultValue);
        if (v < 1) {
            problems.add(name + " must be at least 1, got: " + v);
            return defaultValue;
        }
        return v;
    }

    private Set<String> csv(String name) {
        return value(name)
                .map(v -> Arrays.stream(v.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .map(Set::copyOf)
                .orElse(Set.of());
    }

    private Role role(String name, Role defaultValue) {
        Optional<String> raw = value(name);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        return Role.parse(raw.get()).orElseGet(() -> {
            problems.add(name + " must be one of reader, operator, admin, got: " + raw.get());
            return defaultValue;
        });
    }
}
