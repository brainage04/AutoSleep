package io.github.brainage04.autosleep;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Properties;

final class GameTestServers {
    private GameTestServers() {}

    /**
     * Short view and simulation distances, and a free port: two runs on one host would otherwise
     * both bind the default port, and the second one's server fails to start (as
     * MinecraftModConventions' {@code findAvailablePort} avoids).
     */
    static Properties serverProperties() {
        Properties properties = new Properties();
        properties.setProperty("simulation-distance", "5");
        properties.setProperty("view-distance", "4");
        try (ServerSocket socket = new ServerSocket(0)) {
            properties.setProperty("server-port", String.valueOf(socket.getLocalPort()));
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the test server", e);
        }
        return properties;
    }
}
