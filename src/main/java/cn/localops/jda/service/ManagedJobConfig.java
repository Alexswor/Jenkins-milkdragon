package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentSettings;
import cn.localops.jda.model.JobDraft;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class ManagedJobConfig {
    private static final String LEGACY_DESCRIPTION = "由 Jenkins一键部署助手管理；触发规则由客户端同步。";
    private static final Pattern MARKER = Pattern.compile("(?m)^JDA-MANAGED/1 ([A-Za-z0-9_-]+) ([A-F0-9]{64}) ([A-F0-9]{64})$");
    private static final String GERRIT = "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritTrigger";

    static String scope(JobDraft draft, DeploymentSettings settings) {
        return hash(String.join("\n", draft.jobName(), draft.kind(), settings.gerritProject(), settings.repositoryUrl().strip(),
                settings.agentLabel(), settings.productionRoot().toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT)));
    }

    static String stamp(String xml, String owner, String scope) {
        Document document = parse(xml);
        Element description = child(document.getDocumentElement(), "description");
        String human = MARKER.matcher(description.getTextContent()).replaceAll("").strip();
        description.setTextContent(human + "\nJDA-MANAGED/1 " + owner + " " + scope + " " + fingerprint(document));
        return serialize(document);
    }

    /** Returns true only for an exact, recognizable pre-marker assistant configuration. */
    static boolean verify(String xml, String desired, String owner, String scope) {
        Document document = parse(xml);
        Element root = document.getDocumentElement();
        if (!"flow-definition".equals(root.getTagName()) || "true".equals(text(root, "disabled"))) { throw protectedJob("任务类型不符或已被手动禁用"); }
        Element definition = child(root, "definition");
        if (!"org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition".equals(definition.getAttribute("class"))
                || !"true".equals(text(definition, "sandbox"))) { throw protectedJob("不是受管 Sandbox Pipeline"); }
        var marker = MARKER.matcher(text(root, "description"));
        if (marker.find()) {
            if (!marker.group(1).equals(owner) || !marker.group(2).equals(scope)) { throw protectedJob("来源或项目/Agent/生产目录与当前操作不一致"); }
            if (!marker.group(3).equals(fingerprint(document))) { throw protectedJob("原脚本或触发规则已被人工修改"); }
            if (marker.find()) { throw protectedJob("任务身份标记重复"); }
            return false;
        }
        String script = text(definition, "script");
        if (!LEGACY_DESCRIPTION.equals(text(root, "description").strip())
                || !script.contains("ARTIFACT_HELPERS_BASE64") || !script.contains("JDA_DEPLOYMENT_STARTED")
                || !fingerprint(document).equals(fingerprint(parse(desired)))) {
            throw protectedJob("没有可信身份标记，且不匹配当前项目的旧版助手模板");
        }
        return true;
    }

    static boolean differs(String current, String desired) { return !fingerprint(parse(current)).equals(fingerprint(parse(desired))); }

    static String update(String current, String desired, String owner, String scope) {
        Document target = parse(current);
        Document source = parse(desired);
        Element root = target.getDocumentElement();
        Element definition = child(root, "definition");
        child(definition, "script").setTextContent(text(child(source.getDocumentElement(), "definition"), "script"));
        Element oldTriggers = child(root, "triggers");
        Element desiredTriggers = child(source.getDocumentElement(), "triggers");
        Element oldGerrit = child(oldTriggers, GERRIT);
        Element desiredGerrit = child(desiredTriggers, GERRIT);
        for (String name : new String[]{"serverName", "gerritProjects", "triggerOnEvents"}) {
            oldGerrit.replaceChild(target.importNode(child(desiredGerrit, name), true), child(oldGerrit, name));
        }
        var oldPolling = oldTriggers.getElementsByTagName("hudson.triggers.SCMTrigger");
        var newPolling = desiredTriggers.getElementsByTagName("hudson.triggers.SCMTrigger");
        if (oldPolling.getLength() > 0) { oldTriggers.removeChild(oldPolling.item(0)); }
        if (newPolling.getLength() > 0) { oldTriggers.appendChild(target.importNode(newPolling.item(0), true)); }
        return stamp(serialize(target), owner, scope);
    }

    private static String fingerprint(Document document) {
        Element root = document.getDocumentElement();
        StringBuilder values = new StringBuilder(text(child(root, "definition"), "script").replace("\r\n", "\n").strip());
        Element triggers = child(root, "triggers");
        Element gerrit = child(triggers, GERRIT);
        // Include every project/branch/path/event, not only the first list member.
        for (String name : new String[]{"serverName", "gerritProjects", "triggerOnEvents"}) {
            canonical(child(gerrit, name), values);
        }
        var polling = triggers.getElementsByTagName("hudson.triggers.SCMTrigger");
        for (int index = 0; index < polling.getLength(); index++) { canonical((Element) polling.item(index), values); }
        return hash(values.toString());
    }

    private static void canonical(Element element, StringBuilder values) {
        values.append('\0').append(element.getTagName()).append('{');
        boolean hasElements = false;
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) { hasElements = true; canonical(child, values); }
        }
        if (!hasElements) { values.append(element.getTextContent().strip()); }
        values.append('}');
    }

    private static Element child(Element parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) { return element; }
        }
        throw protectedJob("配置字段不完整，无法安全确认身份");
    }

    private static String text(Element parent, String name) { return child(parent, name).getTextContent(); }

    private static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalidXml) { throw protectedJob("原配置无法安全解析"); }
    }

    private static String serialize(Document document) {
        try {
            var factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (Exception invalidXml) { throw protectedJob("无法生成受管配置"); }
    }

    static String hash(String value) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static JenkinsException protectedJob(String reason) { return new JenkinsException("为保护现有 Pipeline，已阻止覆盖：" + reason + "。"); }
}
