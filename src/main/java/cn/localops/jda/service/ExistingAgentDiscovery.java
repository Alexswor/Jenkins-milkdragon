package cn.localops.jda.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Discovers installed agents without launching commands from their configuration or reading secrets. */
final class ExistingAgentDiscovery {
    private static final Pattern OPTION = Pattern.compile(
            "(?:^|\\s)-(jar|url|name|workDir|jnlpUrl)(?:=|\\s+)(?:\"([^\"]*)\"|'([^']*)'|(\\S+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VARIABLE = Pattern.compile("%([^%]+)%");
    static final String SERVICE_QUERY = """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
            $items = [Collections.Generic.List[object]]::new()
            Get-Service | ForEach-Object {
                $service = $_
                $key = Join-Path 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services' $service.Name
                $registration = Get-ItemProperty -LiteralPath $key -ErrorAction Stop
                $image = [Environment]::ExpandEnvironmentVariables([string]$registration.ImagePath)
                if ($image -match '^\\s*(?:"(?<exe>[^"]+[.]exe)"|(?<exe>.+?[.]exe))(?:\\s|$)') {
                    $wrapper = $Matches.exe
                    $xml = [IO.Path]::ChangeExtension($wrapper, '.xml')
                    if (Test-Path -LiteralPath $xml -PathType Leaf) {
                        $items.Add([pscustomobject]@{ name = $service.Name; wrapper = $wrapper; running = ($service.Status -eq 'Running') })
                    }
                }
            }
            ConvertTo-Json -InputObject @($items) -Compress
            """;
    private final ServiceSource serviceSource;

    ExistingAgentDiscovery() {
        this(ExistingAgentDiscovery::readWindowsServices);
    }

    ExistingAgentDiscovery(ServiceSource serviceSource) {
        this.serviceSource = serviceSource;
    }

    List<InstalledAgent> discover() throws IOException {
        List<InstalledAgent> agents = new ArrayList<>();
        for (ServiceRegistration service : serviceSource.read()) {
            Path xml = service.wrapper().resolveSibling(
                    service.wrapper().getFileName().toString().replaceFirst("(?i)[.]exe$", ".xml"));
            readDefinition(service, xml).ifPresent(agents::add);
        }
        return List.copyOf(agents);
    }

    static Optional<InstalledAgent> readDefinition(ServiceRegistration service, Path xml) throws IOException {
        if (!Files.isRegularFile(xml)) {
            return Optional.empty();
        }
        try (var input = Files.newInputStream(xml)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Element root = builder.parse(input).getDocumentElement();
            if (!"service".equals(root.getTagName())) { return Optional.empty(); }
            Path directory = xml.toAbsolutePath().normalize().getParent();
            Map<String, String> variables = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            variables.putAll(System.getenv());
            var environment = root.getElementsByTagName("env");
            for (int i = 0; i < environment.getLength(); i++) {
                Element variable = (Element) environment.item(i);
                variables.put(variable.getAttribute("name"), variable.getAttribute("value"));
            }
            variables.put("BASE", directory.toString());
            String arguments = expand(text(root, "arguments"), variables);
            Map<String, String> options = new HashMap<>();
            var matcher = OPTION.matcher(arguments);
            while (matcher.find()) {
                String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
                options.put(matcher.group(1).toLowerCase(java.util.Locale.ROOT), value);
            }
            String jarValue = options.getOrDefault("jar", "");
            if (!jarValue.matches("(?i).*(?:^|[/\\\\])(agent|remoting|slave)[.]jar")) {
                return Optional.empty();
            }
            Path jar = directory.resolve(jarValue).normalize();
            if (!Files.isRegularFile(jar)) { throw new IOException("已安装的 Jenkins Agent 缺少 JAR，请检查原服务目录。"); }
            String name = options.getOrDefault("name", "");
            String controller = options.getOrDefault("url", "");
            if ((name.isBlank() || controller.isBlank()) && options.containsKey("jnlpurl")) {
                URI jnlp = URI.create(options.get("jnlpurl"));
                String path = jnlp.getPath();
                int computer = path.lastIndexOf("/computer/");
                if (computer >= 0) {
                    String remainder = path.substring(computer + "/computer/".length());
                    int slash = remainder.indexOf('/');
                    if (slash > 0) {
                        name = remainder.substring(0, slash);
                        controller = new URI(jnlp.getScheme(), jnlp.getAuthority(), path.substring(0, computer + 1), null, null).toString();
                    }
                }
            }
            if (name.isBlank() || controller.isBlank()) { return Optional.empty(); }
            String executable = expand(text(root, "executable"), variables);
            Path java = executable.isBlank() ? null : directory.resolve(executable).normalize();
            String work = options.getOrDefault("workdir", expand(text(root, "workingdirectory"), variables));
            Path workDirectory = work.isBlank() ? directory : directory.resolve(work).normalize();
            return Optional.of(new InstalledAgent(name, controller, directory, jar, workDirectory,
                    service.name(), service.running(), java != null && Files.isRegularFile(java) ? java : null));
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            // Do not attach XML/command-line exceptions: their messages may include an Agent Secret.
            if (!(service.name() + service.wrapper().getFileName()).toLowerCase(java.util.Locale.ROOT).matches(".*(jenkins|agent).*")) {
                return Optional.empty();
            }
            throw new IOException("无法安全读取已有 Windows 服务的 XML 配置，请检查配置格式与读取权限。");
        }
    }

    static boolean sameController(String left, String right) {
        try { return normalizeController(left).equals(normalizeController(right)); }
        catch (RuntimeException invalidUri) { return false; }
    }

    private static String normalizeController(String value) {
        URI uri = URI.create(value.strip()).normalize();
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid Controller URL");
        }
        int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + ":" + port + uri.getPath().replaceFirst("/+$", "");
    }

    private static String text(Element root, String name) {
        var values = root.getElementsByTagName(name);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().strip();
    }

    private static String expand(String value, Map<String, String> variables) {
        for (int round = 0; round < 5; round++) {
            String expanded = VARIABLE.matcher(value).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                    variables.getOrDefault(match.group(1), match.group())));
            if (expanded.equals(value)) { return value; }
            value = expanded;
        }
        return value;
    }

    private static List<ServiceRegistration> readWindowsServices() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) { return List.of(); }
        String encoded = Base64.getEncoder().encodeToString(SERVICE_QUERY.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start();
        // Drain stdout while waiting: machines with many WinSW services can exceed a Windows pipe buffer.
        FutureTask<byte[]> output = new FutureTask<>(() -> process.getInputStream().readNBytes(1_048_577));
        Thread.startVirtualThread(output);
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) { throw new IOException("本机服务检测超时，请重试检测。"); }
            if (process.exitValue() != 0) { throw new IOException("无法读取本机 Windows 服务，请检查服务与注册表读取权限。"); }
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            if (bytes.length > 1_048_576) { throw new IOException("本机服务检测信息过大，请确认本机 Agent 服务。"); }
            return parseServices(new String(bytes, StandardCharsets.UTF_8));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IOException("本机 Agent 检测已取消。");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IOException("无法读取完整的本机服务检测结果。");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
            process.getInputStream().close();
            output.cancel(true);
        }
    }

    static List<ServiceRegistration> parseServices(String output) throws IOException {
        var jsonLine = Pattern.compile("(?m)^\\s*(\\[(?:\\{.*})?])\\s*$").matcher(output);
        if (!jsonLine.find()) { throw new IOException("本机服务检测未返回完整信息，请重试检测。"); }
        try {
            var items = new ObjectMapper().readTree(jsonLine.group(1));
            List<ServiceRegistration> services = new ArrayList<>();
            for (var item : items) {
                services.add(new ServiceRegistration(item.path("name").asText(), Path.of(item.path("wrapper").asText()), item.path("running").asBoolean()));
            }
            return services;
        } catch (RuntimeException | IOException invalidJson) {
            throw new IOException("本机服务检测信息不完整，请重试检测。");
        }
    }

    @FunctionalInterface interface ServiceSource { List<ServiceRegistration> read() throws IOException; }
    record ServiceRegistration(String name, Path wrapper, boolean running) { }
    record InstalledAgent(String name, String controller, Path directory, Path jar, Path workDirectory,
                          String serviceId, boolean running, Path javaExecutable) { }
}
