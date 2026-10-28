package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentHints;
import cn.localops.jda.model.FrontendDeploymentMode;
import cn.localops.jda.model.ProjectProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProjectDetectionService {
    private static final Pattern PROPERTIES_PORT = Pattern.compile(
            "(?m)^\\s*server\\.port\\s*[=:]\\s*(?:\\$\\{[^:}]+:)?(\\d{1,5})");
    private static final Pattern PROPERTIES_JDBC_URL = Pattern.compile(
            "(?m)^\\s*spring\\.datasource\\.url\\s*[=:]\\s*['\"]?(jdbc:[^'\"\\r\\n]+)");
    private static final Pattern VITE_OBJECT_PROXY = Pattern.compile(
            "['\"](/[^'\"]*)['\"]\\s*:\\s*\\{[\\s\\S]{0,500}?target\\s*:\\s*['\"]https?://(?:127\\.0\\.0\\.1|localhost):(\\d{1,5})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VITE_DIRECT_PROXY = Pattern.compile(
            "['\"](/[^'\"]*)['\"]\\s*:\\s*['\"]https?://(?:127\\.0\\.0\\.1|localhost):(\\d{1,5})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOTNET_HEALTH_PATH = Pattern.compile(
            "MapHealthChecks\\s*\\(\\s*['\"](/[^'\"]*)['\"]");
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "target", "build");

    public ProjectProfile detect(Path selectedDirectory) throws IOException {
        if (selectedDirectory == null || !Files.isDirectory(selectedDirectory)) {
            throw new IOException("请选择有效的项目目录。");
        }
        Path root = selectedDirectory.toAbsolutePath().normalize();
        Optional<Path> frontend = locateSourceModule(root, this::isFrontend, "前端");
        Optional<Path> backend = locateSourceModule(root, this::isBackend, "后端");

        if (frontend.isEmpty() && backend.isEmpty()) {
            if (containsPackagedArtifacts(root)) {
                throw new IOException("检测到已打包成品（dist 静态文件、.jar 或 .dll），但 Jenkins 需要从 Gerrit 的 dev 分支重新拉取源码并编译。"
                        + "请选择包含 package.json（前端）或 pom.xml/build.gradle/.sln/.csproj（后端）的源码根目录。");
            }
            throw new IOException("未找到源码构建文件。请选择包含 package.json（前端）或 pom.xml/build.gradle/.sln/.csproj（后端）的源码根目录；"
                    + "软件会自动扫描根目录及其一级子文件夹。");
        }

        String projectName = sanitizeProjectName(root.getFileName() == null ? "project" : root.getFileName().toString());
        return new ProjectProfile(
                root,
                projectName,
                frontend,
                backend,
                frontend.map(this::frontendTool),
                backend.map(this::backendTool),
                detectDeploymentHints(root, frontend, backend));
    }

    private DeploymentHints detectDeploymentHints(
            Path root,
            Optional<Path> frontend,
            Optional<Path> backend) throws IOException {
        Optional<ProxyHint> viteProxy = frontend.flatMap(this::detectViteProxy);
        Optional<Path> javaBackend = backend.filter(this::isJavaBackend);
        Optional<Path> dotNetBackend = backend.filter(this::isDotNetBackend);
        int backendPort = javaBackend.flatMap(this::detectBackendPort)
                .or(() -> viteProxy.map(ProxyHint::port))
                .orElse(8080);
        String proxyPath = viteProxy.map(ProxyHint::path).orElse("/api");
        String healthPath = javaBackend.filter(this::hasActuator).map(ignored -> "/actuator/health")
                .or(() -> dotNetBackend.flatMap(this::detectDotNetHealthPath))
                .orElse("");
        String databaseJdbcUrl = javaBackend.flatMap(this::detectBackendJdbcUrl).orElse("");
        FrontendDeploymentMode mode = frontend
                .filter(path -> Files.isRegularFile(path.resolve("web.config"))
                        || Files.isRegularFile(root.resolve("web.config")))
                .map(ignored -> FrontendDeploymentMode.IIS)
                .orElse(FrontendDeploymentMode.STATIC_DIRECTORY);
        return new DeploymentHints(mode, backendPort, proxyPath, healthPath, databaseJdbcUrl);
    }

    private Optional<String> detectBackendJdbcUrl(Path backend) {
        Path resources = backend.resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return Optional.empty();
        }
        try (var files = Files.list(resources)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("application(?:-[^.]+)?\\.(?:properties|ya?ml)"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().contains("-") ? 1 : 0))
                    .map(this::readText)
                    .flatMap(Optional::stream)
                    .map(this::jdbcUrlFromConfiguration)
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> jdbcUrlFromConfiguration(String content) {
        Matcher property = PROPERTIES_JDBC_URL.matcher(content);
        if (property.find()) {
            return usableJdbcUrl(property.group(1));
        }

        boolean inSpring = false;
        boolean inDatasource = false;
        int springIndent = -1;
        int datasourceIndent = -1;
        for (String line : content.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (trimmed.matches("spring\\s*:\\s*")) {
                inSpring = true;
                inDatasource = false;
                springIndent = indent;
                continue;
            }
            if (inSpring && indent <= springIndent) {
                inSpring = false;
                inDatasource = false;
            }
            if (inSpring && trimmed.matches("datasource\\s*:\\s*")) {
                inDatasource = true;
                datasourceIndent = indent;
                continue;
            }
            if (inDatasource && indent <= datasourceIndent) {
                inDatasource = false;
            }
            if (inDatasource && trimmed.startsWith("url:")) {
                return usableJdbcUrl(trimmed.substring("url:".length()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> usableJdbcUrl(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() >= 2
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).strip();
        }
        return normalized.startsWith("jdbc:") ? Optional.of(normalized) : Optional.empty();
    }

    private Optional<Integer> detectBackendPort(Path backend) {
        Path resources = backend.resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return Optional.empty();
        }
        try (var files = Files.list(resources)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("application(?:-[^.]+)?\\.(?:properties|ya?ml)"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().contains("-") ? 1 : 0))
                    .map(this::readText)
                    .flatMap(Optional::stream)
                    .map(this::portFromConfiguration)
                    .flatMap(Optional::stream)
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Integer> portFromConfiguration(String content) {
        Matcher properties = PROPERTIES_PORT.matcher(content);
        if (properties.find()) {
            return validPort(properties.group(1));
        }
        boolean inServer = false;
        int serverIndent = -1;
        for (String line : content.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (trimmed.matches("server\\s*:\\s*")) {
                inServer = true;
                serverIndent = indent;
                continue;
            }
            if (inServer && indent <= serverIndent) {
                inServer = false;
            }
            if (inServer) {
                Matcher port = Pattern.compile("port\\s*:\\s*(?:\\$\\{[^:}]+:)?(\\d{1,5})")
                        .matcher(trimmed);
                if (port.matches()) {
                    return validPort(port.group(1));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ProxyHint> detectViteProxy(Path frontend) {
        for (String name : List.of("vite.config.ts", "vite.config.js", "vite.config.mjs", "vite.config.cjs")) {
            Optional<String> content = readText(frontend.resolve(name));
            if (content.isEmpty()) {
                continue;
            }
            for (Pattern pattern : List.of(VITE_OBJECT_PROXY, VITE_DIRECT_PROXY)) {
                Matcher matcher = pattern.matcher(content.get());
                if (matcher.find()) {
                    Optional<Integer> port = validPort(matcher.group(2));
                    if (port.isPresent()) {
                        return Optional.of(new ProxyHint(matcher.group(1), port.get()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasActuator(Path backend) {
        for (String buildFile : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Optional<String> content = readText(backend.resolve(buildFile));
            if (content.isPresent() && content.get().contains("spring-boot-starter-actuator")) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> detectDotNetHealthPath(Path backend) {
        try (var files = Files.walk(backend, 6)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".cs"))
                    .filter(path -> !path.toString().matches("(?i).*[\\\\/](?:bin|obj)[\\\\/].*"))
                    .map(this::readText)
                    .flatMap(Optional::stream)
                    .map(DOTNET_HEALTH_PATH::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> readText(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 1_048_576) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> validPort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535 ? Optional.of(port) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> locateSourceModule(
            Path root,
            DirectoryPredicate predicate,
            String moduleType) throws IOException {
        if (predicate.matches(root)) {
            return Optional.of(root);
        }

        List<Path> candidates;
        try (var children = Files.list(root)) {
            candidates = children
                    .filter(Files::isDirectory)
                    .filter(path -> !isIgnoredDirectory(path))
                    .filter(predicate::matches)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        if (candidates.size() > 1) {
            String names = candidates.stream()
                    .map(path -> path.getFileName().toString())
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
            throw new IOException("检测到多个" + moduleType + "源码目录：" + names
                    + "。请只选择其中一个源码目录，或把本次需要部署的前后端源码放在同一个上级目录中。");
        }
        return candidates.stream().findFirst();
    }

    private boolean isIgnoredDirectory(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && IGNORED_DIRECTORIES.contains(fileName.toString().toLowerCase());
    }

    private boolean containsPackagedArtifacts(Path root) throws IOException {
        if (Files.isRegularFile(root.resolve("index.html"))
                || Files.isRegularFile(root.resolve("dist").resolve("index.html"))) {
            return true;
        }
        try (var files = Files.list(root)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && (path.getFileName().toString().toLowerCase().endsWith(".jar")
                    || path.getFileName().toString().toLowerCase().endsWith(".dll")));
        }
    }

    private boolean isFrontend(Path directory) {
        return Files.isRegularFile(directory.resolve("package.json"));
    }

    private boolean isBackend(Path directory) {
        return isJavaBackend(directory) || isDotNetBackend(directory);
    }

    private boolean isJavaBackend(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"));
    }

    private boolean isDotNetBackend(Path directory) {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase())
                    .anyMatch(name -> name.endsWith(".sln")
                            || name.endsWith(".slnx")
                            || name.endsWith(".csproj"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private String frontendTool(Path directory) {
        if (Files.isRegularFile(directory.resolve("pnpm-lock.yaml"))) {
            return "pnpm";
        }
        if (Files.isRegularFile(directory.resolve("yarn.lock"))) {
            return "yarn";
        }
        return "npm";
    }

    private String backendTool(Path directory) {
        if (Files.isRegularFile(directory.resolve("mvnw.cmd")) || Files.isRegularFile(directory.resolve("pom.xml"))) {
            return "maven";
        }
        if (Files.isRegularFile(directory.resolve("gradlew.bat"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"))) {
            return "gradle";
        }
        if (isDotNetBackend(directory)) {
            return "dotnet";
        }
        throw new IllegalStateException("暂不支持该后端构建工具。");
    }

    static String sanitizeProjectName(String name) {
        String normalized = name.strip().toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "project" : normalized;
    }

    @FunctionalInterface
    private interface DirectoryPredicate {
        boolean matches(Path path);
    }

    private record ProxyHint(String path, int port) {
    }
}
