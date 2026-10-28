package cn.localops.jda.ui;

final class WizardState {
    enum Step {
        AGENT,
        PROJECT,
        DEPLOYMENT
    }

    private Step currentStep = Step.AGENT;
    private boolean agentOnline;
    private boolean projectReady;

    Step currentStep() {
        return currentStep;
    }

    boolean canOpen(Step step) {
        return switch (step) {
            case AGENT -> true;
            case PROJECT -> agentOnline;
            case DEPLOYMENT -> agentOnline && projectReady;
        };
    }

    boolean isComplete(Step step) {
        return switch (step) {
            case AGENT -> agentOnline;
            case PROJECT -> projectReady;
            case DEPLOYMENT -> false;
        };
    }

    boolean open(Step step) {
        if (!canOpen(step)) {
            return false;
        }
        currentStep = step;
        return true;
    }

    void markAgentOnline() {
        agentOnline = true;
        currentStep = Step.PROJECT;
    }

    void markAgentOffline() {
        agentOnline = false;
        projectReady = false;
        currentStep = Step.AGENT;
    }

    void markProjectReady() {
        if (!agentOnline) {
            throw new IllegalStateException("Agent 在线后才能进入项目部署阶段。");
        }
        projectReady = true;
        currentStep = Step.DEPLOYMENT;
    }

    void clearProject() {
        projectReady = false;
        if (agentOnline) {
            currentStep = Step.PROJECT;
        }
    }
}
