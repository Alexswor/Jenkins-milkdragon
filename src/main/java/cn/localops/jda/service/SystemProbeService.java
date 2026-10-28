package cn.localops.jda.service;

import cn.localops.jda.model.HostSnapshot;
import cn.localops.jda.model.StorageVolume;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class SystemProbeService {
    public HostSnapshot inspect() {
        return new HostSnapshot(
                hostName(),
                System.getProperty("os.name", "Windows") + " " + System.getProperty("os.version", ""),
                normalizeArchitecture(System.getProperty("os.arch", "unknown")),
                storageVolumes(),
                System.getProperty("java.version", "unknown"),
                hasAdministratorToken());
    }

    private static String hostName() {
        String environmentName = System.getenv("COMPUTERNAME");
        if (environmentName != null && !environmentName.isBlank()) {
            return environmentName;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "windows-host";
        }
    }

    private static List<StorageVolume> storageVolumes() {
        File[] roots = File.listRoots();
        if (roots == null) {
            roots = new File[0];
        }
        List<StorageVolume> volumes = Arrays.stream(roots)
                .filter(File::exists)
                .filter(root -> root.getTotalSpace() > 0)
                .map(root -> new StorageVolume(root.toPath(), root.getUsableSpace(), root.getTotalSpace()))
                .sorted(Comparator.comparing(volume -> volume.root().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (!volumes.isEmpty()) {
            return volumes;
        }
        File fallback = new File(System.getProperty("user.dir", ".")).toPath().toAbsolutePath().getRoot().toFile();
        return List.of(new StorageVolume(fallback.toPath(), fallback.getUsableSpace(), fallback.getTotalSpace()));
    }

    static String normalizeArchitecture(String architecture) {
        String normalized = architecture.toLowerCase();
        if (normalized.contains("aarch64") || normalized.contains("arm64")) {
            return "arm64";
        }
        if (normalized.contains("64")) {
            return "amd64";
        }
        return "x86";
    }

    private static boolean hasAdministratorToken() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "net", "session")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
