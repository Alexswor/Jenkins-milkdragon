package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentSettings;
import cn.localops.jda.model.DeploymentOutcome;
import cn.localops.jda.model.DeploymentProgress;
import cn.localops.jda.model.DeploymentRunResult;
import cn.localops.jda.model.JobDraft;
import cn.localops.jda.model.JenkinsBuild;
import cn.localops.jda.model.JobPreparationPlan;
import cn.localops.jda.model.BuildFailureType;
import cn.localops.jda.model.ProjectProfile;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class JobProvisioningService {
    private final JenkinsClient jenkinsClient;
    private final PipelineTemplateService pipelineTemplateService;
    private final WorkflowJobConfigFactory configFactory;
    private final BuildFailureClassifier failureClassifier;
    private JobRecoveryService recoveryService;

    public JobProvisioningService(JenkinsClient jenkinsClient) {
        this(jenkinsClient, new PipelineTemplateService(), new WorkflowJobConfigFactory(), new BuildFailureClassifier());
    }

    public JobProvisioningService(JenkinsClient jenkinsClient, String ownerId, Path backupDirectory) {
        this(jenkinsClient);
        if (ownerId == null || !ownerId.matches("[A-Za-z0-9_-]{4,80}")) { throw new IllegalArgumentException("客户端 Job 身份无效。"); }
        this.recoveryService = new JobRecoveryService(jenkinsClient, ownerId, backupDirectory);
    }

    JobProvisioningService(
            JenkinsClient jenkinsClient,
            PipelineTemplateService pipelineTemplateService,
            WorkflowJobConfigFactory configFactory,
            BuildFailureClassifier failureClassifier) {
        this.jenkinsClient = jenkinsClient;
        this.pipelineTemplateService = pipelineTemplateService;
        this.configFactory = configFactory;
        this.failureClassifier = failureClassifier;
    }

    public List<JobDraft> preview(ProjectProfile project, DeploymentSettings settings) {
        return pipelineTemplateService.createDrafts(project, settings);
    }

    public List<JobDraft> apply(ProjectProfile project, DeploymentSettings settings) {
        List<JobDraft> drafts = preview(project, settings);
        createJobs(drafts, settings);
        return drafts;
    }

    public void ensureJobsAvailable(ProjectProfile project, DeploymentSettings settings) {
        if (recoveryService == null) { ensureJobsAvailable(preview(project, settings)); }
        else { plan(project, settings); }
    }

    public JobPreparationPlan plan(ProjectProfile project, DeploymentSettings settings) {
        if (recoveryService == null) { throw new IllegalStateException("续建需要客户端身份与备份目录。"); }
        return recoveryService.inspect(preview(project, settings), settings);
    }

    public void validatePlan(JobPreparationPlan approved, DeploymentSettings settings) {
        recoveryService.validate(approved, settings);
    }

    public DeploymentRunResult applyAndDeploy(JobPreparationPlan approved, DeploymentSettings settings,
                                             Consumer<DeploymentProgress> progress) {
        recoveryService.prepare(approved, settings);
        List<JobDraft> drafts = approved.items().stream().map(JobPreparationPlan.Item::draft).toList();
        List<DeploymentOutcome> outcomes = new ArrayList<>();
        var order = approved.items().stream().sorted(Comparator.comparingInt(item -> "backend".equals(item.draft().kind()) ? 0 : 1)).toList();
        int step = 0;
        for (var item : order) {
            JobDraft draft = item.draft();
            step++;
            if (item.action() == JobPreparationPlan.Action.SKIP_SUCCESS) {
                outcomes.add(new DeploymentOutcome(draft.jobName(), draft.kind(), item.observedState().successfulBuildNumber(),
                        "SUCCESS", BuildFailureType.NONE, "沿用已成功部署，不重复执行", "", 0));
                progress.accept(new DeploymentProgress(step, order.size(), "跳过已成功部分", draft.jobName()));
                continue;
            }
            recoveryService.validateBeforeBuild(item, settings);
            DeploymentOutcome outcome = deployJob(draft, step, order.size(), progress);
            outcomes.add(outcome);
            if (!outcome.successful()) { break; }
        }
        return new DeploymentRunResult(drafts, outcomes);
    }

    public DeploymentRunResult applyAndDeploy(
            ProjectProfile project,
            DeploymentSettings settings,
            Consumer<DeploymentProgress> progress) {
        List<JobDraft> drafts = preview(project, settings);
        int totalSteps = drafts.size() + 1;
        progress.accept(new DeploymentProgress(0, totalSteps, "正在检查 Jenkins Job", "正在确认没有同名项目"));
        createJobs(drafts, settings);

        progress.accept(new DeploymentProgress(0, totalSteps, "Jenkins Job 创建完成", "尚未触发生产部署"));

        List<JobDraft> deploymentOrder = drafts.stream()
                .sorted(Comparator.comparingInt(draft -> "backend".equals(draft.kind()) ? 0 : 1))
                .toList();
        List<DeploymentOutcome> outcomes = new ArrayList<>();
        int completed = 0;
        for (JobDraft draft : deploymentOrder) {
            DeploymentOutcome outcome = deployJob(draft, completed + 1, totalSteps, progress);
            outcomes.add(outcome);
            completed++;
            if (!outcome.successful()) {
                break;
            }
        }
        return new DeploymentRunResult(drafts, outcomes);
    }

    private void createJobs(List<JobDraft> drafts, DeploymentSettings settings) {
        ensureJobsAvailable(drafts);
        for (JobDraft draft : drafts) {
            String xml = configFactory.build(
                    draft,
                    settings.gerritProject(),
                    settings.gerritServerName(),
                    settings.triggerSettings());
            jenkinsClient.createJob(draft.jobName(), xml);
        }
    }

    private void ensureJobsAvailable(List<JobDraft> drafts) {
        jenkinsClient.ensureJobsAbsent(drafts.stream().map(JobDraft::jobName).toList());
    }

    private DeploymentOutcome deployJob(
            JobDraft draft,
            int step,
            int totalSteps,
            Consumer<DeploymentProgress> progress) {
        final int maximumAttempts = 3;
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            int currentAttempt = attempt;
            progress.accept(new DeploymentProgress(
                    step,
                    totalSteps,
                    "正在首次部署 " + draft.jobName(),
                    currentAttempt == 1 ? "正在触发 Jenkins 构建" : "临时故障重试 " + currentAttempt + "/" + maximumAttempts));
            JenkinsBuild build = jenkinsClient.triggerAndWait(
                    draft.jobName(),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(45),
                    detail -> progress.accept(new DeploymentProgress(step, totalSteps, "正在部署 " + draft.jobName(), detail)));
            var failureType = failureClassifier.classify(build.result(), build.consoleText());
            if (build.successful()) {
                return new DeploymentOutcome(
                        draft.jobName(), draft.kind(), build.number(), build.result(), failureType,
                        failureClassifier.userMessage(failureType), diagnosticConsole(build.consoleText()), attempt);
            }
            if (failureType != cn.localops.jda.model.BuildFailureType.TRANSIENT || attempt == maximumAttempts
                    || !mayRetryBuild(draft, build.consoleText())) {
                return new DeploymentOutcome(
                        draft.jobName(), draft.kind(), build.number(), build.result(), failureType,
                        failureMessage(failureType, build.consoleText()), diagnosticConsole(build.consoleText()), attempt);
            }
        }
        throw new IllegalStateException("部署重试状态异常。");
    }

    static boolean mayRetryBuild(JobDraft draft, String consoleText) {
        // Missing/truncated evidence and custom pipelines must never cause a production deployment to be replayed.
        return draft.pipelineScript().contains("ARTIFACT_HELPERS_BASE64")
                && draft.pipelineScript().contains("JDA_DEPLOYMENT_STARTED")
                && consoleText != null && consoleText.contains("JDA_BUILD_ONLY")
                && !consoleText.contains("JDA_DEPLOYMENT_STARTED");
    }

    private static String diagnosticConsole(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int maximumLength = 40_000;
        return value.length() <= maximumLength ? value : value.substring(value.length() - maximumLength);
    }

    private String failureMessage(cn.localops.jda.model.BuildFailureType type, String consoleText) {
        String summary = lastUsefulLine(consoleText);
        return failureClassifier.userMessage(type) + (summary.isBlank() ? "" : " 日志摘要：" + summary);
    }

    private static String lastUsefulLine(String consoleText) {
        if (consoleText == null || consoleText.isBlank()) {
            return "";
        }
        String[] lines = consoleText.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].strip();
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (!line.isBlank()
                    && !line.startsWith("[Pipeline]")
                    && !lower.startsWith("finished:")
                    && (lower.contains("error") || lower.contains("exception") || lower.contains("failed")
                    || lower.contains("missing") || lower.contains("denied") || lower.contains("timeout"))) {
                return line.length() <= 260 ? line : line.substring(0, 260) + "…";
            }
        }
        return "";
    }
}
