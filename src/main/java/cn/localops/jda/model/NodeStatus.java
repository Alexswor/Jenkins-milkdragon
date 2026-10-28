package cn.localops.jda.model;

public record NodeStatus(
        String displayName,
        boolean online,
        String architecture,
        String responseTime,
        String clockDifference,
        String freeDiskSpace,
        String freeTempSpace) {

    public static NodeStatus offline(String displayName) {
        return new NodeStatus(displayName, false, "待上报", "待上报", "待上报", "待上报", "待上报");
    }
}

