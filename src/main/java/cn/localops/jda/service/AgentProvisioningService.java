package cn.localops.jda.service;

import cn.localops.jda.config.AppPaths;
import cn.localops.jda.config.EnvConfig;
import cn.localops.jda.config.EnvConfigService;
import cn.localops.jda.model.HostSnapshot;
import cn.localops.jda.model.NodeStatus;
import cn.localops.jda.model.ProvisioningProgress;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AgentProvisioningService {
    private static final int TOTAL_STEPS = 7;
    private final EnvConfigService configService;
    private final AgentNameService agentNameService;
    private final WinSwConfigGenerator winSwConfigGenerator;
    private final ProcessRunner processRunner;
    private final HttpClient downloadClient;
    private final Path bundledWinSw;
    private final ExistingAgentDiscovery existingAgentDiscovery;

    public AgentProvisioningService(EnvConfigService configService) {
        this(configService, new AgentNameService(), new WinSwConfigGenerator(), new ProcessRunner(),
                newDownloadClient(), packagedWinSw());
    }

    AgentProvisioningService(
            EnvConfigService configService,
            AgentNameService agentNameService,
            WinSwConfigGenerator winSwConfigGenerator,
            ProcessRunner processRunner,
            HttpClient downloadClient,
            Path bundledWinSw) {
        this(configService, agentNameService, winSwConfigGenerator, processRunner, downloadClient, bundledWinSw,
                new ExistingAgentDiscovery());
    }

    AgentProvisioningService(
            EnvConfigService configService, AgentNameService agentNameService,
            WinSwConfigGenerator winSwConfigGenerator, ProcessRunner processRunner,
            HttpClient downloadClient, Path bundledWinSw, ExistingAgentDiscovery existingAgentDiscovery) {
        this.configService = configService;
        this.agentNameService = agentNameService;
        this.winSwConfigGenerator = winSwConfigGenerator;
        this.processRunner = processRunner;
        this.downloadClient = downloadClient;
        this.bundledWinSw = bundledWinSw;
        this.existingAgentDiscovery = existingAgentDiscovery;
    }

    public NodeStatus provision(HostSnapshot host, Consumer<ProvisioningProgress> progress) throws IOException {
        EnvConfig config = configService.load();
        require(config, "JENKINS_URL", "JENKINS_USERNAME", "JENKINS_PASSWORD");
        String enrollmentUsername = config.getOrDefault(
                "ENROLLMENT_USERNAME",
                config.get("JENKINS_USERNAME").orElseThrow());
        String enrollmentCredential = config.getOrDefault(
                "ENROLLMENT_TOKEN",
                config.get("JENKINS_PASSWORD").orElseThrow());
        JenkinsClient jenkins = new JenkinsClient(
                config.get("JENKINS_URL").orElseThrow(),
                enrollmentUsername,
                enrollmentCredential);

        emit(progress, 1, "检查 Jenkins 连接", "正在验证地址和凭据");
        jenkins.pingMillis();

        Optional<NodeStatus> installed = recoverOnlineAgent(host, jenkins, progress);
        if (installed.isPresent()) {
            if (!installed.get().online()) { throw new IOException("已发现本机已有 Agent，但尚未在线；请检查原服务，软件不会重复安装。"); }
            return installed.get();
        }
        config = configService.load();

        Path agentDirectory = AppPaths.configuredAgentDirectory(config);
        Path workDirectory = agentDirectory.resolve("workspace");
        Files.createDirectories(workDirectory);

        Optional<NodeStatus> reusable = reuseOnlineAgent(
                config, host, agentDirectory, workDirectory, jenkins, progress);
        if (reusable.isPresent()) {
            return reusable.get();
        }

        String agentName = agentNameService.resolve(config, host);

        emit(progress, 2, "创建 Jenkins 节点", agentName);
        String labels = "windows " + host.architecture() + " deployment " + agentName;
        jenkins.createInboundNode(agentName, workDirectory.toString(), labels);

        emit(progress, 3, "获取 Agent 密钥", "密钥将写入本机 config.env");
        String secret = config.get("AGENT_SECRET").orElseGet(() -> jenkins.readAgentSecret(agentName));

        Path agentJar = agentDirectory.resolve("agent.jar");
        persistAgentIdentity(config, host, agentName, secret, agentJar, workDirectory);
        configService.save(config);

        emit(progress, 4, "准备 Java 和 agent.jar", "使用应用私有路径");
        Path javaExecutable = resolveJava(config, agentDirectory);
        if (!isUsableAgentJar(agentJar)) {
            jenkins.downloadAgentJar(agentJar);
        }
        Path trustStore = InternalTlsSupport.writeAgentTrustStore(agentDirectory);
        prepareIisSupport(agentDirectory);

        config.put("AGENT_JAVA_EXE", javaExecutable.toString());
        config.put("AGENT_TRUSTSTORE", trustStore.toString());
        config.put("AGENT_TRUSTSTORE_PASSWORD", new String(InternalTlsSupport.agentTrustStorePassword()));
        configService.save(config);

        emit(progress, 5, "配置 Windows 服务", "生成 WinSW 配置与启动脚本");
        Path wrapper = prepareWinSw(config, agentDirectory);
        Path launcher = agentDirectory.resolve("start-agent.ps1");
        Files.writeString(launcher, winSwConfigGenerator.launcherScript(configService.configFile().toString()), StandardCharsets.UTF_8);
        String serviceId = winSwConfigGenerator.serviceId(agentName);
        Path xml = wrapper.resolveSibling(stripExtension(wrapper.getFileName().toString()) + ".xml");
        Files.writeString(xml, winSwConfigGenerator.generate(serviceId, launcher.toString(), agentDirectory.toString()), StandardCharsets.UTF_8);

        emit(progress, 6, "启动 Agent 服务", "通过 HTTPS/WebSocket 主动连接 Jenkins");
        installOrRefreshService(wrapper, serviceId);

        emit(progress, 7, "验证节点在线", "等待 Jenkins 上报监控数据");
        NodeStatus status = waitUntilOnline(jenkins, agentName, Duration.ofSeconds(60));
        config.remove("ENROLLMENT_TOKEN");
        config.remove("ENROLLMENT_USERNAME");
        config.put("AGENT_ONLINE_VERIFIED_AT", Instant.now().toString());
        config.put("AGENT_SERVICE_ID", serviceId);
        configService.save(config);
        return status;
    }

    public Optional<NodeStatus> recoverOnlineAgent(
            HostSnapshot host,
            JenkinsClient jenkins,
            Consumer<ProvisioningProgress> progress) throws IOException {
        EnvConfig config = configService.load();
        List<ExistingAgentDiscovery.InstalledAgent> localAgents = existingAgentDiscovery.discover().stream()
                .filter(agent -> ExistingAgentDiscovery.sameController(agent.controller(), config.getOrDefault("JENKINS_URL", "")))
                .toList();
        if (!localAgents.isEmpty()) {
            String configuredName = config.getOrDefault("AGENT_NAME", "");
            List<ExistingAgentDiscovery.InstalledAgent> preferred = localAgents.stream()
                    .filter(agent -> agent.name().equals(configuredName)
                            && agent.directory().toString().equals(config.getOrDefault("AGENT_INSTALL_DIR", ""))).toList();
            List<ExistingAgentDiscovery.InstalledAgent> candidates = preferred.size() == 1 ? preferred : localAgents;
            if (candidates.size() != 1) {
                throw new IOException("发现多个连接当前 Jenkins 的本机 Agent，无法安全自动选择；请确认需要复用的节点后重试。");
            }
            var agent = candidates.getFirst();
            NodeStatus status = jenkins.nodeExists(agent.name()) ? jenkins.nodeStatus(agent.name()) : NodeStatus.offline(agent.name());
            if (status.online() && !agent.running()) {
                throw new IOException("本机 Agent 服务未运行，但同名 Jenkins 节点在线，无法确认节点归属；不会复用或重装。");
            }
            config.put("AGENT_EXTERNAL", "true");
            config.put("AGENT_NAME", agent.name());
            config.put("AGENT_LABEL", agent.name().matches("[A-Za-z0-9_-]+") ? agent.name()
                    : "\"" + agent.name().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            config.put("AGENT_INSTALL_DIR", agent.directory().toString());
            config.put("AGENT_JAR", agent.jar().toString());
            config.put("AGENT_WORK_DIR", agent.workDirectory().toString());
            config.put("AGENT_SERVICE_ID", agent.serviceId());
            config.put("AGENT_ARCH", host.architecture());
            config.put("AGENT_HOSTNAME", host.hostName());
            config.remove("AGENT_SECRET");
            config.remove("ENROLLMENT_TOKEN");
            config.remove("ENROLLMENT_USERNAME");
            if (agent.javaExecutable() != null) { config.put("AGENT_JAVA_EXE", agent.javaExecutable().toString()); }
            else { config.remove("AGENT_JAVA_EXE"); }
            if (status.online()) { config.put("AGENT_ONLINE_VERIFIED_AT", Instant.now().toString()); }
            else { config.remove("AGENT_ONLINE_VERIFIED_AT"); }
            configService.save(config);
            emit(progress, 7, "识别已有本机 Agent", agent.name() + (status.online() ? " 已在线，无需重新安装" : " 已安装，等待原服务连接 Jenkins"));
            return Optional.of(status);
        }
        if (AppPaths.isExternalAgent(config)) {
            throw new IOException("未找到此前识别的本机 Agent 服务，或其 Jenkins 地址与当前设置不一致；请检查原服务，软件不会自动重装。");
        }
        String configuredDirectory = config.getOrDefault("AGENT_INSTALL_DIR", "");
        if (configuredDirectory.isBlank()) {
            return Optional.empty();
        }
        Path agentDirectory = AppPaths.configuredAgentDirectory(config);
        Path workDirectory = agentDirectory.resolve("workspace");
        return reuseOnlineAgent(config, host, agentDirectory, workDirectory, jenkins, progress);
    }

    Optional<NodeStatus> reuseOnlineAgent(
            EnvConfig config,
            HostSnapshot host,
            Path agentDirectory,
            Path workDirectory,
            JenkinsClient jenkins,
            Consumer<ProvisioningProgress> progress) throws IOException {
        String configuredName = config.getOrDefault("AGENT_NAME", "");
        Optional<String> candidate = Optional.empty();
        NodeStatus status = null;
        if (!configuredName.isBlank() && jenkins.nodeExists(configuredName)) {
            NodeStatus configuredStatus = jenkins.nodeStatus(configuredName);
            if (configuredStatus.online()) {
                candidate = Optional.of(configuredName);
                status = configuredStatus;
            }
        }
        if (candidate.isEmpty()) {
            candidate = jenkins.findOnlineNodeByPrefix(agentNameService.expectedPrefix(host));
        }
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        if (status == null) {
            status = jenkins.nodeStatus(candidate.get());
        }

        String agentName = candidate.get();
        String secret = config.get("AGENT_SECRET").orElseGet(() -> jenkins.readAgentSecret(agentName));
        Path agentJar = agentDirectory.resolve("agent.jar");
        persistAgentIdentity(config, host, agentName, secret, agentJar, workDirectory);
        restoreKnownRuntimePaths(config, agentDirectory);
        config.remove("ENROLLMENT_TOKEN");
        config.remove("ENROLLMENT_USERNAME");
        config.put("AGENT_ONLINE_VERIFIED_AT", Instant.now().toString());
        config.put("AGENT_SERVICE_ID", winSwConfigGenerator.serviceId(agentName));
        configService.save(config);
        emit(progress, 7, "复用已在线 Agent", agentName + " 已连接，无需重新安装");
        return Optional.of(status);
    }

    private static void persistAgentIdentity(
            EnvConfig config,
            HostSnapshot host,
            String agentName,
            String secret,
            Path agentJar,
            Path workDirectory) {
        config.put("AGENT_NAME", agentName);
        config.put("AGENT_LABEL", agentName);
        config.put("AGENT_SECRET", secret);
        config.put("AGENT_JAR", agentJar.toString());
        config.put("AGENT_WORK_DIR", workDirectory.toString());
        config.put("AGENT_ARCH", host.architecture());
        config.put("AGENT_HOSTNAME", host.hostName());
    }

    private static void restoreKnownRuntimePaths(EnvConfig config, Path agentDirectory) {
        Path javaExecutable = agentDirectory.resolve("jre").resolve("bin").resolve("java.exe");
        Path trustStore = agentDirectory.resolve("company-truststore.p12");
        if (Files.isRegularFile(javaExecutable)) {
            config.put("AGENT_JAVA_HOME", javaExecutable.getParent().getParent().toString());
            config.put("AGENT_JAVA_EXE", javaExecutable.toString());
        }
        if (Files.isRegularFile(trustStore)) {
            config.put("AGENT_TRUSTSTORE", trustStore.toString());
            config.put("AGENT_TRUSTSTORE_PASSWORD", new String(InternalTlsSupport.agentTrustStorePassword()));
        }
    }

    static boolean isUsableAgentJar(Path agentJar) {
        if (!Files.isRegularFile(agentJar)) {
            return false;
        }
        try (JarFile ignored = new JarFile(agentJar.toFile())) {
            return true;
        } catch (IOException invalidJar) {
            return false;
        }
    }

    public void refreshBundledSupport(EnvConfig config) throws IOException {
        if (AppPaths.isExternalAgent(config)) { return; }
        String configuredDirectory = config.getOrDefault("AGENT_INSTALL_DIR", "");
        if (!configuredDirectory.isBlank()) {
            Path directory = Path.of(configuredDirectory).toAbsolutePath().normalize();
            if (directory.equals(AppPaths.agentDirectoryForRoot(directory.getRoot()))) { prepareIisSupport(directory); }
        }
    }

    public void prepareDeploymentSupport(EnvConfig config) throws IOException {
        if (!AppPaths.isExternalAgent(config)) { return; }
        Path support = AppPaths.deploymentSupportDirectory(config);
        prepareWinSw(config, support);
        if (!Files.isRegularFile(support.resolve("jre/bin/java.exe"))) {
            resolveJava(config.copy(), support);
        }
        prepareIisSupport(support);
    }

    private Path resolveJava(EnvConfig config, Path agentDirectory) throws IOException {
        String configured = config.get("AGENT_JAVA_HOME").orElse("");
        if (!configured.isBlank()) {
            Path java = Path.of(configured, "bin", "java.exe");
            if (java.toAbsolutePath().normalize().startsWith(agentDirectory) && Files.isRegularFile(java)) {
                return java;
            }
        }

        Path packagedJava = packagedAgentRuntime();
        if (packagedJava != null && Files.isRegularFile(packagedJava)) {
            Path runtimeDirectory = agentDirectory.resolve("jre");
            copyDirectory(packagedJava.getParent().getParent(), runtimeDirectory);
            Path selectedJava = runtimeDirectory.resolve("bin").resolve("java.exe");
            config.put("AGENT_JAVA_HOME", runtimeDirectory.toString());
            return selectedJava;
        }

        Path bundledJava = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (Files.isRegularFile(bundledJava)) {
            Path runtimeDirectory = agentDirectory.resolve("jre");
            copyDirectory(bundledJava.getParent().getParent(), runtimeDirectory);
            Path selectedJava = runtimeDirectory.resolve("bin").resolve("java.exe");
            config.put("AGENT_JAVA_HOME", runtimeDirectory.toString());
            return selectedJava;
        }

        String architecture = config.getOrDefault("AGENT_ARCH", "amd64").toUpperCase();
        String runtimeUrl = config.get("JRE_DOWNLOAD_URL_" + architecture)
                .orElseThrow(() -> new IOException("未找到 Java，也未配置 JRE_DOWNLOAD_URL_" + architecture + "。"));
        Path archive = agentDirectory.resolve("runtime.zip");
        download(runtimeUrl, archive);
        Path runtimeDirectory = agentDirectory.resolve("jre");
        unzip(archive, runtimeDirectory);
        try (var stream = Files.find(runtimeDirectory, 5,
                (path, attributes) -> attributes.isRegularFile() && path.getFileName().toString().equalsIgnoreCase("java.exe"))) {
            Path java = stream.findFirst().orElseThrow(() -> new IOException("下载的 JRE 中没有 java.exe。"));
            config.put("AGENT_JAVA_HOME", java.getParent().getParent().toString());
            return java;
        }
    }

    private static Path packagedAgentRuntime() {
        try {
            Path applicationJar = Path.of(AgentProvisioningService.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path applicationDirectory = Files.isDirectory(applicationJar)
                    ? applicationJar
                    : applicationJar.getParent();
            return applicationDirectory.resolve("agent-runtime").resolve("bin").resolve("java.exe");
        } catch (Exception ignored) {
            return null;
        }
    }

    Path prepareWinSw(EnvConfig config, Path agentDirectory) throws IOException {
        Path wrapper = agentDirectory.resolve("jenkins-agent-service.exe");
        if (Files.isRegularFile(wrapper)) {
            return wrapper;
        }
        String architecture = config.getOrDefault("AGENT_ARCH", "amd64");
        if (!"amd64".equals(architecture)) {
            throw new IOException("当前软件包只包含 amd64 版 WinSW，请使用与服务器架构匹配的软件包。");
        }
        if (bundledWinSw == null || !Files.isRegularFile(bundledWinSw)) {
            throw new IOException("软件包缺少内置 WinSW，请完整解压后重试。");
        }
        Files.createDirectories(wrapper.getParent());
        Files.copy(bundledWinSw, wrapper, StandardCopyOption.REPLACE_EXISTING);
        return wrapper;
    }

    static HttpClient newDownloadClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static Path packagedWinSw() {
        try {
            Path applicationJar = Path.of(AgentProvisioningService.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path applicationDirectory = Files.isDirectory(applicationJar)
                    ? applicationJar
                    : applicationJar.getParent();
            return applicationDirectory.resolve("winsw").resolve("WinSW-x64.exe");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path packagedIisSupportDirectory() {
        try {
            Path applicationJar = Path.of(AgentProvisioningService.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path applicationDirectory = Files.isDirectory(applicationJar)
                    ? applicationJar
                    : applicationJar.getParent();
            return applicationDirectory.resolve("iis");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void prepareIisSupport(Path agentDirectory) throws IOException {
        Path destination = agentDirectory.resolve("iis");
        Files.createDirectories(destination);
        Path packaged = packagedIisSupportDirectory();
        if (packaged != null && Files.isDirectory(packaged)) {
            copyDirectory(packaged, destination);
        }
    }

    private void installOrRefreshService(Path wrapper, String serviceId) throws IOException {
        ProcessRunner.ProcessResult query = processRunner.run(List.of("sc.exe", "query", serviceId), Duration.ofSeconds(10));
        String command = query.successful() ? "refresh" : "install";
        ProcessRunner.ProcessResult configured = processRunner.run(List.of(wrapper.toString(), command), Duration.ofSeconds(30));
        if (!configured.successful()) {
            throw new IOException("WinSW 服务" + ("install".equals(command) ? "安装" : "更新") + "失败：" + configured.output());
        }

        ProcessRunner.ProcessResult status = processRunner.run(List.of(wrapper.toString(), "status"), Duration.ofSeconds(10));
        if (!status.output().toLowerCase().contains("started")) {
            ProcessRunner.ProcessResult started = processRunner.run(List.of(wrapper.toString(), "start"), Duration.ofSeconds(30));
            if (!started.successful()) {
                throw new IOException("WinSW 服务启动失败：" + started.output());
            }
        }
    }

    private NodeStatus waitUntilOnline(JenkinsClient jenkins, String agentName, Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        JenkinsException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                NodeStatus status = jenkins.nodeStatus(agentName);
                if (status.online()) {
                    return status;
                }
            } catch (JenkinsException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("节点在线验证已取消。", exception);
            }
        }
        throw new IOException("等待 Jenkins Agent 上线超时。", lastFailure);
    }

    private void download(String url, Path destination) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("下载失败，HTTP " + response.statusCode() + "。");
            }
            Files.createDirectories(destination.getParent());
            try (InputStream body = response.body()) {
                Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("下载已取消。", exception);
        }
    }

    private static void unzip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination)) {
                    throw new IOException("压缩包包含非法路径。");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
                input.closeEntry();
            }
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path input : stream.toList()) {
                Path output = destination.resolve(source.relativize(input)).normalize();
                if (!output.startsWith(destination)) {
                    throw new IOException("Java Runtime 包含非法路径。");
                }
                if (Files.isDirectory(input)) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void require(EnvConfig config, String... keys) throws IOException {
        for (String key : keys) {
            if (config.get(key).isEmpty()) {
                throw new IOException("缺少配置：" + key + "。");
            }
        }
    }

    private static void emit(Consumer<ProvisioningProgress> progress, int step, String title, String detail) {
        progress.accept(new ProvisioningProgress(step, TOTAL_STEPS, title, detail));
    }

    private static String stripExtension(String value) {
        int index = value.lastIndexOf('.');
        return index > 0 ? value.substring(0, index) : value;
    }
}
