package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentSettings;
import cn.localops.jda.model.JobDraft;
import cn.localops.jda.model.JobPreparationPlan;
import cn.localops.jda.model.JobPreparationPlan.Action;
import cn.localops.jda.model.JobPreparationPlan.Item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class JobRecoveryService {
    private final JenkinsClient client;
    private final String ownerId;
    private final Path backupDirectory;
    private final WorkflowJobConfigFactory factory = new WorkflowJobConfigFactory();

    JobRecoveryService(JenkinsClient client, String ownerId, Path backupDirectory) {
        this.client = client;
        this.ownerId = ownerId;
        this.backupDirectory = backupDirectory;
    }

    JobPreparationPlan inspect(List<JobDraft> drafts, DeploymentSettings settings) {
        List<Item> items = new ArrayList<>();
        for (JobDraft draft : drafts) {
            String desired = factory.build(draft, settings.gerritProject(), settings.gerritServerName(), settings.triggerSettings());
            var state = client.jobState(draft.jobName());
            if (!state.exists()) {
                items.add(new Item(draft, Action.CREATE, state, "", desired));
                continue;
            }
            if (ownerId.isBlank()) { throw ManagedJobConfig.protectedJob("缺少客户端身份，不能续建已有任务"); }
            if (state.active()) { throw ManagedJobConfig.protectedJob(draft.jobName() + " 正在构建或排队，不会终止原任务"); }
            String original = client.getJobConfig(draft.jobName());
            boolean legacy = ManagedJobConfig.verify(original, desired, ownerId, ManagedJobConfig.scope(draft, settings));
            boolean changed = ManagedJobConfig.differs(original, desired);
            Action action;
            if (state.everSucceeded()) {
                if (changed) { throw ManagedJobConfig.protectedJob(draft.jobName() + " 已成功部署，不属于首次失败脚本修复"); }
                action = Action.SKIP_SUCCESS;
            } else {
                if (state.lastBuildNumber() > 0 && !List.of("FAILURE", "ABORTED", "UNSTABLE", "NOT_BUILT").contains(state.lastResult())) {
                    throw ManagedJobConfig.protectedJob(draft.jobName() + " 不是已结束的失败任务");
                }
                action = legacy ? Action.ADOPT : changed ? Action.UPDATE : Action.RETRY;
            }
            items.add(new Item(draft, action, state, original, desired));
        }
        if (items.stream().allMatch(item -> item.action() == Action.SKIP_SUCCESS)) {
            throw ManagedJobConfig.protectedJob("本项目已经全部成功部署，无需重复执行首次部署");
        }
        return new JobPreparationPlan(items);
    }

    void validate(JobPreparationPlan approved, DeploymentSettings settings) {
        var current = inspect(approved.items().stream().map(Item::draft).toList(), settings);
        if (current.items().size() != approved.items().size()) { throw ManagedJobConfig.protectedJob("计划已变化"); }
        for (int index = 0; index < approved.items().size(); index++) {
            Item before = approved.items().get(index);
            Item after = current.items().get(index);
            if (before.action() != after.action() || !before.observedState().equals(after.observedState())
                    || !ManagedJobConfig.hash(before.originalXml()).equals(ManagedJobConfig.hash(after.originalXml()))
                    || !before.desiredXml().equals(after.desiredXml())) {
                throw ManagedJobConfig.protectedJob("确认后任务状态或配置发生变化，请重新预览");
            }
        }
    }

    void prepare(JobPreparationPlan approved, DeploymentSettings settings) {
        validate(approved, settings);
        // Save every required backup before the first remote write; a failed backup aborts the batch.
        for (Item item : approved.items()) {
            if (item.action() == Action.ADOPT || item.action() == Action.UPDATE) { backup(item); }
        }
        validate(approved, settings);
        for (Item item : approved.items()) {
            String scope = ManagedJobConfig.scope(item.draft(), settings);
            switch (item.action()) {
                case CREATE -> client.createJob(item.draft().jobName(), ManagedJobConfig.stamp(item.desiredXml(), ownerId, scope));
                case ADOPT -> client.updateFailedJob(item.draft().jobName(), item.observedState(), item.originalXml(),
                        ManagedJobConfig.stamp(item.originalXml(), ownerId, scope));
                case UPDATE -> client.updateFailedJob(item.draft().jobName(), item.observedState(), item.originalXml(),
                        ManagedJobConfig.update(item.originalXml(), item.desiredXml(), ownerId, scope));
                case RETRY, SKIP_SUCCESS -> { /* Existing pipeline remains unchanged. */ }
            }
        }
    }

    void validateBeforeBuild(Item item, DeploymentSettings settings) {
        var current = client.jobState(item.draft().jobName());
        var expected = item.action() == Action.CREATE
                ? new cn.localops.jda.model.JenkinsJobState(true, false, 0, "", 0) : item.observedState();
        if (!current.equals(expected) || current.active() || current.everSucceeded()) {
            throw ManagedJobConfig.protectedJob(item.draft().jobName() + " 在执行前状态已变化，请重新检测");
        }
        String xml = client.getJobConfig(item.draft().jobName());
        if (ManagedJobConfig.verify(xml, item.desiredXml(), ownerId, ManagedJobConfig.scope(item.draft(), settings))) {
            throw ManagedJobConfig.protectedJob("执行前任务身份标记丢失");
        }
        if (ManagedJobConfig.differs(xml, item.desiredXml())) { throw ManagedJobConfig.protectedJob("执行前配置发生变化"); }
    }

    private void backup(Item item) {
        if (backupDirectory == null) { throw ManagedJobConfig.protectedJob("未设置原配置备份目录"); }
        try {
            Path directory = backupDirectory.toAbsolutePath().normalize();
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) { throw new IOException("Backup directory must not be a link"); }
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                // USERNAME may describe the parent desktop user, not the effective process token.
                var runner = new ProcessRunner();
                var identity = runner.run(List.of("whoami.exe", "/user", "/fo", "csv", "/nh"), Duration.ofSeconds(10));
                var sid = java.util.regex.Pattern.compile("S-1-5-[0-9-]+").matcher(identity.output());
                if (!identity.successful() || !sid.find()) { throw new IOException("Current Windows account is unknown"); }
                var result = runner.run(List.of("icacls.exe", directory.toString(), "/inheritance:r", "/grant:r",
                        "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", "*" + sid.group() + ":(OI)(CI)F"), Duration.ofSeconds(10));
                if (!result.successful()) { throw new IOException("Cannot protect configuration backups"); }
            }
            String safeName = item.draft().jobName().replaceAll("[^A-Za-z0-9_-]", "_");
            Path file = Files.createTempFile(directory, safeName + "-", ".config.xml");
            Files.writeString(file, item.originalXml(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new JenkinsException("原 Jenkins 配置备份失败，已停止续建，未覆盖远程脚本。请检查备份目录权限。");
        }
    }
}
