package cn.localops.jda.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class LegacyCredentialImporter {
    public boolean importIfPresent(Path source, EnvConfig target) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            return false;
        }

        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        String section = "";
        boolean changed = false;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = firstSeparator(line);
            if (separator < 1) {
                continue;
            }
            String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).strip();
            if (value.isBlank()) {
                continue;
            }
            if (key.contains("jenkins")) {
                section = "JENKINS";
                changed |= putIfMissing(target, "JENKINS_USERNAME", value);
            } else if (key.contains("gerrit")) {
                section = "";
            } else if (key.contains("密码") && !section.isBlank()) {
                changed |= putIfMissing(target, section + "_PASSWORD", value);
            }
        }
        return changed;
    }

    private static boolean putIfMissing(EnvConfig target, String key, String value) {
        if (target.get(key).isPresent()) {
            return false;
        }
        target.put(key, value);
        return true;
    }

    private static int firstSeparator(String line) {
        int colon = line.indexOf(':');
        int chineseColon = line.indexOf('：');
        int equals = line.indexOf('=');
        int result = Integer.MAX_VALUE;
        for (int candidate : new int[]{colon, chineseColon, equals}) {
            if (candidate >= 0) {
                result = Math.min(result, candidate);
            }
        }
        return result == Integer.MAX_VALUE ? -1 : result;
    }
}
