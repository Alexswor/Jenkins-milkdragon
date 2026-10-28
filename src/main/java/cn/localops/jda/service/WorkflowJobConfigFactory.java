package cn.localops.jda.service;

import cn.localops.jda.model.JobDraft;
import cn.localops.jda.model.TriggerSettings;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

public final class WorkflowJobConfigFactory {
    public String build(JobDraft draft, String gerritProject, String gerritServerName) {
        return build(draft, gerritProject, gerritServerName, TriggerSettings.defaults());
    }

    public String build(
            JobDraft draft,
            String gerritProject,
            String gerritServerName,
            TriggerSettings triggerSettings) {
        try {
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            builderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            builderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            builderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            builderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = builderFactory.newDocumentBuilder().newDocument();

            var root = document.createElement("flow-definition");
            root.setAttribute("plugin", "workflow-job");
            document.appendChild(root);
            append(document, root, "actions", "");
            append(document, root, "description", "由 Jenkins一键部署助手管理；触发规则由客户端同步。");
            append(document, root, "keepDependencies", "false");
            append(document, root, "properties", "");

            var definition = document.createElement("definition");
            definition.setAttribute("class", "org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition");
            definition.setAttribute("plugin", "workflow-cps");
            root.appendChild(definition);
            var script = document.createElement("script");
            script.appendChild(document.createCDATASection(draft.pipelineScript()));
            definition.appendChild(script);
            append(document, definition, "sandbox", "true");

            var triggers = document.createElement("triggers");
            root.appendChild(triggers);
            var trigger = document.createElement(
                    "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritTrigger");
            trigger.setAttribute("plugin", "gerrit-trigger");
            triggers.appendChild(trigger);
            append(document, trigger, "spec", "");
            append(document, trigger, "silentMode", "false");
            append(document, trigger, "silentStartMode", "false");
            append(document, trigger, "escapeQuotes", "true");
            append(document, trigger, "nameAndEmailParameterMode", "PLAIN");
            append(document, trigger, "commitMessageParameterMode", "BASE64");
            append(document, trigger, "changeSubjectParameterMode", "PLAIN");
            append(document, trigger, "commentTextParameterMode", "BASE64");
            append(document, trigger, "dynamicTriggerConfiguration", "false");
            append(document, trigger, "triggerConfigURL", "");
            append(document, trigger, "triggerInformationAction", "");

            var projects = document.createElement("gerritProjects");
            trigger.appendChild(projects);
            var project = document.createElement(
                    "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject");
            projects.appendChild(project);
            append(document, project, "compareType", "PLAIN");
            append(document, project, "pattern", gerritProject);

            var branches = document.createElement("branches");
            project.appendChild(branches);
            var branch = document.createElement(
                    "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch");
            branches.appendChild(branch);
            append(document, branch, "compareType", triggerSettings.branchMatchType().name());
            append(document, branch, "pattern", triggerSettings.branchPattern());

            var filePaths = document.createElement("filePaths");
            project.appendChild(filePaths);
            var filePath = document.createElement(
                    "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.FilePath");
            filePaths.appendChild(filePath);
            append(document, filePath, "compareType", "ANT");
            append(document, filePath, "pattern", draft.watchedPath());
            emptyList(document, project, "forbiddenFilePaths");
            emptyList(document, project, "topics");
            emptyList(document, project, "hashtags");
            append(document, project, "disableStrictForbiddenFileVerification", "false");

            var events = document.createElement("triggerOnEvents");
            trigger.appendChild(events);
            events.appendChild(document.createElement(
                    "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.events."
                            + triggerSettings.event().xmlElementName()));
            append(document, trigger, "serverName", gerritServerName);
            append(document, trigger, "notificationLevel", "ALL");

            if (triggerSettings.scmPollingEnabled()) {
                var scmTrigger = document.createElement("hudson.triggers.SCMTrigger");
                triggers.appendChild(scmTrigger);
                append(document, scmTrigger, "spec", triggerSettings.scmPollingSchedule());
                append(document, scmTrigger, "ignorePostCommitHooks", "false");
            }

            append(document, root, "disabled", "false");

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Jenkins Job XML。", exception);
        }
    }

    private static void append(org.w3c.dom.Document document, org.w3c.dom.Element parent, String name, String value) {
        var element = document.createElement(name);
        if (!value.isEmpty()) {
            element.setTextContent(value);
        }
        parent.appendChild(element);
    }

    private static void emptyList(org.w3c.dom.Document document, org.w3c.dom.Element parent, String name) {
        var element = document.createElement(name);
        element.setAttribute("class", "empty-list");
        parent.appendChild(element);
    }
}
