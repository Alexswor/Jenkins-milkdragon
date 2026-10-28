package cn.localops.jda.service;

import cn.localops.jda.model.NodeStatus;
import cn.localops.jda.model.JenkinsBuild;
import cn.localops.jda.model.JenkinsJobState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class JenkinsClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final URI baseUri;
    private final String authorization;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration pollingInterval;

    public JenkinsClient(String baseUrl, String username, String password) {
        this(
                normalizeBaseUri(baseUrl),
                username,
                password,
                InternalTlsSupport.newHttpClient(
                        Duration.ofSeconds(8),
                        new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)),
                new ObjectMapper(),
                Duration.ofSeconds(2));
    }

    JenkinsClient(URI baseUri, String username, String password, HttpClient httpClient, ObjectMapper objectMapper) {
        this(baseUri, username, password, httpClient, objectMapper, Duration.ofSeconds(2));
    }

    JenkinsClient(
            URI baseUri,
            String username,
            String password,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration pollingInterval) {
        this.baseUri = baseUri;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.pollingInterval = pollingInterval;
    }

    public long pingMillis() {
        long started = System.nanoTime();
        send(request("api/json?tree=mode", "GET").build(), 200);
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    public boolean nodeExists(String agentName) {
        HttpResponse<String> response = sendAllowingNotFound(
                request("computer/" + encodePath(agentName) + "/api/json?tree=displayName", "GET").build());
        return response.statusCode() == 200;
    }

    public Optional<String> findOnlineNodeByPrefix(String prefix) {
        String body = send(request(
                "computer/api/json?tree=computer%5BdisplayName,offline,assignedLabels%5Bname%5D%5D",
                "GET").build(), 200).body();
        JsonNode computers = readJson(body, "Jenkins 节点列表解析失败。").path("computer");
        String match = null;
        for (JsonNode computer : computers) {
            String displayName = computer.path("displayName").asText("");
            boolean managedLabel = false;
            for (JsonNode label : computer.path("assignedLabels")) {
                if ("deployment".equals(label.path("name").asText())) {
                    managedLabel = true;
                    break;
                }
            }
            if (managedLabel && !computer.path("offline").asBoolean(true) && displayName.startsWith(prefix)) {
                if (match != null) {
                    throw new JenkinsException("发现多个符合当前主机的在线 Agent，请先在 Jenkins 中保留正确节点后重试。");
                }
                match = displayName;
            }
        }
        return Optional.ofNullable(match);
    }

    public void createInboundNode(String agentName, String remoteFs, String labels) {
        if (nodeExists(agentName)) {
            return;
        }
        String nodeXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <slave>
                  <name>%s</name>
                  <description>Jenkins一键部署助手自动注册的 Windows Agent</description>
                  <remoteFS>%s</remoteFS>
                  <numExecutors>1</numExecutors>
                  <mode>EXCLUSIVE</mode>
                  <retentionStrategy class="hudson.slaves.RetentionStrategy$Always"/>
                  <launcher class="hudson.slaves.JNLPLauncher"/>
                  <label>%s</label>
                  <nodeProperties/>
                </slave>
                """.formatted(xml(agentName), xml(remoteFs), xml(labels));
        postXml("computer/createItem?name=" + encodeQuery(agentName), nodeXml, 200);
    }

    public String readAgentSecret(String agentName) {
        HttpResponse<InputStream> response = sendStream(
                request("computer/" + encodePath(agentName) + "/jenkins-agent.jnlp", "GET").build(), 200);
        try (InputStream body = response.body()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(body);
            var arguments = document.getElementsByTagName("argument");
            for (int index = 0; index < arguments.getLength(); index++) {
                String value = arguments.item(index).getTextContent().strip();
                if (value.matches("[a-fA-F0-9]{32,}")) {
                    return value;
                }
            }
            throw new JenkinsException("Jenkins 未返回可用的 Agent Secret。");
        } catch (JenkinsException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new JenkinsException("无法解析 Jenkins Agent 启动信息。", exception);
        }
    }

    public void downloadAgentJar(Path destination) {
        HttpResponse<InputStream> response = sendStream(request("jnlpJars/agent.jar", "GET").build(), 200);
        Path temporary = null;
        try (InputStream body = response.body()) {
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), "agent-", ".jar.tmp");
            Files.copy(body, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                        temporary,
                        destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new JenkinsException("agent.jar 保存失败。", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理由下次启动时的目录维护处理。
                }
            }
        }
    }

    public NodeStatus nodeStatus(String agentName) {
        String path = "computer/" + encodePath(agentName)
                + "/api/json?tree=displayName,offline,monitorData[*]";
        String body = send(request(path, "GET").build(), 200).body();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode monitor = root.path("monitorData");
            return new NodeStatus(
                    root.path("displayName").asText(agentName),
                    !root.path("offline").asBoolean(true),
                    monitorValue(monitor, "hudson.node_monitors.ArchitectureMonitor"),
                    monitorValue(monitor, "hudson.node_monitors.ResponseTimeMonitor"),
                    monitorValue(monitor, "hudson.node_monitors.ClockMonitor"),
                    monitorValue(monitor, "hudson.node_monitors.DiskSpaceMonitor"),
                    monitorValue(monitor, "hudson.node_monitors.TemporarySpaceMonitor"));
        } catch (IOException exception) {
            throw new JenkinsException("Jenkins 节点状态解析失败。", exception);
        }
    }

    public boolean jobExists(String jobName) {
        return sendAllowingNotFound(request("job/" + encodePath(jobName) + "/api/json", "GET").build())
                .statusCode() == 200;
    }

    public void ensureJobsAbsent(List<String> jobNames) {
        List<String> existingJobs = jobNames.stream()
                .filter(this::jobExists)
                .toList();
        if (!existingJobs.isEmpty()) {
            throw duplicateJobException(existingJobs);
        }
    }

    public void createJob(String jobName, String configXml) {
        try {
            postXml("createItem?name=" + encodeQuery(jobName), configXml, 200);
        } catch (JenkinsException exception) {
            if (exception.statusCode() == 400 && jobExists(jobName)) {
                throw duplicateJobException(List.of(jobName));
            }
            throw exception;
        }
    }

    public String getJobConfig(String jobName) {
        return send(request("job/" + encodePath(jobName) + "/config.xml", "GET").build(), 200).body();
    }

    public JenkinsJobState jobState(String jobName) {
        String tree = "_class,inQueue,queueItem[id],lastBuild[number,building,result],lastStableBuild[number],builds[building]";
        var response = sendAllowingNotFound(request("job/" + encodePath(jobName) + "/api/json?tree=" + encodeQuery(tree), "GET").build());
        if (response.statusCode() == 404) { return JenkinsJobState.absent(); }
        JsonNode root = readJson(response.body(), "Jenkins Job 状态解析失败。");
        if (!"org.jenkinsci.plugins.workflow.job.WorkflowJob".equals(root.path("_class").asText())
                || !root.path("inQueue").isBoolean() || !root.has("lastBuild") || !root.has("lastStableBuild") || !root.path("builds").isArray()) {
            throw ManagedJobConfig.protectedJob("无法确认任务类型、排队/构建状态和成功历史");
        }
        JsonNode last = root.path("lastBuild");
        boolean active = root.path("inQueue").asBoolean() || root.hasNonNull("queueItem")
                || (!last.isNull() && last.path("building").asBoolean(true));
        for (JsonNode build : root.path("builds")) { active |= build.path("building").asBoolean(true); }
        if (!last.isNull() && (last.path("number").asInt() <= 0 || (!active && !last.hasNonNull("result")))) {
            throw ManagedJobConfig.protectedJob("最近构建状态不完整");
        }
        // Jenkins lastSuccessfulBuild also includes UNSTABLE; only stable SUCCESS permits skipping deployment.
        int success = root.path("lastStableBuild").path("number").asInt();
        if (root.hasNonNull("lastStableBuild") && success <= 0) { throw ManagedJobConfig.protectedJob("成功历史无法确认"); }
        return new JenkinsJobState(true, active, last.path("number").asInt(), last.path("result").asText(""), success);
    }

    void updateFailedJob(String jobName, JenkinsJobState approvedState, String originalXml, String replacementXml) {
        JenkinsJobState current = jobState(jobName);
        if (!current.equals(approvedState) || current.active() || current.everSucceeded()
                || !ManagedJobConfig.hash(getJobConfig(jobName)).equals(ManagedJobConfig.hash(originalXml))) {
            throw ManagedJobConfig.protectedJob("任务在确认后发生变化，请重新检测并确认");
        }
        postXml("job/" + encodePath(jobName) + "/config.xml", replacementXml, 200);
    }

    public JenkinsBuild triggerAndWait(
            String jobName,
            Duration queueTimeout,
            Duration buildTimeout,
            Consumer<String> progress) {
        HttpResponse<String> triggered = postEmpty("job/" + encodePath(jobName) + "/build", 201);
        String location = triggered.headers().firstValue("Location")
                .orElseThrow(() -> new JenkinsException("Jenkins 已接收构建请求，但没有返回队列地址。"));
        URI queueUri = trustedUri(location);
        progress.accept("已进入 Jenkins 队列");
        BuildReference reference = waitForExecutable(queueUri, queueTimeout, progress);
        progress.accept("构建 #" + reference.number() + " 已开始");
        return waitForBuild(reference, buildTimeout, progress);
    }

    private BuildReference waitForExecutable(URI queueUri, Duration timeout, Consumer<String> progress) {
        Instant deadline = Instant.now().plus(timeout);
        URI apiUri = childUri(queueUri, "api/json?tree=cancelled,why,executable[number,url]");
        while (Instant.now().isBefore(deadline)) {
            JsonNode root = readJson(send(request(apiUri, "GET").build(), 200).body(), "Jenkins 队列状态解析失败。");
            if (root.path("cancelled").asBoolean(false)) {
                throw new JenkinsException("Jenkins 队列任务已被取消。" + root.path("why").asText(""));
            }
            JsonNode executable = root.path("executable");
            if (executable.has("number") && executable.hasNonNull("url")) {
                return new BuildReference(executable.path("number").asInt(), trustedUri(executable.path("url").asText()));
            }
            String why = root.path("why").asText("");
            if (!why.isBlank()) {
                progress.accept("等待执行器：" + why);
            }
            pausePolling();
        }
        throw new JenkinsException("等待 Jenkins 分配执行器超时。请检查 Agent 是否在线或执行器是否繁忙。");
    }

    private JenkinsBuild waitForBuild(BuildReference reference, Duration timeout, Consumer<String> progress) {
        Instant deadline = Instant.now().plus(timeout);
        URI apiUri = childUri(reference.url(), "api/json?tree=building,result");
        while (Instant.now().isBefore(deadline)) {
            JsonNode root = readJson(send(request(apiUri, "GET").build(), 200).body(), "Jenkins 构建状态解析失败。");
            if (!root.path("building").asBoolean(true)) {
                String result = root.path("result").asText("UNKNOWN");
                String console = send(request(childUri(reference.url(), "consoleText"), "GET").build(), 200).body();
                progress.accept("构建 #" + reference.number() + " 已结束：" + result);
                return new JenkinsBuild(reference.number(), result, console);
            }
            progress.accept("构建 #" + reference.number() + " 正在执行");
            pausePolling();
        }
        throw new JenkinsException("等待 Jenkins 构建完成超时。构建仍可能在 Jenkins 中继续运行。");
    }

    private HttpRequest.Builder request(String relativePath, String method) {
        return request(baseUri.resolve(relativePath), method);
    }

    private HttpRequest.Builder request(URI uri, String method) {
        URI trusted = trustedUri(uri.toString());
        HttpRequest.Builder builder = HttpRequest.newBuilder(trusted)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "application/json");
        return "GET".equals(method) ? builder.GET() : builder;
    }

    private void postXml(String path, String xml, int... expectedStatuses) {
        HttpRequest.Builder builder = request(path, "POST")
                .header("Content-Type", "application/xml; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8));
        crumb().ifPresent(crumb -> builder.header(crumb.field(), crumb.value()));
        send(builder.build(), expectedStatuses);
    }

    private HttpResponse<String> postEmpty(String path, int... expectedStatuses) {
        HttpRequest.Builder builder = request(path, "POST")
                .POST(HttpRequest.BodyPublishers.noBody());
        crumb().ifPresent(crumb -> builder.header(crumb.field(), crumb.value()));
        return send(builder.build(), expectedStatuses);
    }

    private Optional<Crumb> crumb() {
        HttpResponse<String> response = sendAllowingNotFound(request("crumbIssuer/api/json", "GET").build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw httpFailure(response.statusCode(), response.body());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String field = root.path("crumbRequestField").asText();
            String value = root.path("crumb").asText();
            return field.isBlank() || value.isBlank() ? Optional.empty() : Optional.of(new Crumb(field, value));
        } catch (IOException exception) {
            throw new JenkinsException("Jenkins CSRF Crumb 解析失败。", exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request, int... expectedStatuses) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw connectionFailure(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JenkinsException("Jenkins 请求已取消。", exception);
        }
        for (int status : expectedStatuses) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        throw httpFailure(response.statusCode(), response.body());
    }

    private HttpResponse<String> sendAllowingNotFound(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 || response.statusCode() == 404) {
                return response;
            }
            throw httpFailure(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw connectionFailure(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JenkinsException("Jenkins 请求已取消。", exception);
        }
    }

    private HttpResponse<InputStream> sendStream(HttpRequest request, int... expectedStatuses) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw connectionFailure(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JenkinsException("Jenkins 文件下载已取消。", exception);
        }
        for (int status : expectedStatuses) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        try {
            response.body().close();
        } catch (IOException ignored) {
            // 关闭失败不覆盖 HTTP 错误。
        }
        throw httpFailure(response.statusCode(), "");
    }

    private static JenkinsException httpFailure(int statusCode, String responseBody) {
        String message = switch (statusCode) {
            case 401 -> "Jenkins 账号或密码未被接受（HTTP 401），请重新检查连接设置。";
            case 403 -> "Jenkins 已通过身份识别，但拒绝了当前操作（HTTP 403），请检查节点查看与创建权限。";
            case 404 -> "Jenkins 地址或目标资源不存在。";
            default -> "Jenkins 返回异常状态：HTTP " + statusCode + "。";
        };
        return new JenkinsException(message, statusCode, diagnosticResponse(responseBody));
    }

    private static String diagnosticResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        String text = responseBody
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .strip();
        return text.length() <= 3000 ? text : text.substring(0, 3000) + "…";
    }

    private static JenkinsException connectionFailure(IOException exception) {
        if (containsCause(exception, javax.net.ssl.SSLHandshakeException.class)
                || containsMessage(exception, "PKIX")) {
            return new JenkinsException("Jenkins HTTPS 证书校验失败，请确认服务器仍使用公司内部证书。", exception);
        }
        if (exception instanceof HttpTimeoutException
                || exception instanceof HttpConnectTimeoutException
                || containsCause(exception, HttpTimeoutException.class)) {
            return new JenkinsException("连接 Jenkins 超时，请检查服务器地址和网络。", exception);
        }
        if (exception instanceof ConnectException || containsCause(exception, ConnectException.class)) {
            return new JenkinsException("Jenkins 端口无法连接，请检查服务器地址和网络。", exception);
        }
        return new JenkinsException("无法连接 Jenkins，请检查地址和网络。", exception);
    }

    private static JenkinsException duplicateJobException(List<String> jobNames) {
        String names = String.join("、", jobNames);
        return new JenkinsException(
                "Jenkins 已存在同名 Job：" + names
                        + "。为保护现有 Pipeline，已阻止本次部署且不会覆盖原脚本。"
                        + "请更换 Gerrit 项目名，或由 Jenkins 管理员确认并处理旧 Job。");
    }

    private static boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMessage(Throwable throwable, String value) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String monitorValue(JsonNode monitor, String key) {
        JsonNode value = monitor.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return "待上报";
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        for (String candidate : new String[]{"string", "value", "size"}) {
            if (value.has(candidate)) {
                return value.path(candidate).asText("待上报");
            }
        }
        return value.toString();
    }

    private static URI normalizeBaseUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Jenkins 地址不能为空。");
        }
        String normalized = value.strip();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        URI result = URI.create(normalized);
        if (!"https".equalsIgnoreCase(result.getScheme()) && !"http".equalsIgnoreCase(result.getScheme())) {
            throw new IllegalArgumentException("Jenkins 地址必须以 http:// 或 https:// 开头。");
        }
        return result;
    }

    private URI trustedUri(String value) {
        URI resolved = baseUri.resolve(value);
        int basePort = effectivePort(baseUri);
        if (!baseUri.getScheme().equalsIgnoreCase(resolved.getScheme())
                || !baseUri.getHost().equalsIgnoreCase(resolved.getHost())
                || basePort != effectivePort(resolved)
                || !resolved.getPath().startsWith(baseUri.getPath())) {
            throw new JenkinsException("Jenkins 返回了不受信任的构建地址，已拒绝继续访问。");
        }
        return resolved;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static URI childUri(URI parent, String child) {
        String value = parent.toString();
        return URI.create((value.endsWith("/") ? value : value + "/") + child);
    }

    private JsonNode readJson(String body, String failureMessage) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new JenkinsException(failureMessage, exception);
        }
    }

    private void pausePolling() {
        if (pollingInterval.isZero() || pollingInterval.isNegative()) {
            return;
        }
        try {
            Thread.sleep(pollingInterval);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JenkinsException("Jenkins 构建监控已取消。", exception);
        }
    }

    private static String encodePath(String value) {
        return encodeQuery(value).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private record Crumb(String field, String value) {
    }

    private record BuildReference(int number, URI url) {
    }
}
