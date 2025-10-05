package com.obsinity.client.transport;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal transport SPI: send serialized payloads to Obsinity ingest.
 */
public interface EventSender extends Closeable {
    // Default to the REST UnifiedPublish endpoint on a local controller instance
    String DEFAULT_SCHEME = "http";
    String DEFAULT_HOST = "localhost";
    int DEFAULT_PORT = 8086;
    String DEFAULT_PATH = "/events/publish";
    String DEFAULT_ENDPOINT = DEFAULT_SCHEME + "://" + DEFAULT_HOST + ":" + DEFAULT_PORT + DEFAULT_PATH;
    String PROP_ENDPOINT = "obsinity.ingest.url";
    String ENV_ENDPOINT = "OBSINITY_INGEST_URL";

    void send(byte[] body) throws IOException;

    default String endpoint() {
        String sys = System.getProperty(PROP_ENDPOINT);
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv(ENV_ENDPOINT);
        if (env != null && !env.isBlank()) return env;
        String discovered = discoverHostEndpoint();
        return discovered != null ? discovered : DEFAULT_ENDPOINT;
    }

    @Override
    default void close() throws IOException {
        /* no-op */
    }

    static byte[] requireBytes(String s) {
        return Objects.requireNonNull(s, "payload").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String discoverHostEndpoint() {
        if (!runningInsideContainer()) return null;
        String host = resolveHostAddress();
        if (host == null || host.isBlank()) return null;
        return DEFAULT_SCHEME + "://" + host + ":" + DEFAULT_PORT + DEFAULT_PATH;
    }

    private static boolean runningInsideContainer() {
        return Files.exists(Path.of("/.dockerenv"))
                || Files.exists(Path.of("/.containerenv"))
                || cgroupIndicatesContainer();
    }

    private static boolean cgroupIndicatesContainer() {
        Path cgroup = Path.of("/proc/1/cgroup");
        if (!Files.isReadable(cgroup)) return false;
        try (BufferedReader reader = Files.newBufferedReader(cgroup)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("docker") || line.contains("containerd") || line.contains("kubepods")) return true;
            }
        } catch (IOException ignore) {
            // ignore and report false
        }
        return false;
    }

    private static String resolveHostAddress() {
        String configured = firstNonBlank(
                System.getenv("OBSINITY_HOST_IP"), System.getenv("HOST_DOCKER_INTERNAL"), System.getenv("HOST_IP"));
        if (configured != null) return configured;

        String hostDockerInternal = resolveHostDockerInternal();
        if (hostDockerInternal != null) return hostDockerInternal;

        return defaultGatewayAddress();
    }

    private static String resolveHostDockerInternal() {
        try {
            InetAddress addr = InetAddress.getByName("host.docker.internal");
            if (!addr.isLoopbackAddress()) return addr.getHostAddress();
        } catch (UnknownHostException ignore) {
            // ignore and fall back
        }
        return null;
    }

    private static String defaultGatewayAddress() {
        Path route = Path.of("/proc/net/route");
        if (!Files.isReadable(route)) return null;
        try (BufferedReader reader = Files.newBufferedReader(route)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("Iface")) continue;
                String[] parts = line.split("\t");
                if (parts.length < 3) parts = line.split(" ");
                parts = clean(parts);
                if (parts.length < 3) continue;
                String destination = parts[1];
                String gateway = parts[2];
                if (!"00000000".equals(destination)) continue;
                try {
                    long value = Long.parseLong(gateway, 16);
                    return String.format(
                            "%d.%d.%d.%d",
                            value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF, (value >> 24) & 0xFF);
                } catch (NumberFormatException ignore) {
                    // ignore and continue
                }
            }
        } catch (IOException ignore) {
            // ignore
        }
        return null;
    }

    private static String[] clean(String[] parts) {
        return Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toArray(String[]::new);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
