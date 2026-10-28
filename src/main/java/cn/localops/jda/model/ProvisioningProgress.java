package cn.localops.jda.model;

public record ProvisioningProgress(int step, int totalSteps, String title, String detail) {
}

