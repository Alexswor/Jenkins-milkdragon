package cn.localops.jda.model;

import java.nio.file.Path;

public record DeploymentSettings(
        String gerritProject,
        String repositoryUrl,
        String gerritServerName,
        String agentLabel,
        Path productionRoot,
        TriggerSettings triggerSettings,
        FrontendDeploymentMode frontendDeploymentMode,
        String iisSiteName,
        int frontendPort,
        int backendPort,
        String backendServiceId,
        Path backendWinSwSource,
        Path backendJavaExecutable,
        String backendHealthPath,
        Path iisSupportDirectory,
        String customFrontendPipeline,
        String customBackendPipeline) {
}
