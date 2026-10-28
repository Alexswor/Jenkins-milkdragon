package cn.localops.jda.model;

import java.nio.file.Path;

public record StorageVolume(Path root, long freeBytes, long totalBytes) {
    public StorageVolume {
        if (root == null) {
            throw new IllegalArgumentException("磁盘根目录不能为空。");
        }
        root = root.toAbsolutePath().normalize();
        if (!root.equals(root.getRoot())) {
            throw new IllegalArgumentException("必须提供磁盘根目录：" + root);
        }
        if (freeBytes < 0 || totalBytes <= 0 || freeBytes > totalBytes) {
            throw new IllegalArgumentException("磁盘容量数据无效。");
        }
    }
}
