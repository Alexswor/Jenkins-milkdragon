package cn.localops.jda.model;

public record DeploymentHints(
        FrontendDeploymentMode frontendMode,
        int backendPort,
        String proxyPath,
        String backendHealthPath,
        String databaseJdbcUrl) {
    public DeploymentHints(
            FrontendDeploymentMode frontendMode,
            int backendPort,
            String proxyPath,
            String backendHealthPath) {
        this(frontendMode, backendPort, proxyPath, backendHealthPath, "");
    }

    public DeploymentHints {
        if (frontendMode == null) {
            frontendMode = FrontendDeploymentMode.STATIC_DIRECTORY;
        }
        if (backendPort < 1 || backendPort > 65_535) {
            throw new IllegalArgumentException("后端端口必须在 1 到 65535 之间。");
        }
        proxyPath = normalizePath(proxyPath, "/api");
        backendHealthPath = normalizePath(backendHealthPath, "");
        databaseJdbcUrl = databaseJdbcUrl == null ? "" : databaseJdbcUrl.strip();
    }

    public static DeploymentHints defaults() {
        return new DeploymentHints(FrontendDeploymentMode.STATIC_DIRECTORY, 8080, "/api", "", "");
    }

    private static String normalizePath(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
