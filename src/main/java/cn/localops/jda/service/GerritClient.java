package cn.localops.jda.service;

import cn.localops.jda.model.GerritProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

public final class GerritClient {
    public static final String BASE_URL = System.getenv().getOrDefault("GERRIT_URL", "https://gerrit.example.com/");
    public static final String JENKINS_SERVER_NAME = "local-gerrit";
    private static final String FIXED_USERNAME = System.getenv().getOrDefault("GERRIT_USERNAME", "");
    private static final String FIXED_PASSWORD = System.getenv().getOrDefault("GERRIT_PASSWORD", "");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final URI baseUri;
    private final String authorization;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GerritClient() {
        this(
                URI.create(BASE_URL),
                FIXED_USERNAME,
                FIXED_PASSWORD,
                InternalTlsSupport.newHttpClient(Duration.ofSeconds(8)),
                new ObjectMapper());
    }

    GerritClient(
            URI baseUri,
            String username,
            String password,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.baseUri = normalizeBaseUri(baseUri);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public GerritProject resolveProject(String requestedName) {
        String projectName = requireProjectName(requestedName);
        URI endpoint = baseUri.resolve("a/projects/" + encodeProjectId(projectName));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new GerritException("无法连接固定 Gerrit 地址，请检查服务器网络。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GerritException("Gerrit 项目识别已取消。", exception);
        }

        if (response.statusCode() == 404) {
            throw new GerritException("Gerrit 中不存在项目：" + projectName);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new GerritException("软件内置 Gerrit 账号认证失败或无权查看该项目。 ");
        }
        if (response.statusCode() != 200) {
            throw new GerritException("Gerrit 返回异常状态：HTTP " + response.statusCode() + "。");
        }

        try {
            JsonNode project = objectMapper.readTree(stripXssiPrefix(response.body()));
            String canonicalName = project.path("name").asText("").strip();
            if (canonicalName.isBlank()) {
                throw new GerritException("Gerrit 项目信息缺少项目名称。 ");
            }
            return new GerritProject(canonicalName, repositoryUrl(canonicalName));
        } catch (GerritException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new GerritException("Gerrit 项目信息解析失败。", exception);
        }
    }

    static String stripXssiPrefix(String body) {
        if (body == null) {
            return "";
        }
        String normalized = body.stripLeading();
        if (!normalized.startsWith(")]}'")) {
            return normalized;
        }
        int newline = normalized.indexOf('\n');
        return newline < 0 ? "" : normalized.substring(newline + 1);
    }

    private String repositoryUrl(String projectName) {
        String path = Arrays.stream(projectName.split("/", -1))
                .map(GerritClient::encodePathSegment)
                .collect(Collectors.joining("/"));
        return baseUri.resolve(path).toString();
    }

    private static URI normalizeBaseUri(URI value) {
        String normalized = value.toString();
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return URI.create(normalized);
    }

    private static String requireProjectName(String value) {
        if (value == null || value.isBlank()) {
            throw new GerritException("请输入 Gerrit 项目名称。 ");
        }
        String normalized = value.strip();
        if (normalized.contains("..") || normalized.startsWith("/") || normalized.endsWith("/")) {
            throw new GerritException("Gerrit 项目名称格式不正确。 ");
        }
        return normalized;
    }

    private static String encodeProjectId(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
