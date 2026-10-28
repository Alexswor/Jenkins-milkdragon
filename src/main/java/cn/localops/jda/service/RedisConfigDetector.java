package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentRunResult;
import cn.localops.jda.model.ProjectProfile;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class RedisConfigDetector {
    private static final int MAX_CONFIG_BYTES = 1_048_576;
    private static final Set<String> IGNORED = Set.of(".git", ".vs", "bin", "obj", "node_modules", "target", "dist", "build");
    private static final Set<String> REDIS_KEYS = Set.of("redis", "redisconnectionstring", "redisconfiguration", "redissettings", "redisoptions");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA).build();

    public boolean shouldRemind(DeploymentRunResult result, ProjectProfile project, Path productionRoot) {
        if (result == null || project == null || productionRoot == null || !project.hasBackend()
                || !result.successful() || result.outcomes().isEmpty()
                || result.outcomes().stream().anyMatch(outcome -> !"SUCCESS".equals(outcome.result()))
                || result.outcomes().stream().noneMatch(outcome -> "backend".equals(outcome.kind()))) {
            return false;
        }
        Map<String, String> values = new HashMap<>();
        Path backend = productionRoot.resolve("backend");
        boolean deployedDotNetBase = project.isDotNetBackend()
                && Files.isRegularFile(backend.resolve("app/appsettings.json"));
        if (!deployedDotNetBase) {
            sourceConfigurations(project).forEach(path -> values.putAll(readConfiguration(path)));
        }
        if (project.isDotNetBackend()) {
            for (String name : List.of("app/appsettings.json", "app/appsettings.Production.json", "config/appsettings.Production.json")) {
                values.putAll(readConfiguration(backend.resolve(name)));
            }
        } else if (project.isJavaBackend()) {
            for (String name : List.of("application.properties", "application.yml", "application.yaml",
                    "application-prod.properties", "application-prod.yml", "application-prod.yaml")) {
                values.putAll(readConfiguration(backend.resolve("config").resolve(name)));
            }
        }
        for (var entry : values.entrySet()) {
            String[] keys = entry.getKey().split("\\.");
            for (int index = 0; index < keys.length; index++) {
                if (!REDIS_KEYS.contains(keys[index])) { continue; }
                String prefix = String.join(".", List.of(keys).subList(0, index + 1));
                if (!disabled(values.get(prefix)) && !disabled(values.get(prefix + ".enabled"))
                        && meaningful(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Path> sourceConfigurations(ProjectProfile project) {
        List<Path> files = new ArrayList<>();
        Path root = project.backendDirectory().orElseThrow();
        try {
            Files.walkFileTree(root, Set.of(), 6, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return !directory.equals(root) && IGNORED.contains(directory.getFileName().toString().toLowerCase(Locale.ROOT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    boolean candidate = project.isDotNetBackend()
                            ? name.equals("appsettings.json") || name.equals("appsettings.production.json")
                            : name.matches("application(?:-prod)?[.](?:properties|ya?ml)");
                    if (candidate && attributes.isRegularFile()) { files.add(file); }
                    return files.size() >= 128 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) {
            // A best-effort reminder must not turn a completed Jenkins deployment into a failure.
        }
        files.sort(Comparator.<Path>comparingInt(path -> {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.contains("-prod.") || name.contains(".production.") ? 1 : 0;
        }).thenComparing(Path::toString));
        return files;
    }

    private static Map<String, String> readConfiguration(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_CONFIG_BYTES) { return values; }
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_CONFIG_BYTES + 1); }
            if (bytes.length > MAX_CONFIG_BYTES) { return values; }
            String content = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) {
                flattenJson(JSON.readTree(content), "", values);
            } else if (name.endsWith(".properties")) {
                Properties properties = new Properties();
                properties.load(new StringReader(content));
                properties.forEach((key, value) -> values.put(normalize(key.toString()), value.toString()));
            } else {
                for (String document : content.split("(?m)^---[ \\t]*(?:#.*)?$")) {
                    Map<String, String> documentValues = new HashMap<>();
                    List<String> keys = new ArrayList<>();
                    List<Integer> indents = new ArrayList<>();
                    for (String line : document.split("\\R")) {
                        String trimmed = stripComment(line).strip();
                        int separator = trimmed.indexOf(':');
                        if (separator < 1 || trimmed.startsWith("#")) { continue; }
                        int indent = line.length() - line.stripLeading().length();
                        while (!indents.isEmpty() && indents.getLast() >= indent) {
                            indents.removeLast(); keys.removeLast();
                        }
                        keys.add(unquote(trimmed.substring(0, separator)));
                        indents.add(indent);
                        documentValues.put(normalize(String.join(".", keys)), unquote(trimmed.substring(separator + 1)));
                    }
                    String profile = documentValues.getOrDefault("spring.config.activate.on-profile", documentValues.get("spring.profiles"));
                    if (profile == null || List.of(profile.split("\\s*[,|]\\s*")).contains("prod")) {
                        values.putAll(documentValues);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Never expose parser exceptions: configuration text may contain credentials.
            values.clear();
        }
        return values;
    }

    private static void flattenJson(JsonNode node, String prefix, Map<String, String> values) {
        if (node == null) { return; }
        if (node.isObject()) {
            node.properties().forEach(field -> flattenJson(field.getValue(),
                    prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), values));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) { flattenJson(node.get(index), prefix + "." + index, values); }
        } else { values.put(normalize(prefix), node.isNull() ? "" : node.asText()); }
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '\\' && quote == '"') { index++; continue; }
            if (current == quote) { quote = 0; }
            else if (quote == 0 && (current == '\'' || current == '"')) { quote = current; }
            else if (quote == 0 && current == '#' && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String normalize(String key) { return key.replace(':', '.').toLowerCase(Locale.ROOT); }

    private static String unquote(String value) {
        String trimmed = value.strip();
        if (trimmed.length() >= 2 && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    private static boolean disabled(String value) {
        if (value == null) { return false; }
        String trimmed = unquote(value);
        return trimmed.matches("(?i)false|off|no|0")
                || trimmed.matches("(?is)\\{.*\\benabled\\s*:\\s*(?:false|off|no|0)\\b.*}");
    }

    private static boolean meaningful(String value) {
        String trimmed = unquote(value);
        return !trimmed.isBlank() && !disabled(trimmed) && !trimmed.matches("(?i)null|~|\\{\\s*}|\\[\\s*]");
    }
}
