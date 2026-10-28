package cn.localops.jda.model;

public enum FrontendDeploymentMode {
    STATIC_DIRECTORY("静态目录 / Nginx"),
    IIS("IIS 站点");

    private final String displayName;

    FrontendDeploymentMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
