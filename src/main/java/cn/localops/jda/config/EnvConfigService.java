package cn.localops.jda.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EnvConfigService {
    private final Path configFile;
    private final boolean restrictAclAfterSave;

    public EnvConfigService(Path configFile) {
        this(configFile, isManagedConfigPath(configFile));
    }

    EnvConfigService(Path configFile, boolean restrictAclAfterSave) {
        this.configFile = configFile.toAbsolutePath().normalize();
        this.restrictAclAfterSave = restrictAclAfterSave;
    }

    public EnvConfig load() throws IOException {
        EnvConfig result = new EnvConfig();
        if (!Files.exists(configFile)) {
            return result;
        }

        for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).strip();
            String value = trimmed.substring(separator + 1);
            if (key.matches("[A-Z][A-Z0-9_]*")) {
                result.put(key, value);
            }
        }
        return result;
    }

    public void save(EnvConfig config) throws IOException {
        Files.createDirectories(configFile.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# Jenkins一键部署助手本机配置。包含明文凭据，请勿共享。");
        config.asMap().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey()))
                .forEach(entry -> lines.add(entry.getKey() + "=" + singleLine(entry.getValue())));

        Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
        if (restrictAclAfterSave) {
            restrictWindowsAcl();
        }
    }

    public Path configFile() {
        return configFile;
    }

    private void restrictWindowsAcl() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        String userDomain = System.getenv("USERDOMAIN");
        String userName = System.getenv("USERNAME");
        String currentUser = userName == null || userName.isBlank()
                ? System.getProperty("user.name", "")
                : (userDomain == null || userDomain.isBlank() ? userName : userDomain + "\\" + userName);
        try {
            List<String> command = new ArrayList<>(List.of(
                    "icacls",
                    configFile.toString(),
                    "/inheritance:r",
                    "/grant:r",
                    "*S-1-5-18:F",
                    "*S-1-5-32-544:F"));
            if (!currentUser.isBlank()) {
                command.add(currentUser + ":F");
            }
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (IOException exception) {
            // ACL 失败不影响写入，由界面诊断页提示人工修复。
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String singleLine(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("config.env 不支持包含换行的配置值。");
        }
        return value;
    }

    private static boolean isManagedConfigPath(Path candidate) {
        Path managedDirectory = AppPaths.dataDirectory().toAbsolutePath().normalize();
        return candidate.toAbsolutePath().normalize().startsWith(managedDirectory);
    }
}
