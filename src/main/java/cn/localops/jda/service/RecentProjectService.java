package cn.localops.jda.service;

import cn.localops.jda.config.EnvConfig;
import cn.localops.jda.config.EnvConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

public final class RecentProjectService {
    static final String CONFIG_KEY = "LAST_PROJECT_ROOT";

    private final EnvConfigService configService;

    public RecentProjectService(EnvConfigService configService) {
        this.configService = configService;
    }

    public boolean hasRememberedDirectory(EnvConfig config) {
        return config != null && config.get(CONFIG_KEY).isPresent();
    }

    public Optional<Path> existingDirectory(EnvConfig config) {
        if (config == null) {
            return Optional.empty();
        }
        try {
            return config.get(CONFIG_KEY)
                    .map(Path::of)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(Files::isDirectory);
        } catch (InvalidPathException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    public void remember(EnvConfig config, Path projectDirectory) throws IOException {
        Path normalized = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException("无法记住不存在的项目源码目录：" + normalized);
        }
        config.put(CONFIG_KEY, normalized.toString());
        configService.save(config);
    }

    public void clear(EnvConfig config) throws IOException {
        config.remove(CONFIG_KEY);
        configService.save(config);
    }
}
