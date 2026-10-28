package cn.localops.jda.model;

public record JenkinsJobState(boolean exists, boolean active, int lastBuildNumber,
                              String lastResult, int successfulBuildNumber) {
    public static JenkinsJobState absent() { return new JenkinsJobState(false, false, 0, "", 0); }
    public boolean everSucceeded() { return successfulBuildNumber > 0; }
}
