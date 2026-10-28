package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentSettings;
import cn.localops.jda.model.JobDraft;
import cn.localops.jda.model.ProjectProfile;
import cn.localops.jda.model.FrontendDeploymentMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class PipelineTemplateService {
    public List<JobDraft> createDrafts(ProjectProfile project, DeploymentSettings settings) {
        validate(project, settings);
        List<JobDraft> drafts = new ArrayList<>();
        if (project.hasFrontend()) {
            String path = project.relativeFrontendPath();
            String builtIn = frontendPipeline(project, settings);
            drafts.add(new JobDraft(
                    settings.gerritProject() + "-frontend",
                    "frontend",
                    watchedPath(path),
                    selectedPipeline(settings.customFrontendPipeline(), builtIn, "前端")));
        }
        if (project.hasBackend()) {
            String path = project.relativeBackendPath();
            String builtIn = backendPipeline(project, settings);
            drafts.add(new JobDraft(
                    settings.gerritProject() + "-backend",
                    "backend",
                    watchedPath(path),
                    selectedPipeline(settings.customBackendPipeline(), builtIn, "后端")));
        }
        return List.copyOf(drafts);
    }

    private String frontendPipeline(ProjectProfile project, DeploymentSettings settings) {
        String relative = project.relativeFrontendPath();
        String sourceDirectory = relative.isBlank() ? "source" : "source/" + relative;
        String tool = project.frontendTool().orElse("npm");
        String install = switch (tool) {
            case "pnpm" -> "call pnpm install --frozen-lockfile";
            case "yarn" -> "call yarn install --frozen-lockfile";
            default -> "call npm ci --no-audit --no-fund";
        };
        String lint = switch (tool) {
            case "pnpm" -> "call pnpm run lint --if-present";
            case "yarn" -> "call yarn run lint --if-present";
            default -> "call npm run lint --if-present";
        };
        String build = switch (tool) {
            case "pnpm" -> "call pnpm run build";
            case "yarn" -> "call yarn run build";
            default -> "call npm run build";
        };

        return """
                pipeline {
                    agent { label '%s' }
                    options {
                        timestamps()
                        disableConcurrentBuilds()
                        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
                    }
                    environment {
                        REPO_URL = '%s'
                        SOURCE_DIR = 'source'
                        APP_DIR = '%s'
                        BUILD_OUTPUT_DIR = '%s/dist'
                        PROJECT_ROOT = '%s'
                        FRONTEND_MODE = '%s'
                        IIS_SITE_NAME = '%s'
                        IIS_APP_POOL = '%s'
                        FRONTEND_PORT = '%d'
                        BACKEND_PORT = '%d'
                        BACKEND_ENABLED = '%s'
                        PROXY_PATH = '%s'
                        IIS_SUPPORT_DIR = '%s'
                        WEB_CONFIG_BASE64 = '%s'
                        ARTIFACT_KIND = 'frontend'
                        ARTIFACT_HELPERS_BASE64 = '%s'
                    }
                    stages {
                        stage('Check Environment') {
                            steps {
                                echo 'JDA_BUILD_ONLY'
                                bat 'git --version'
                                bat 'node --version'
                                bat '%s --version'
                            }
                        }
                        stage('Checkout Gerrit Revision') {
                            steps {
                                deleteDir()
                                timeout(time: 5, unit: 'MINUTES') {
                                    withCredentials([usernamePassword(credentialsId: 'gerrit-http', usernameVariable: 'GERRIT_USERNAME', passwordVariable: 'GERRIT_PASSWORD')]) {
                                        powershell '''
                                            $ErrorActionPreference = 'Stop'
                                            $env:GIT_TERMINAL_PROMPT = '0'
                                            $env:GCM_INTERACTIVE = 'Never'
                                            $credential = "$($env:GERRIT_USERNAME):$($env:GERRIT_PASSWORD)"
                                            $authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential))
                                            $env:GIT_CONFIG_COUNT = '1'
                                            $env:GIT_CONFIG_KEY_0 = 'http.extraHeader'
                                            $env:GIT_CONFIG_VALUE_0 = "Authorization: Basic $authorization"
                                            try {
                                                $refName = if ($env:GERRIT_REFNAME) { $env:GERRIT_REFNAME } elseif ($env:GERRIT_REFSPEC) { $env:GERRIT_REFSPEC } elseif ($env:GERRIT_BRANCH) { "refs/heads/$($env:GERRIT_BRANCH)" } else { '%s' }
                                                git init $env:SOURCE_DIR
                                                if ($LASTEXITCODE -ne 0) { throw 'Git init failed.' }
                                                git -C $env:SOURCE_DIR remote add origin $env:REPO_URL
                                                if ($LASTEXITCODE -ne 0) { throw 'Git remote setup failed.' }
                                                git -C $env:SOURCE_DIR fetch --no-tags --depth=1 --progress origin $refName
                                                if ($LASTEXITCODE -ne 0) { throw 'Git fetch failed. Check Gerrit credentials and repository read permission.' }
                                                $revision = if ($env:GERRIT_NEWREV) { $env:GERRIT_NEWREV } elseif ($env:GERRIT_PATCHSET_REVISION) { $env:GERRIT_PATCHSET_REVISION } else { 'FETCH_HEAD' }
                                                git -C $env:SOURCE_DIR checkout --detach $revision
                                                if ($LASTEXITCODE -ne 0) { throw 'Git checkout failed.' }
                                                git -C $env:SOURCE_DIR rev-parse HEAD
                                                if ($LASTEXITCODE -ne 0) { throw 'Git revision verification failed.' }
                                            } finally {
                                                Remove-Item Env:GIT_CONFIG_COUNT,Env:GIT_CONFIG_KEY_0,Env:GIT_CONFIG_VALUE_0 -ErrorAction SilentlyContinue
                                            }
                                        '''
                                    }
                                }
                            }
                        }
                        stage('Install Dependencies') {
                            steps { dir(env.APP_DIR) { bat '%s' } }
                        }
                        stage('Lint') {
                            steps { dir(env.APP_DIR) { bat '%s' } }
                        }
                        stage('Build') {
                            steps { dir(env.APP_DIR) { bat '%s' } }
                        }
                        stage('Verify And Archive') {
                            steps {
                                powershell '''
                                    $ErrorActionPreference = 'Stop'
                                    . ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:ARTIFACT_HELPERS_BASE64))))
                                    $dist = Join-Path $env:WORKSPACE $env:BUILD_OUTPUT_DIR
                                    if (-not (Test-Path (Join-Path $dist 'index.html') -PathType Leaf)) {
                                        throw 'Frontend index.html is missing.'
                                    }
                                    New-Item -ItemType Directory -Path "$env:WORKSPACE/artifacts" -Force | Out-Null
                                    New-VerifiedPackage $dist "$env:WORKSPACE/artifacts/frontend.zip" @('index.html')
                                '''
                                archiveArtifacts artifacts: 'artifacts/frontend.zip', fingerprint: true
                            }
                        }
                        stage('Backup And Deploy') {
                            steps {
                                echo 'JDA_DEPLOYMENT_STARTED'
                                powershell '''
%s
                                '''
                            }
                        }
                    }
                }
                """.formatted(
                groovy(settings.agentLabel()),
                groovy(settings.repositoryUrl()),
                groovy(sourceDirectory),
                groovy(sourceDirectory),
                groovy(slash(settings.productionRoot().toString())),
                settings.frontendDeploymentMode().name(),
                groovy(settings.iisSiteName()),
                groovy(iisApplicationPool(settings.iisSiteName())),
                settings.frontendPort(),
                settings.backendPort(),
                Boolean.toString(project.hasBackend()),
                groovy(project.deploymentHints().proxyPath()),
                groovy(slash(settings.iisSupportDirectory() == null ? "" : settings.iisSupportDirectory().toString())),
                iisWebConfigBase64(project, settings),
                PipelineArtifactScripts.base64(),
                tool,
                groovy(settings.triggerSettings().manualCheckoutRef()),
                groovy(install),
                groovy(lint),
                groovy(build),
                frontendDeploymentScript(project, settings));
    }

    private String backendPipeline(ProjectProfile project, DeploymentSettings settings) {
        if (project.isDotNetBackend()) {
            return new DotNetPipelineTemplate().create(project, settings);
        }
        String relative = project.relativeBackendPath();
        String sourceDirectory = relative.isBlank() ? "source" : "source/" + relative;
        String tool = project.backendTool().orElse("maven");
        String buildCommand = switch (tool) {
            case "gradle" -> "if exist gradlew.bat (call gradlew.bat clean build) else (call gradle clean build)";
            default -> "if exist mvnw.cmd (call mvnw.cmd -B clean verify) else (call mvn -B clean verify)";
        };
        String artifactPattern = switch (tool) {
            case "gradle" -> "build/libs/*.jar";
            default -> "target/*.jar";
        };

        return """
                pipeline {
                    agent { label '%s' }
                    options {
                        timestamps()
                        disableConcurrentBuilds()
                        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
                    }
                    environment {
                        REPO_URL = '%s'
                        SOURCE_DIR = 'source'
                        APP_DIR = '%s'
                        ARTIFACT_PATTERN = '%s'
                        PROJECT_ROOT = '%s'
                        SERVICE_ID = '%s'
                        WINSW_SOURCE = '%s'
                        JAVA_EXE = '%s'
                        BACKEND_PORT = '%d'
                        PRODUCTION_CONFIG = '%s/backend/config/application-prod.yml'
                        SERVICE_XML_BASE64 = '%s'
                        BACKEND_HEALTH_PATH = '%s'
                        ARTIFACT_KIND = 'java'
                        ARTIFACT_HELPERS_BASE64 = '%s'
                    }
                    stages {
                        stage('Check Environment') {
                            steps {
                                echo 'JDA_BUILD_ONLY'
                                bat 'git --version'
                                powershell '& $env:JAVA_EXE -version'
                                powershell '''
                                    if (-not (Test-Path $env:PRODUCTION_CONFIG -PathType Leaf)) {
                                        throw 'Production configuration is missing. Recreate the deployment from Jenkins一键部署助手.'
                                    }
                                '''
                            }
                        }
                        stage('Checkout Gerrit Revision') {
                            steps {
                                deleteDir()
                                timeout(time: 5, unit: 'MINUTES') {
                                    withCredentials([usernamePassword(credentialsId: 'gerrit-http', usernameVariable: 'GERRIT_USERNAME', passwordVariable: 'GERRIT_PASSWORD')]) {
                                        powershell '''
                                            $ErrorActionPreference = 'Stop'
                                            $env:GIT_TERMINAL_PROMPT = '0'
                                            $env:GCM_INTERACTIVE = 'Never'
                                            $credential = "$($env:GERRIT_USERNAME):$($env:GERRIT_PASSWORD)"
                                            $authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential))
                                            $env:GIT_CONFIG_COUNT = '1'
                                            $env:GIT_CONFIG_KEY_0 = 'http.extraHeader'
                                            $env:GIT_CONFIG_VALUE_0 = "Authorization: Basic $authorization"
                                            try {
                                                $refName = if ($env:GERRIT_REFNAME) { $env:GERRIT_REFNAME } elseif ($env:GERRIT_REFSPEC) { $env:GERRIT_REFSPEC } elseif ($env:GERRIT_BRANCH) { "refs/heads/$($env:GERRIT_BRANCH)" } else { '%s' }
                                                git init $env:SOURCE_DIR
                                                if ($LASTEXITCODE -ne 0) { throw 'Git init failed.' }
                                                git -C $env:SOURCE_DIR remote add origin $env:REPO_URL
                                                if ($LASTEXITCODE -ne 0) { throw 'Git remote setup failed.' }
                                                git -C $env:SOURCE_DIR fetch --no-tags --depth=1 --progress origin $refName
                                                if ($LASTEXITCODE -ne 0) { throw 'Git fetch failed. Check Gerrit credentials and repository read permission.' }
                                                $revision = if ($env:GERRIT_NEWREV) { $env:GERRIT_NEWREV } elseif ($env:GERRIT_PATCHSET_REVISION) { $env:GERRIT_PATCHSET_REVISION } else { 'FETCH_HEAD' }
                                                git -C $env:SOURCE_DIR checkout --detach $revision
                                                if ($LASTEXITCODE -ne 0) { throw 'Git checkout failed.' }
                                                git -C $env:SOURCE_DIR rev-parse HEAD
                                                if ($LASTEXITCODE -ne 0) { throw 'Git revision verification failed.' }
                                            } finally {
                                                Remove-Item Env:GIT_CONFIG_COUNT,Env:GIT_CONFIG_KEY_0,Env:GIT_CONFIG_VALUE_0 -ErrorAction SilentlyContinue
                                            }
                                        '''
                                    }
                                }
                            }
                        }
                        stage('Build And Test') {
                            steps { dir(env.APP_DIR) { bat '%s' } }
                        }
                        stage('Verify And Archive') {
                            steps {
                                powershell '''
                                    $ErrorActionPreference = 'Stop'
                                    . ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:ARTIFACT_HELPERS_BASE64))))
                                    $pattern = Join-Path $env:WORKSPACE (Join-Path $env:APP_DIR $env:ARTIFACT_PATTERN)
                                    $artifact = Get-DeployableJar $pattern
                                    New-Item -ItemType Directory -Path "$env:WORKSPACE/artifacts" -Force | Out-Null
                                    Write-JarReceipt $artifact "$env:WORKSPACE/artifacts/backend-jar.json"
                                '''
                                archiveArtifacts artifacts: "${APP_DIR}/${ARTIFACT_PATTERN}", fingerprint: true
                            }
                        }
                        stage('Backup And Deploy') {
                            steps {
                                echo 'JDA_DEPLOYMENT_STARTED'
                                powershell '''
                                    $ErrorActionPreference = 'Stop'
                                    . ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:ARTIFACT_HELPERS_BASE64))))
                                    $pattern = Join-Path $env:WORKSPACE (Join-Path $env:APP_DIR $env:ARTIFACT_PATTERN)
                                    $artifact = Get-DeployableJar $pattern
                                    Assert-JarReceipt $artifact "$env:WORKSPACE/artifacts/backend-jar.json"
                                    function Test-BackendDeployment {
%s
                                    }
                                    $backend = Join-Path $env:PROJECT_ROOT 'backend'
                                    $serviceDirectory = Join-Path $backend 'service'
                                    $backup = Join-Path $env:PROJECT_ROOT ("backups/backend/" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
                                    New-Item -ItemType Directory -Path $backend,$serviceDirectory,$backup -Force | Out-Null
                                    $wrapper = Join-Path $serviceDirectory ($env:SERVICE_ID + '.exe')
                                    $serviceXml = Join-Path $serviceDirectory ($env:SERVICE_ID + '.xml')
                                    $escapedServiceId = $env:SERVICE_ID.Replace("'", "''")
                                    $registered = Get-CimInstance Win32_Service -Filter "Name='$escapedServiceId'" -ErrorAction SilentlyContinue
                                    if ($registered -and $registered.PathName -notlike "*$wrapper*") {
                                        throw "Service ID is already used by another executable: $($env:SERVICE_ID)"
                                    }
                                    $manuallyDisabled = $registered -and $registered.StartMode -eq 'Disabled'
                                    $service = Get-Service -Name $env:SERVICE_ID -ErrorAction SilentlyContinue
                                    $currentArtifact = Join-Path $backend 'app.jar'
                                    $backupArtifact = Join-Path $backup 'app.jar'
                                    $artifactExisted = Test-Path -LiteralPath $currentArtifact -PathType Leaf
                                    if ($artifactExisted) { Copy-Item $currentArtifact $backupArtifact -Force }
                                    if (-not (Test-Path $env:WINSW_SOURCE -PathType Leaf)) { throw 'Bundled WinSW is missing from the Agent directory.' }
                                    if (-not (Test-Path $env:JAVA_EXE -PathType Leaf)) { throw 'Bundled Java runtime is missing from the Agent directory.' }
                                    if (-not (Test-Path $wrapper -PathType Leaf)) { Copy-Item $env:WINSW_SOURCE $wrapper -Force }
                                    $previousServiceXml = if (Test-Path -LiteralPath $serviceXml -PathType Leaf) { [IO.File]::ReadAllBytes($serviceXml) } else { $null }
                                    $serviceXmlModified = $false
                                    $artifactModified = $false
                                    $serviceInstalled = $false
                                    $serviceStopped = $false
                                    try {
                                        if ($service -and $service.Status -ne 'Stopped') {
                                            Stop-Service -Name $service.Name -ErrorAction Stop
                                            $serviceStopped = $true
                                            $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(90))
                                        }
                                        $artifactModified = $true
                                        Copy-Item $artifact.FullName $currentArtifact -Force
                                        $serviceXmlModified = $true
                                        [IO.File]::WriteAllBytes($serviceXml, [Convert]::FromBase64String($env:SERVICE_XML_BASE64))
                                        $serviceCommand = if ($service) { 'refresh' } else { 'install' }
                                        & $wrapper $serviceCommand
                                        $serviceExitCode = $LASTEXITCODE
                                        $serviceInstalled = -not $service -and [bool](Get-Service -Name $env:SERVICE_ID -ErrorAction SilentlyContinue)
                                        if ($serviceExitCode -ne 0) { throw "WinSW $serviceCommand failed: $serviceExitCode" }
                                        if ($manuallyDisabled) {
                                            Set-Service -Name $env:SERVICE_ID -StartupType Disabled
                                            Write-Host 'Backend service remains manually stopped.'
                                        } else {
                                            Set-Service -Name $env:SERVICE_ID -StartupType Automatic
                                            & $wrapper start
                                            if ($LASTEXITCODE -ne 0) { throw "WinSW start failed: $LASTEXITCODE" }
                                            $running = Get-Service -Name $env:SERVICE_ID -ErrorAction Stop
                                            $running.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(90))
                                            Test-BackendDeployment
                                        }
                                    } catch {
                                        $deploymentError = $_
                                        try {
                                        $latest = Get-Service -Name $env:SERVICE_ID -ErrorAction SilentlyContinue
                                        if (($artifactModified -or $serviceStopped) -and $latest -and $latest.Status -ne 'Stopped') {
                                            Stop-Service -Name $latest.Name -ErrorAction Stop
                                            $latest.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(90))
                                        }
                                        if ($artifactModified -and $artifactExisted) { Copy-Item $backupArtifact $currentArtifact -Force }
                                        elseif ($artifactModified -and (Test-Path -LiteralPath $currentArtifact)) { Remove-Item -LiteralPath $currentArtifact -Force }
                                        if ($serviceInstalled) {
                                            & $wrapper uninstall | Out-Null
                                            if ($LASTEXITCODE -ne 0) { throw "WinSW uninstall failed: $LASTEXITCODE" }
                                        }
                                        if ($serviceXmlModified) {
                                            if ($null -ne $previousServiceXml) {
                                                [IO.File]::WriteAllBytes($serviceXml, $previousServiceXml)
                                                if ($service) {
                                                    & $wrapper refresh | Out-Null
                                                    if ($LASTEXITCODE -ne 0) { throw "WinSW rollback refresh failed: $LASTEXITCODE" }
                                                }
                                            } elseif (Test-Path -LiteralPath $serviceXml) { Remove-Item -LiteralPath $serviceXml -Force }
                                        }
                                        if ($manuallyDisabled -and $latest) {
                                            Set-Service -Name $latest.Name -StartupType Disabled -ErrorAction Stop
                                        } elseif ($serviceStopped -and $latest -and $latest.Status -ne 'Running') {
                                            Start-Service -Name $latest.Name -ErrorAction Stop
                                        }
                                        } catch { throw "Deployment failed: $($deploymentError.Exception.Message). Rollback failed: $($_.Exception.Message)" }
                                        throw $deploymentError
                                    }
                                '''
                            }
                        }
                    }
                }
                """.formatted(
                groovy(settings.agentLabel()),
                groovy(settings.repositoryUrl()),
                groovy(sourceDirectory),
                groovy(artifactPattern),
                groovy(slash(settings.productionRoot().toString())),
                groovy(settings.backendServiceId()),
                groovy(slash(settings.backendWinSwSource().toString())),
                groovy(slash(settings.backendJavaExecutable().toString())),
                settings.backendPort(),
                groovy(slash(settings.productionRoot().toString())),
                backendServiceConfigBase64(settings),
                groovy(settings.backendHealthPath()),
                PipelineArtifactScripts.base64(),
                groovy(settings.triggerSettings().manualCheckoutRef()),
                groovy(buildCommand),
                backendVerificationScript(settings));
    }

    private static void validate(ProjectProfile project, DeploymentSettings settings) {
        if (!project.hasFrontend() && !project.hasBackend()) {
            throw new IllegalArgumentException("项目中没有可生成的前端或后端模块。");
        }
        require(settings.gerritProject(), "Gerrit 项目名");
        require(settings.repositoryUrl(), "Gerrit 仓库地址");
        require(settings.gerritServerName(), "Gerrit Server 名称");
        require(settings.agentLabel(), "Agent 标签");
        if (settings.triggerSettings() == null) {
            throw new IllegalArgumentException("触发规则不能为空。");
        }
        if (settings.productionRoot() == null || !settings.productionRoot().isAbsolute()) {
            throw new IllegalArgumentException("生产根目录必须是绝对路径。");
        }
        if (project.hasFrontend()) {
            if (settings.frontendDeploymentMode() == null) {
                throw new IllegalArgumentException("请选择前端部署方式。");
            }
            if (settings.frontendDeploymentMode() == FrontendDeploymentMode.IIS) {
                require(settings.iisSiteName(), "IIS 站点名");
                if (settings.frontendPort() < 1 || settings.frontendPort() > 65_535) {
                    throw new IllegalArgumentException("前端端口必须在 1 到 65535 之间。");
                }
                if (settings.iisSupportDirectory() == null || !settings.iisSupportDirectory().isAbsolute()) {
                    throw new IllegalArgumentException("IIS 离线组件目录无效，请先完成本机 Agent 配置。");
                }
            }
        }
        if (project.hasBackend()) {
            require(settings.backendServiceId(), "后端 Windows 服务 ID");
            if (settings.backendPort() < 1 || settings.backendPort() > 65_535) {
                throw new IllegalArgumentException("后端端口必须在 1 到 65535 之间。");
            }
            if (settings.backendWinSwSource() == null || !settings.backendWinSwSource().isAbsolute()) {
                throw new IllegalArgumentException("WinSW 来源路径无效，请先完成本机 Agent 配置。");
            }
            if (project.isJavaBackend()
                    && (settings.backendJavaExecutable() == null || !settings.backendJavaExecutable().isAbsolute())) {
                throw new IllegalArgumentException("Java Runtime 路径无效，请先完成本机 Agent 配置。");
            }
        }
    }

    private static String frontendDeploymentScript(ProjectProfile project, DeploymentSettings settings) {
        String before = "";
        String after = "";
        String stopForRollback = "";
        String restoreSite = "";
        if (settings.frontendDeploymentMode() == FrontendDeploymentMode.IIS) {
            before = """
                    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
                    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
                    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
                        throw 'Administrator privileges are required to install and configure IIS.'
                    }

                    function Invoke-Msi($installer, $label) {
                        if (-not $installer) { throw "$label offline installer is missing from $env:IIS_SUPPORT_DIR" }
                        $signature = Get-AuthenticodeSignature -FilePath $installer.FullName
                        if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft Corporation') {
                            throw "$label installer is not signed by Microsoft: $($installer.FullName)"
                        }
                        $process = Start-Process msiexec.exe -ArgumentList @('/i', $installer.FullName, '/qn', '/norestart') -Wait -PassThru
                        if ($process.ExitCode -notin @(0, 3010)) { throw "$label installation failed: $($process.ExitCode)" }
                    }

                    if (Get-Command Install-WindowsFeature -ErrorAction SilentlyContinue) {
                        $feature = Get-WindowsFeature Web-Server
                        if (-not $feature.Installed) {
                            $installed = Install-WindowsFeature Web-Server,Web-Static-Content,Web-Default-Doc,Web-Http-Errors,Web-Http-Logging,Web-Request-Filtering,Web-Stat-Compression,Web-Mgmt-Tools,Web-Scripting-Tools -IncludeManagementTools
                            if (-not $installed.Success) { throw 'IIS Windows role installation failed.' }
                            if ($installed.RestartNeeded -eq 'Yes') { throw 'IIS was installed, but Windows must be restarted before deployment can continue.' }
                        }
                    } elseif (Get-Command Enable-WindowsOptionalFeature -ErrorAction SilentlyContinue) {
                        $enabled = Enable-WindowsOptionalFeature -Online -All -NoRestart -FeatureName IIS-WebServerRole,IIS-WebServer,IIS-StaticContent,IIS-DefaultDocument,IIS-HttpErrors,IIS-HttpLogging,IIS-RequestFiltering,IIS-HttpCompressionStatic,IIS-ManagementConsole,IIS-ManagementScriptingTools
                        if ($enabled.RestartNeeded) { throw 'IIS was installed, but Windows must be restarted before deployment can continue.' }
                    } else {
                        throw 'This Windows edition does not provide an IIS feature installation command.'
                    }

                    Import-Module WebAdministration -Force
                    $rewriteInstalled = @(Get-WebGlobalModule | Where-Object Name -eq 'RewriteModule').Count -gt 0
                    if (-not $rewriteInstalled) {
                        $rewriteInstaller = Get-ChildItem $env:IIS_SUPPORT_DIR -File -Filter '*.msi' -ErrorAction SilentlyContinue | Where-Object Name -Match 'rewrite.*amd64|rewrite_amd64' | Select-Object -First 1
                        Invoke-Msi $rewriteInstaller 'URL Rewrite 2.1 x64'
                        Import-Module WebAdministration -Force
                    }
                    if ($env:BACKEND_ENABLED -eq 'true') {
                        $arrInstalled = Test-Path (Join-Path $env:ProgramFiles 'IIS/Application Request Routing/requestRouter.dll')
                        if (-not $arrInstalled) {
                            $arrInstaller = Get-ChildItem $env:IIS_SUPPORT_DIR -File -Filter '*.msi' -ErrorAction SilentlyContinue | Where-Object Name -Match 'requestRouter.*amd64|arr.*amd64' | Select-Object -First 1
                            Invoke-Msi $arrInstaller 'Application Request Routing 3.0 x64'
                        }
                        Set-WebConfigurationProperty -PSPath 'MACHINE/WEBROOT/APPHOST' -Filter 'system.webServer/proxy' -Name enabled -Value true
                    }

                    $desiredBinding = "*:$($env:FRONTEND_PORT):"
                    $conflictingSite = Get-Website | Where-Object Name -ne $env:IIS_SITE_NAME | Where-Object {
                        @($_.Bindings.Collection | Where-Object {
                            $bindingParts = $_.bindingInformation.Split(':')
                            $_.protocol -eq 'http' -and $bindingParts.Count -ge 3 -and $bindingParts[1] -eq $env:FRONTEND_PORT
                        }).Count -gt 0
                    } | Select-Object -First 1
                    if ($conflictingSite) { throw "IIS port is already used by site '$($conflictingSite.Name)': $($env:FRONTEND_PORT)" }

                    $existingSite = Get-Website -Name $env:IIS_SITE_NAME -ErrorAction SilentlyContinue
                    $manuallyStopped = $existingSite -and -not [bool]$existingSite.serverAutoStart
                    if ($existingSite -and $existingSite.State -eq 'Started') {
                        Stop-WebSite -Name $env:IIS_SITE_NAME
                        $siteStopped = $true
                    }
                    """;
            stopForRollback = "if ($frontendModified -and (Get-Website -Name $env:IIS_SITE_NAME -ErrorAction SilentlyContinue)) { Stop-WebSite -Name $env:IIS_SITE_NAME -ErrorAction Stop }";
            restoreSite = "if ($siteStopped) { Start-WebSite -Name $env:IIS_SITE_NAME -ErrorAction Stop }";
            after = """
                        $appPoolPath = "IIS:/AppPools/$($env:IIS_APP_POOL)"
                        if (-not (Test-Path $appPoolPath)) { New-WebAppPool -Name $env:IIS_APP_POOL | Out-Null }
                        Set-ItemProperty $appPoolPath -Name managedRuntimeVersion -Value ''
                        $site = Get-Website -Name $env:IIS_SITE_NAME -ErrorAction SilentlyContinue
                        if (-not $site) {
                            New-Website -Name $env:IIS_SITE_NAME -PhysicalPath $frontend -Port ([int]$env:FRONTEND_PORT) -ApplicationPool $env:IIS_APP_POOL | Out-Null
                        } else {
                            $sitePath = "IIS:/Sites/$($env:IIS_SITE_NAME)"
                            Set-ItemProperty $sitePath -Name physicalPath -Value $frontend
                            Set-ItemProperty $sitePath -Name applicationPool -Value $env:IIS_APP_POOL
                            $desiredBinding = "*:$($env:FRONTEND_PORT):"
                            if (-not (Get-WebBinding -Name $env:IIS_SITE_NAME -Protocol http | Where-Object bindingInformation -eq $desiredBinding)) {
                                New-WebBinding -Name $env:IIS_SITE_NAME -Protocol http -Port ([int]$env:FRONTEND_PORT) -IPAddress '*' | Out-Null
                            }
                            $obsoleteBindings = @(Get-WebBinding -Name $env:IIS_SITE_NAME -Protocol http | Where-Object {
                                $_.bindingInformation -match '^[*]:[0-9]+:$' -and $_.bindingInformation -ne $desiredBinding
                            })
                            foreach ($binding in $obsoleteBindings) {
                                $obsoletePort = [int]$binding.bindingInformation.Split(':')[1]
                                Remove-WebBinding -Name $env:IIS_SITE_NAME -Protocol http -IPAddress '*' -Port $obsoletePort -HostHeader ''
                            }
                        }
                        if ($env:WEB_CONFIG_BASE64) {
                            [IO.File]::WriteAllBytes((Join-Path $frontend 'web.config'), [Convert]::FromBase64String($env:WEB_CONFIG_BASE64))
                        }
                        & icacls.exe $frontend /grant 'IIS_IUSRS:(OI)(CI)(RX)' /T /C /Q | Out-Null
                        if ($LASTEXITCODE -ne 0) { throw "IIS directory permission update failed: $LASTEXITCODE" }
                        $sitePath = "IIS:/Sites/$($env:IIS_SITE_NAME)"
                        if ($manuallyStopped) {
                            Set-ItemProperty $sitePath -Name serverAutoStart -Value $false
                            Stop-WebSite -Name $env:IIS_SITE_NAME -ErrorAction SilentlyContinue
                            Write-Host 'IIS site remains manually stopped.'
                        } else {
                            Set-ItemProperty $sitePath -Name serverAutoStart -Value $true
                            Start-WebSite -Name $env:IIS_SITE_NAME
                            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($env:FRONTEND_PORT)/" -TimeoutSec 20
                            if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) { throw "IIS site verification failed: HTTP $($response.StatusCode)" }
                        }
                    """;
        }
        return """
                                    $ErrorActionPreference = 'Stop'
                                    . ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:ARTIFACT_HELPERS_BASE64))))
                                    function Sync-Folder($source, $target) {
                                        New-Item -ItemType Directory -Path $target -Force | Out-Null
                                        & robocopy.exe $source $target /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS
                                        $copyCode = $LASTEXITCODE
                                        $global:LASTEXITCODE = 0
                                        if ($copyCode -gt 7) { throw "Robocopy failed: $copyCode" }
                                    }
                                    $frontend = Join-Path $env:PROJECT_ROOT 'frontend'
                                    $staging = Join-Path $env:PROJECT_ROOT 'staging/frontend'
                                    $backup = Join-Path $env:PROJECT_ROOT ("backups/frontend/" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
                                    if (Test-Path $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
                                    New-Item -ItemType Directory -Path $staging -Force | Out-Null
                                    Assert-Package "$env:WORKSPACE/artifacts/frontend.zip" @('index.html') $staging
                                    $frontendExisted = Test-Path -LiteralPath $frontend -PathType Container
                                    $backupReady = $false
                                    $frontendModified = $false
                                    $siteStopped = $false
                                    try {
                                        if ($frontendExisted) {
                                            Sync-Folder $frontend $backup
                                            $backupReady = $true
                                        }
                                        %s
                                        $frontendModified = $true
                                        Sync-Folder $staging $frontend
                                        %s
                                        if (-not (Test-Path (Join-Path $frontend 'index.html') -PathType Leaf)) { throw 'Deployed frontend index.html is missing.' }
                                    } catch {
                                        $deploymentError = $_
                                        try {
                                            %s
                                            if ($frontendModified -and $backupReady) {
                                                Sync-Folder $backup $frontend
                                            } elseif ($frontendModified -and -not $frontendExisted -and (Test-Path -LiteralPath $frontend)) {
                                                Remove-Item -LiteralPath $frontend -Recurse -Force
                                            }
                                            %s
                                        } catch {
                                            throw "Frontend deployment failed: $($deploymentError.Exception.Message). Rollback failed: $($_.Exception.Message)"
                                        }
                                        throw $deploymentError
                                    }
                """.formatted(before, after, stopForRollback, restoreSite);
    }

    private static String iisWebConfigBase64(ProjectProfile project, DeploymentSettings settings) {
        if (settings.frontendDeploymentMode() != FrontendDeploymentMode.IIS) {
            return "";
        }
        String proxyRule = "";
        if (project.hasBackend()) {
            String proxyPath = project.deploymentHints().proxyPath();
            String prefix = proxyPath == null || proxyPath.isBlank() ? "api" : proxyPath.replaceFirst("^/+", "");
            proxyRule = """
                              <rule name="JDA API Reverse Proxy" stopProcessing="true">
                                <match url="^%s/(.*)" />
                                <action type="Rewrite" url="http://127.0.0.1:%d/{R:0}" appendQueryString="true" />
                              </rule>
                    """.formatted(xml(prefix), settings.backendPort());
        }
        String webConfig = """
                <?xml version="1.0" encoding="UTF-8"?>
                <configuration>
                  <system.webServer>
                    <rewrite>
                      <rules>
                %s
                        <rule name="JDA SPA Fallback" stopProcessing="true">
                          <match url=".*" />
                          <conditions logicalGrouping="MatchAll">
                            <add input="{REQUEST_FILENAME}" matchType="IsFile" negate="true" />
                            <add input="{REQUEST_FILENAME}" matchType="IsDirectory" negate="true" />
                          </conditions>
                          <action type="Rewrite" url="/index.html" />
                        </rule>
                      </rules>
                    </rewrite>
                  </system.webServer>
                </configuration>
                """.formatted(proxyRule);
        return Base64.getEncoder().encodeToString(webConfig.getBytes(StandardCharsets.UTF_8));
    }

    private static String iisApplicationPool(String siteName) {
        String safe = siteName == null ? "site" : siteName.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isBlank()) {
            safe = "site";
        }
        return "Jda_" + safe;
    }

    private static String selectedPipeline(String customPipeline, String builtIn, String label) {
        if (customPipeline == null || customPipeline.isBlank()) {
            return builtIn;
        }
        String value = customPipeline.strip();
        if (value.length() > 200_000) {
            throw new IllegalArgumentException(label + "自定义 Pipeline 不能超过 200 KB。");
        }
        if (!value.contains("pipeline") || !value.contains("{")) {
            throw new IllegalArgumentException(label + "自定义 Pipeline 必须包含完整的 pipeline { ... }。" );
        }
        return value;
    }

    private static String backendServiceConfigBase64(DeploymentSettings settings) {
        String xml = """
                <service>
                  <id>%s</id>
                  <name>项目后端服务（%s）</name>
                  <description>Jenkins一键部署助手自动管理的 Spring Boot 服务</description>
                  <executable>%s</executable>
                  <arguments>-jar &quot;%%BASE%%\\..\\app.jar&quot; --spring.profiles.active=prod --spring.config.additional-location=&quot;file:%%BASE%%\\..\\config\\application-prod.yml&quot; --server.port=%d</arguments>
                  <workingdirectory>%%BASE%%\\..</workingdirectory>
                  <startmode>Automatic</startmode>
                  <onfailure action="restart" delay="10 sec"/>
                  <stoptimeout>90 sec</stoptimeout>
                  <logpath>%%BASE%%\\logs</logpath>
                  <log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>10</keepFiles></log>
                </service>
                """.formatted(
                xml(settings.backendServiceId()),
                xml(settings.gerritProject()),
                xml(settings.backendJavaExecutable().toString()),
                settings.backendPort());
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String backendVerificationScript(DeploymentSettings settings) {
        if (settings.backendHealthPath() == null || settings.backendHealthPath().isBlank()) {
            return """
                                    $escapedServiceId = $env:SERVICE_ID.Replace("'", "''")
                                    $registered = Get-CimInstance Win32_Service -Filter "Name='$escapedServiceId'" -ErrorAction SilentlyContinue
                                    if ($registered -and $registered.StartMode -eq 'Disabled') {
                                        Write-Host 'Backend verification skipped because the service is manually stopped.'
                                        return
                                    }
                                    $service = Get-Service -Name $env:SERVICE_ID -ErrorAction Stop
                                    if ($service.Status -ne 'Running') { throw "Backend service is not running: $($env:SERVICE_ID)" }
                    """;
        }
        return """
                                    $ErrorActionPreference = 'Stop'
                                    $escapedServiceId = $env:SERVICE_ID.Replace("'", "''")
                                    $registered = Get-CimInstance Win32_Service -Filter "Name='$escapedServiceId'" -ErrorAction SilentlyContinue
                                    if ($registered -and $registered.StartMode -eq 'Disabled') {
                                        Write-Host 'Backend verification skipped because the service is manually stopped.'
                                        return
                                    }
                                    $healthUrl = "http://127.0.0.1:$($env:BACKEND_PORT)$($env:BACKEND_HEALTH_PATH)"
                                    $lastError = $null
                                    for ($attempt = 1; $attempt -le 12; $attempt++) {
                                        try {
                                            $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 10
                                            if ($response.StatusCode -eq 200) { return }
                                        } catch { $lastError = $_ }
                                        Start-Sleep -Seconds 5
                                    }
                                    throw "Backend health check failed: $lastError"
                """;
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空。");
        }
    }

    private static String watchedPath(String relativeDirectory) {
        return relativeDirectory.isBlank() ? "**" : relativeDirectory + "/**";
    }

    private static String groovy(String value) {
        return value.replace("\\", "/").replace("'", "\\'");
    }

    private static String slash(String value) {
        return value.replace('\\', '/');
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
