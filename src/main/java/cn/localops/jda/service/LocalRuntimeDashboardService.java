package cn.localops.jda.service;

import cn.localops.jda.model.ManagedRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class LocalRuntimeDashboardService {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(45);
    private static final String BACKEND_PREFIX = "JdaBackend_";
    private static final String IIS_POOL_PREFIX = "Jda_";
    private static final String POWERSHELL = "powershell.exe";

    private static final String LIST_SCRIPT = addressFunctions() + "\n" + """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
            $items = [Collections.Generic.List[object]]::new()
            Get-Service -Name 'JdaBackend_*' -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
                $registryPath = Join-Path 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services' $_.Name
                $registration = Get-ItemProperty -LiteralPath $registryPath -ErrorAction SilentlyContinue
                if ($registration -and $registration.ImagePath -like "*\\backend\\service\\$($_.Name).exe*") {
                    $startMode = switch ([int]$registration.Start) {
                        2 { 'Auto' }
                        3 { 'Manual' }
                        4 { 'Disabled' }
                        default { 'Unknown' }
                    }
                    $items.Add([pscustomobject]@{
                        kind = 'BACKEND_SERVICE'
                        id = $_.Name
                        displayName = $_.DisplayName
                        state = $_.Status.ToString()
                        autoStart = ($startMode -eq 'Auto')
                        detail = "Windows 服务 · ID $($_.Name) · 启动类型 $startMode"
                        addresses = @(if ($registration.ImagePath -match '^\\s*(?:"([^"\\r\\n]+[.]exe)"|(.+?[.]exe))(?:\\s|$)') {
                            $wrapperPath = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
                            Get-BackendAddresses ([Environment]::ExpandEnvironmentVariables($wrapperPath)) $_.Name
                        })
                    })
                }
            }
            $iisAssembly = Join-Path $env:windir 'System32/inetsrv/Microsoft.Web.Administration.dll'
            if (Test-Path $iisAssembly -PathType Leaf) {
                Add-Type -Path $iisAssembly
                $manager = [Microsoft.Web.Administration.ServerManager]::new()
                try {
                    foreach ($site in $manager.Sites) {
                        $application = $site.Applications['/']
                        $pool = if ($application) { $application.ApplicationPoolName } else { '' }
                        if ($pool -like 'Jda_*') {
                            $binding = $site.Bindings | Where-Object Protocol -eq 'http' | Select-Object -First 1
                            $port = if ($binding) { $binding.EndPoint.Port } else { '未绑定' }
                            $items.Add([pscustomobject]@{
                                kind = 'IIS_SITE'
                                id = $site.Name
                                displayName = $site.Name
                                state = $site.State.ToString()
                                autoStart = [bool]$site.ServerAutoStart
                                detail = "端口 $port · 应用池 $pool"
                                addresses = @(Get-IisAddresses $site | Select-Object -Unique)
                            })
                        }
                    }
                } finally {
                    $manager.Dispose()
                }
            }
            ConvertTo-Json -InputObject @($items) -Compress
            """;

    private static final String START_BACKEND_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $runtimeId = '%s'
            $registryPath = Join-Path 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services' $runtimeId
            $registration = Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop
            if ($registration.ImagePath -notlike "*\\backend\\service\\$runtimeId.exe*") { throw 'Only backend services managed by this application can be controlled.' }
            Set-Service -Name $runtimeId -StartupType Automatic
            $service = Get-Service -Name $runtimeId
            if ($service.Status -ne 'Running') { Start-Service -Name $runtimeId }
            $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(30))
            """;
    private static final String STOP_BACKEND_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $runtimeId = '%s'
            $registryPath = Join-Path 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services' $runtimeId
            $registration = Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop
            if ($registration.ImagePath -notlike "*\\backend\\service\\$runtimeId.exe*") { throw 'Only backend services managed by this application can be controlled.' }
            Set-Service -Name $runtimeId -StartupType Disabled
            $service = Get-Service -Name $runtimeId
            if ($service.Status -ne 'Stopped') {
                Stop-Service -Name $runtimeId -Force
                $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
            }
            """;
    private static final String START_IIS_SCRIPT = iisActionScript(true);
    private static final String STOP_IIS_SCRIPT = iisActionScript(false);
    private static final String RENAME_BACKEND_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $runtimeId = '%s'
            $displayName = '%s'
            $registryPath = Join-Path 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services' $runtimeId
            $registration = Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop
            if ($registration.ImagePath -notlike "*\\backend\\service\\$runtimeId.exe*") { throw 'Only backend services managed by this application can be renamed.' }
            Set-Service -Name $runtimeId -DisplayName $displayName
            """;
    private static final String DELETE_BACKEND_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $runtimeId = '%s'
            $registryPath = Join-Path 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services' $runtimeId
            $registration = Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop
            if ($registration.ImagePath -notlike "*\\backend\\service\\$runtimeId.exe*") { throw 'Only backend services managed by this application can be deleted.' }
            $service = Get-Service -Name $runtimeId -ErrorAction Stop
            if ($service.Status -ne 'Stopped') {
                Stop-Service -Name $runtimeId -Force
                $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(30))
            }
            & sc.exe delete $runtimeId | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Failed to delete Windows service: $runtimeId" }
            """;
    private static final String DELETE_IIS_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $runtimeId = '%s'
            $iisAssembly = Join-Path $env:windir 'System32/inetsrv/Microsoft.Web.Administration.dll'
            if (-not (Test-Path $iisAssembly -PathType Leaf)) { throw 'IIS is not installed.' }
            Add-Type -Path $iisAssembly
            $manager = [Microsoft.Web.Administration.ServerManager]::new()
            try {
                $site = $manager.Sites[$runtimeId]
                if (-not $site) { throw "IIS site does not exist: $runtimeId" }
                $application = $site.Applications['/']
                $poolName = if ($application) { $application.ApplicationPoolName } else { '' }
                if ($poolName -notlike '%s*') { throw 'Only IIS sites managed by this application can be deleted.' }
                $poolInUse = @($manager.Sites | Where-Object { $_.Name -ne $runtimeId } | ForEach-Object {
                    $_.Applications | Where-Object { $_.ApplicationPoolName -eq $poolName }
                }).Count -gt 0
                if ($site.State -ne 'Stopped') { $site.Stop() | Out-Null }
                $manager.Sites.Remove($site)
                if (-not $poolInUse) {
                    $pool = $manager.ApplicationPools[$poolName]
                    if ($pool) { $manager.ApplicationPools.Remove($pool) }
                }
                $manager.CommitChanges()
            } finally {
                $manager.Dispose()
            }
            """.formatted("%s", IIS_POOL_PREFIX);

    private final CommandRunner commandRunner;
    private final CommandRunner queryRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalRuntimeDashboardService() {
        this(new ProcessRunner()::run, (command, timeout) -> new ProcessRunner().run(command, timeout, 1_048_576));
    }

    LocalRuntimeDashboardService(CommandRunner commandRunner) {
        this(commandRunner, commandRunner);
    }

    private LocalRuntimeDashboardService(CommandRunner commandRunner, CommandRunner queryRunner) {
        this.commandRunner = commandRunner;
        this.queryRunner = queryRunner;
    }

    public List<ManagedRuntime> list() throws IOException {
        ProcessRunner.ProcessResult result = queryRunner.run(powerShell(LIST_SCRIPT), QUERY_TIMEOUT);
        requireSuccess(result, "读取本机服务看板失败");
        if (result.output().endsWith("……输出已截断")) { throw new IOException("本机服务列表超过安全输出上限，无法完整显示，请导出诊断。"); }
        if (result.output().isBlank()) {
            return List.of();
        }
        JsonNode root = parseJsonPayload(result.output());
        List<ManagedRuntime> runtimes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(node -> parseRuntime(node).ifPresent(runtimes::add));
        } else {
            parseRuntime(root).ifPresent(runtimes::add);
        }
        runtimes.sort(Comparator.comparing(ManagedRuntime::kind).thenComparing(ManagedRuntime::displayName));
        return List.copyOf(runtimes);
    }

    public void start(ManagedRuntime runtime) throws IOException {
        execute(runtime, true);
    }

    public void stop(ManagedRuntime runtime) throws IOException {
        execute(runtime, false);
    }

    public void renameBackend(ManagedRuntime runtime, String newDisplayName) throws IOException {
        if (runtime == null || runtime.kind() != ManagedRuntime.Kind.BACKEND_SERVICE) {
            throw new IllegalArgumentException("只能重命名由本软件创建的后端服务。");
        }
        requireManagedBackend(runtime.id());
        String normalizedName = validateDisplayName(newDisplayName);
        ProcessRunner.ProcessResult result = commandRunner.run(
                powerShell(RENAME_BACKEND_SCRIPT.formatted(
                        powerShellLiteral(runtime.id()),
                        powerShellLiteral(normalizedName))),
                ACTION_TIMEOUT);
        requireSuccess(result, "重命名" + runtime.displayName() + "失败");
    }

    public void delete(ManagedRuntime runtime) throws IOException {
        if (runtime == null) {
            throw new IllegalArgumentException("请选择要删除的服务。");
        }
        String script = switch (runtime.kind()) {
            case BACKEND_SERVICE -> {
                requireManagedBackend(runtime.id());
                yield DELETE_BACKEND_SCRIPT;
            }
            case IIS_SITE -> DELETE_IIS_SCRIPT;
        };
        ProcessRunner.ProcessResult result = commandRunner.run(
                powerShell(script.formatted(powerShellLiteral(runtime.id()))), ACTION_TIMEOUT);
        requireSuccess(result, "删除" + runtime.displayName() + "失败");
    }

    private void execute(ManagedRuntime runtime, boolean start) throws IOException {
        String script = switch (runtime.kind()) {
            case BACKEND_SERVICE -> {
                requireManagedBackend(runtime.id());
                yield start ? START_BACKEND_SCRIPT : STOP_BACKEND_SCRIPT;
            }
            case IIS_SITE -> start ? START_IIS_SCRIPT : STOP_IIS_SCRIPT;
        };
        ProcessRunner.ProcessResult result = commandRunner.run(
                powerShell(script.formatted(powerShellLiteral(runtime.id()))), ACTION_TIMEOUT);
        requireSuccess(result, (start ? "启动" : "停止") + runtime.displayName() + "失败");
    }

    private java.util.Optional<ManagedRuntime> parseRuntime(JsonNode node) {
        try {
            ManagedRuntime.Kind kind = ManagedRuntime.Kind.valueOf(node.path("kind").asText());
            String id = node.path("id").asText();
            if (kind == ManagedRuntime.Kind.BACKEND_SERVICE && !id.startsWith(BACKEND_PREFIX)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ManagedRuntime(
                    kind,
                    id,
                    node.path("displayName").asText(id),
                    runtimeState(node.path("state").asText()),
                    node.path("autoStart").asBoolean(false),
                    node.path("detail").asText(""),
                    addresses(node)));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static List<String> addresses(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode address : node.path("addresses")) { if (address.isTextual()) { values.add(address.asText()); } }
        return values;
    }

    static String addressFunctions() {
        try (var input = LocalRuntimeDashboardService.class.getResourceAsStream("/dashboard/addresses.ps1")) {
            if (input == null) { throw new IllegalStateException("看板地址解析资源缺失，请完整解压软件包。"); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("无法加载看板地址解析资源。", failure); }
    }

    private static ManagedRuntime.State runtimeState(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "running", "started", "starting" -> ManagedRuntime.State.RUNNING;
            case "stopped", "stopping" -> ManagedRuntime.State.STOPPED;
            default -> ManagedRuntime.State.UNKNOWN;
        };
    }

    private static List<String> powerShell(String script) {
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of(
                POWERSHELL, "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript);
    }

    private static void requireManagedBackend(String id) {
        if (id == null || !id.startsWith(BACKEND_PREFIX)) {
            throw new IllegalArgumentException("只能操作由本软件创建的后端服务。");
        }
    }

    private static String validateDisplayName(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("服务显示名称不能为空。");
        }
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("服务显示名称不能超过 256 个字符。");
        }
        if (normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("服务显示名称不能包含换行或控制字符。");
        }
        return normalized;
    }

    private static void requireSuccess(ProcessRunner.ProcessResult result, String message) throws IOException {
        if (!result.successful()) {
            String detail = result.output().isBlank() ? "请确认软件以管理员身份运行。" : result.output();
            throw new IOException(message + "：" + detail);
        }
    }

    private JsonNode parseJsonPayload(String output) throws IOException {
        for (int index = 0; index < output.length(); index++) {
            char marker = output.charAt(index);
            if (marker != '[' && marker != '{') {
                continue;
            }
            try {
                return objectMapper.readTree(output.substring(index).strip());
            } catch (IOException ignored) {
                // Windows PowerShell may prefix stdout with CLIXML progress records.
            }
        }
        throw new IOException("本机服务查询没有返回有效数据。");
    }

    private static String powerShellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static String iisActionScript(boolean start) {
        String desired = start ? "$true" : "$false";
        String action = start
                ? "if ($site.State -ne 'Started') { $site.Start() | Out-Null }"
                : "if ($site.State -ne 'Stopped') { $site.Stop() | Out-Null }";
        return """
                $ErrorActionPreference = 'Stop'
                $runtimeId = '%s'
                $iisAssembly = Join-Path $env:windir 'System32/inetsrv/Microsoft.Web.Administration.dll'
                if (-not (Test-Path $iisAssembly -PathType Leaf)) { throw 'IIS is not installed.' }
                Add-Type -Path $iisAssembly
                $manager = [Microsoft.Web.Administration.ServerManager]::new()
                try {
                    $site = $manager.Sites[$runtimeId]
                    if (-not $site) { throw "IIS site does not exist: $runtimeId" }
                    $application = $site.Applications['/']
                    $pool = if ($application) { $application.ApplicationPoolName } else { '' }
                    if ($pool -notlike '%s*') { throw 'Only IIS sites managed by this application can be controlled.' }
                    $site.ServerAutoStart = %s
                    $manager.CommitChanges()
                    %s
                } finally {
                    $manager.Dispose()
                }
                """.formatted("%s", IIS_POOL_PREFIX, desired, action);
    }

    @FunctionalInterface
    interface CommandRunner {
        ProcessRunner.ProcessResult run(List<String> command, Duration timeout) throws IOException;
    }
}
