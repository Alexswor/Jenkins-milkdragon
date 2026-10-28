package cn.localops.jda.model;

import java.util.List;

public record DeploymentRunResult(List<JobDraft> drafts, List<DeploymentOutcome> outcomes) {
    public DeploymentRunResult {
        drafts = List.copyOf(drafts);
        outcomes = List.copyOf(outcomes);
    }

    public boolean successful() {
        return outcomes.size() == drafts.size() && outcomes.stream().allMatch(DeploymentOutcome::successful);
    }

    public DeploymentOutcome failure() {
        return outcomes.stream().filter(outcome -> !outcome.successful()).findFirst().orElse(null);
    }
}
