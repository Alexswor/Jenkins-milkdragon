package cn.localops.jda.service;

public final class BackendServiceNaming {
    private static final String PREFIX = "JdaBackend_";
    private static final int MAX_SERVICE_ID_LENGTH = 64;

    private BackendServiceNaming() {
    }

    public static String serviceId(String projectName) {
        String suffix = projectName == null ? "" : projectName.strip().toLowerCase()
                .replaceAll("[^a-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (suffix.isBlank()) {
            suffix = "project";
        }
        int maxSuffixLength = MAX_SERVICE_ID_LENGTH - PREFIX.length();
        if (suffix.length() > maxSuffixLength) {
            suffix = suffix.substring(0, maxSuffixLength);
        }
        return PREFIX + suffix;
    }

    public static String displayProjectName(String projectName) {
        String value = projectName == null ? "" : projectName.strip();
        return value.isBlank() ? "project" : value;
    }
}
