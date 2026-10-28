package cn.localops.jda.config;


public final class DefaultJenkinsSettings {
    public static final String URL = System.getenv().getOrDefault("JENKINS_URL", "https://jenkins.example.com/");

    private static final String USERNAME = System.getenv().getOrDefault("JENKINS_USERNAME", "");
    private static final String PASSWORD = System.getenv().getOrDefault("JENKINS_PASSWORD", "");

    private DefaultJenkinsSettings() {
    }

    public static boolean applyMissing(EnvConfig config) {
        boolean changed = putIfMissing(config, "JENKINS_URL", URL);
        changed |= putIfMissing(config, "JENKINS_USERNAME", USERNAME);
        changed |= putIfMissing(config, "JENKINS_PASSWORD", PASSWORD);
        return changed;
    }

    private static boolean putIfMissing(EnvConfig config, String key, String value) {
        if (config.get(key).isPresent() || value.isBlank()) {
            return false;
        }
        config.put(key, value);
        return true;
    }

}
