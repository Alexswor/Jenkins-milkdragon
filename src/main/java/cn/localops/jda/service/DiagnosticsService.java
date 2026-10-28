package cn.localops.jda.service;

import cn.localops.jda.config.EnvConfig;
import cn.localops.jda.model.HostSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class DiagnosticsService {
    public Path export(Path directory, HostSnapshot host, EnvConfig config, String currentStage, String lastError)
            throws IOException {
        Files.createDirectories(directory);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
        Path file = directory.resolve("diagnostics-" + timestamp + ".txt");
        Set<String> keys = new TreeSet<>(config.asMap().keySet());
        String volumes = host == null ? "尚未完成检测" : host.storageVolumes().stream()
                .map(volume -> "%s 可用 %s / 总计 %s".formatted(
                        volume.root(), formatBytes(volume.freeBytes()), formatBytes(volume.totalBytes())))
                .collect(Collectors.joining(System.lineSeparator()));
        String content = """
                Jenkins一键部署助手诊断信息
                生成时间: %s
                当前阶段: %s
                最近错误: %s

                主机名: %s
                Windows: %s
                架构: %s
                Java: %s
                管理员权限: %s

                可用磁盘:
                %s
                Agent 安装目录: %s

                已配置字段（仅键名，不包含值）:
                %s
                """.formatted(
                Instant.now(),
                safe(currentStage),
                safe(redactSensitive(lastError, config)),
                host == null ? "尚未完成检测" : safe(host.hostName()),
                host == null ? "尚未完成检测" : safe(host.windowsVersion()),
                host == null ? "尚未完成检测" : safe(host.architecture()),
                host == null ? "尚未完成检测" : safe(host.javaVersion()),
                host != null && host.administrator() ? "是" : "否",
                volumes,
                safe(config.getOrDefault("AGENT_INSTALL_DIR", "尚未选择")),
                String.join(System.lineSeparator(), keys));
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    public String describe(Throwable failure, EnvConfig config) {
        if (failure == null) {
            return "无";
        }
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (Throwable current = failure; current != null && depth < 8; current = current.getCause(), depth++) {
            if (!result.isEmpty()) {
                result.append(" -> ");
            }
            result.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                result.append(": ").append(current.getMessage());
            }
            if (current instanceof JenkinsException jenkinsException
                    && !jenkinsException.diagnosticDetail().isBlank()) {
                result.append(" [Jenkins 响应: ").append(jenkinsException.diagnosticDetail()).append(']');
            }
        }
        return redactSensitive(result.toString(), config);
    }

    private static String redactSensitive(String value, EnvConfig config) {
        String redacted = value == null ? "" : value;
        for (var entry : config.asMap().entrySet()) {
            String key = entry.getKey();
            String secret = entry.getValue();
            if ((key.contains("PASSWORD") || key.contains("TOKEN") || key.contains("SECRET"))
                    && secret != null && secret.length() >= 4) {
                redacted = redacted.replace(secret, "***");
            }
        }
        return redacted;
    }

    private static String formatBytes(long value) {
        return String.format("%.1f GiB", value / 1024d / 1024d / 1024d);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "无" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
