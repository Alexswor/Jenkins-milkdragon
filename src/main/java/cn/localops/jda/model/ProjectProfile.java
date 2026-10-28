package cn.localops.jda.model;

import java.nio.file.Path;
import java.util.Optional;

public record ProjectProfile(
        Path root,
        String projectName,
        Optional<Path> frontendDirectory,
        Optional<Path> backendDirectory,
        Optional<String> frontendTool,
        Optional<String> backendTool,
        DeploymentHints deploymentHints) {

    public boolean hasFrontend() {
        return frontendDirectory.isPresent();
    }

    public boolean hasBackend() {
        return backendDirectory.isPresent();
    }

    public boolean isJavaBackend() {
        return backendTool.filter(tool -> tool.equals("maven") || tool.equals("gradle")).isPresent();
    }

    public boolean isDotNetBackend() {
        return backendTool.filter("dotnet"::equals).isPresent();
    }

    public String relativeFrontendPath() {
        return frontendDirectory.map(root::relativize).map(ProjectProfile::slash).orElse("");
    }

    public String relativeBackendPath() {
        return backendDirectory.map(root::relativize).map(ProjectProfile::slash).orElse("");
    }

    private static String slash(Path value) {
        return value.toString().replace('\\', '/');
    }
}
