package cn.localops.jda.config;

import java.nio.file.Path;

public final class AppPaths {
    private static final String APP_DIRECTORY = "JenkinsDeployTool";
    private static final String DIAGNOSTICS_DIRECTORY = "diagnostics";
    private static final String PACKAGED_RESOURCES_DIRECTORY = "resources";

    private AppPaths() {
    }

    public static Path dataDirectory() {
        String override = System.getenv("JDA_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String programData = System.getenv("ProgramData");
        if (programData == null || programData.isBlank()) {
            programData = System.getProperty("user.home");
        }
        return Path.of(programData, APP_DIRECTORY).toAbsolutePath().normalize();
    }

    public static Path configFile() {
        return dataDirectory().resolve("config.env");
    }

    public static Path agentDirectoryForRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("请选择 Agent 安装磁盘。");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.equals(normalized.getRoot())) {
            throw new IllegalArgumentException("请选择磁盘，而不是磁盘中的文件夹。");
        }
        return normalized.resolve(APP_DIRECTORY).resolve("agent").normalize();
    }

    public static Path configuredAgentDirectory(EnvConfig config) {
        Path configured = Path.of(config.get("AGENT_INSTALL_DIR")
                        .orElseThrow(() -> new IllegalStateException("请先选择 Agent 安装磁盘。")))
                .toAbsolutePath()
                .normalize();
        Path root = configured.getRoot();
        if (root == null || !configured.equals(agentDirectoryForRoot(root))) {
            throw new IllegalStateException("Agent 安装目录无效，请重新选择安装磁盘。");
        }
        return configured;
    }

    public static boolean isExternalAgent(EnvConfig config) {
        return "true".equals(config.getOrDefault("AGENT_EXTERNAL", "false"));
    }

    public static Path deploymentSupportDirectory(EnvConfig config) {
        if (!isExternalAgent(config)) { return configuredAgentDirectory(config); }
        Path existing = Path.of(config.get("AGENT_INSTALL_DIR")
                .orElseThrow(() -> new IllegalStateException("未找到已有 Agent 目录，请先重新检测。"))).toAbsolutePath().normalize();
        return agentDirectoryForRoot(existing.getRoot()).resolveSibling("deployment-support");
    }

    public static Path diagnosticsDirectory() {
        String override = System.getenv("JDA_DIAGNOSTICS_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        return resolveDiagnosticsDirectory(
                System.getProperty("jpackage.app-path"),
                Path.of("").toAbsolutePath().normalize());
    }

    static Path resolveDiagnosticsDirectory(String packagedLauncherPath, Path workingDirectory) {
        Path applicationDirectory = workingDirectory.toAbsolutePath().normalize();
        if (packagedLauncherPath != null && !packagedLauncherPath.isBlank()) {
            Path launcherDirectory = Path.of(packagedLauncherPath).toAbsolutePath().normalize().getParent();
            if (launcherDirectory != null) {
                applicationDirectory = launcherDirectory;
                Path directoryName = launcherDirectory.getFileName();
                if (directoryName != null
                        && PACKAGED_RESOURCES_DIRECTORY.equalsIgnoreCase(directoryName.toString())
                        && launcherDirectory.getParent() != null) {
                    applicationDirectory = launcherDirectory.getParent();
                }
            }
        }
        return applicationDirectory.resolve(DIAGNOSTICS_DIRECTORY).normalize();
    }
}
