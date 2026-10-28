package cn.localops.jda.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProductionDirectoryService {
    private static final List<Path> REQUIRED_DIRECTORIES = List.of(
            Path.of("frontend"),
            Path.of("backend"),
            Path.of("backend", "config"),
            Path.of("staging", "frontend"),
            Path.of("staging", "backend"),
            Path.of("backups"),
            Path.of("backups", "config"));

    public Path prepare(Path selectedDirectory) throws IOException {
        if (selectedDirectory == null) {
            throw new IllegalArgumentException("请选择文件夹放置生产文件。");
        }
        Path root = selectedDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (Path relative : REQUIRED_DIRECTORIES) {
            Files.createDirectories(root.resolve(relative));
        }
        return root;
    }
}
