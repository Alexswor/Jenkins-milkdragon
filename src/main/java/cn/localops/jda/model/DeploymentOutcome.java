package cn.localops.jda.model;

public record DeploymentOutcome(
        String jobName,
        String kind,
        int buildNumber,
        String result,
        BuildFailureType failureType,
        String message,
        String consoleText,
        int attempts) {

    public boolean successful() {
        return failureType == BuildFailureType.NONE;
    }
}
