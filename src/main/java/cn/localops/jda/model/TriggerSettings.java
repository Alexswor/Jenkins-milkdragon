package cn.localops.jda.model;

import java.util.Set;

public record TriggerSettings(
        Event event,
        MatchType branchMatchType,
        String branchPattern,
        boolean scmPollingEnabled,
        String scmPollingSchedule) {
    private static final Set<String> JENKINS_CRON_ALIASES = Set.of(
            "@yearly", "@annually", "@monthly", "@weekly", "@daily", "@midnight", "@hourly");

    public TriggerSettings {
        if (event == null) {
            throw new IllegalArgumentException("请选择 Gerrit 触发事件。");
        }
        if (branchMatchType == null) {
            throw new IllegalArgumentException("请选择分支匹配方式。");
        }
        branchPattern = branchPattern == null ? "" : branchPattern.strip();
        scmPollingSchedule = scmPollingSchedule == null ? "" : scmPollingSchedule.strip();
        if (branchPattern.isBlank()) {
            throw new IllegalArgumentException("分支规则不能为空。");
        }
        if (branchPattern.contains("\n") || branchPattern.contains("\r")) {
            throw new IllegalArgumentException("分支规则只能填写一行。");
        }
        if (scmPollingEnabled && !isValidSchedule(scmPollingSchedule)) {
            throw new IllegalArgumentException("SCM 轮询计划需填写 Jenkins cron 五段表达式，例如 H/5 * * * *。");
        }
    }

    public static TriggerSettings defaults() {
        return new TriggerSettings(Event.REF_UPDATED, MatchType.PLAIN, "refs/heads/dev", false, "H/5 * * * *");
    }

    public String summary() {
        String polling = scmPollingEnabled ? "SCM " + scmPollingSchedule : "SCM 关闭";
        return event + " · " + branchMatchType + ": " + branchPattern + " · " + polling;
    }

    public String manualCheckoutRef() {
        return branchMatchType == MatchType.PLAIN ? branchPattern : "refs/heads/dev";
    }

    private static boolean isValidSchedule(String value) {
        if (JENKINS_CRON_ALIASES.contains(value.toLowerCase())) {
            return true;
        }
        return value.split("\\s+").length == 5;
    }

    public enum Event {
        REF_UPDATED("Ref Updated", "PluginRefUpdatedEvent"),
        PATCHSET_CREATED("Patchset Created", "PluginPatchsetCreatedEvent"),
        CHANGE_MERGED("Change Merged", "PluginChangeMergedEvent"),
        CHANGE_ABANDONED("Change Abandoned", "PluginChangeAbandonedEvent"),
        CHANGE_RESTORED("Change Restored", "PluginChangeRestoredEvent");

        private final String displayName;
        private final String xmlElementName;

        Event(String displayName, String xmlElementName) {
            this.displayName = displayName;
            this.xmlElementName = xmlElementName;
        }

        public String xmlElementName() {
            return xmlElementName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum MatchType {
        PLAIN("Plain"),
        ANT("Path (ANT)"),
        REG_EXP("RegExp");

        private final String displayName;

        MatchType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
