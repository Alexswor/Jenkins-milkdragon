package cn.localops.jda.model;

public record DeploymentProgress(int step, int totalSteps, String title, String detail) {
    public double fraction() {
        return totalSteps <= 0 ? 0 : Math.min(1, Math.max(0, (double) step / totalSteps));
    }
}
