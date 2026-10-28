package cn.localops.jda.model;

import java.util.List;
import java.util.stream.Collectors;

public record JobPreparationPlan(List<Item> items) {
    public JobPreparationPlan { items = List.copyOf(items); }

    public enum Action {
        CREATE("新建并部署"), RETRY("继续失败/未运行任务，不修改脚本"), UPDATE("备份后更新失败任务的脚本/触发规则"),
        ADOPT("备份并认领旧版助手任务，继续首次部署"), SKIP_SUCCESS("沿用已成功的部分，不重复部署");
        private final String description;
        Action(String description) { this.description = description; }
        public String description() { return description; }
    }

    public record Item(JobDraft draft, Action action, JenkinsJobState observedState,
                       String originalXml, String desiredXml) {
        @Override public String toString() { return draft.jobName() + "：" + action.description(); }
    }

    public String summary() { return items.stream().map(Item::toString).collect(Collectors.joining("\n")); }
    @Override public String toString() { return summary(); }
}
