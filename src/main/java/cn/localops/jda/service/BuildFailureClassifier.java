package cn.localops.jda.service;

import cn.localops.jda.model.BuildFailureType;

import java.util.List;
import java.util.Locale;

public final class BuildFailureClassifier {
    private static final List<String> SOURCE_SIGNALS = List.of(
            "compilation error", "compilation failure", "test failures", "there are test failures",
            "npm err!", "module not found", "failed to compile", "typescript error", "tsc --noemit",
            "could not resolve all files for configuration", "execution failed for task ':test'",
            "cannot find symbol", "package does not exist", "syntaxerror:",
            "tests run:", "failed to load applicationcontext", "maven-surefire-plugin",
            "claims to not accept jdbcurl", "could not resolve placeholder");
    private static final List<String> PIPELINE_SIGNALS = List.of(
            "workflowscript:", "multiplecompilationerrorsexception", "nosuchmethoderror",
            "no such dsl method", "script returned exit code", "is not recognized as an internal or external command",
            "commandnotfoundexception", "access is denied", "requested registry access is not allowed",
            "bundled winsw is missing", "offline installer is missing", "service id is already used",
            "iis port is already used", "administrator privileges are required", "import-module webadministration",
            "not signed by microsoft", "iis windows role installation failed", "windows must be restarted");
    private static final List<String> TRANSIENT_SIGNALS = List.of(
            "connection reset", "connection timed out", "connect timed out", "agent went offline",
            "channelclosedexception", "closedchannel", "cannot contact", "no space left on device",
            "waiting for next available executor", "could not resolve host", "temporary failure");

    public BuildFailureType classify(String result, String consoleText) {
        if ("SUCCESS".equalsIgnoreCase(result)) {
            return BuildFailureType.NONE;
        }
        String text = consoleText == null ? "" : consoleText.toLowerCase(Locale.ROOT);
        if (containsAny(text, SOURCE_SIGNALS)) {
            return BuildFailureType.SOURCE_CODE;
        }
        if (containsAny(text, PIPELINE_SIGNALS)) {
            return BuildFailureType.PIPELINE_OR_ENVIRONMENT;
        }
        if (containsAny(text, TRANSIENT_SIGNALS)
                || "ABORTED".equalsIgnoreCase(result)
                || "NOT_BUILT".equalsIgnoreCase(result)) {
            return BuildFailureType.TRANSIENT;
        }
        return BuildFailureType.UNKNOWN;
    }

    public String userMessage(BuildFailureType type) {
        return switch (type) {
            case NONE -> "部署成功。";
            case SOURCE_CODE -> "源码编译或测试失败，请让项目开发人员修复代码后重试。";
            case PIPELINE_OR_ENVIRONMENT -> "自动部署模板或服务器环境执行失败，可检查诊断日志或启用自定义 Pipeline。";
            case TRANSIENT -> "Jenkins Agent、网络或执行器出现临时故障；仅在确认尚未开始生产部署时允许有限重试。";
            case UNKNOWN -> "Jenkins 构建失败但未能安全归类，请导出诊断并查看控制台日志。";
        };
    }

    private static boolean containsAny(String text, List<String> signals) {
        return signals.stream().anyMatch(text::contains);
    }
}
