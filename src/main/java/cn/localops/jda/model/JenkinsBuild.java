package cn.localops.jda.model;

public record JenkinsBuild(int number, String result, String consoleText) {
    public boolean successful() {
        return "SUCCESS".equalsIgnoreCase(result);
    }
}
