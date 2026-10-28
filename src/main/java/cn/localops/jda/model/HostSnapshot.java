package cn.localops.jda.model;

import java.util.List;

public record HostSnapshot(
        String hostName,
        String windowsVersion,
        String architecture,
        List<StorageVolume> storageVolumes,
        String javaVersion,
        boolean administrator) {
    public HostSnapshot {
        storageVolumes = List.copyOf(storageVolumes);
    }
}
