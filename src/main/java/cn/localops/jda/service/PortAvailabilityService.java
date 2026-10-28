package cn.localops.jda.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Checks whether a TCP port can currently be bound on the local machine. */
public final class PortAvailabilityService {
    public boolean isAvailable(int port) {
        if (port < 1 || port > 65_535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }
}
