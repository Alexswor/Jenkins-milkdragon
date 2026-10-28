package cn.localops.jda.service;

import cn.localops.jda.model.DeploymentSettings;
import cn.localops.jda.model.ProjectProfile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class DotNetPipelineTemplate {
    String create(ProjectProfile project, DeploymentSettings settings) {
        String relative = project.relativeBackendPath();
        String sourceDirectory = relative.isBlank() ? "source" : "source/" + relative;

        return """
                pipeline {
                    agent { label '%s' }
                    options {
                        timestamps()
                        timeout(time: 30, unit: 'MINUTES')
                        disableConcurrentBuilds()
                        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
                    }
                    environment {
                        REPO_URL = '%s'
                        SOURCE_DIR = 'source'
                        APP_DIR = '%s'
                        PROJECT_ROOT = '%s'
                        SERVICE_ID = '%s'
                        WINSW_SOURCE = '%s'
                        BACKEND_PORT = '%d'
                        BACKEND_HEALTH_PATH = '%s'
                        DOTNET_NOLOGO = '1'
                        DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
                        DOTNET_CLI_TELEMETRY_OPTOUT = '1'
                        SERVICE_XML_TEMPLATE_BASE64 = '%s'
                        ARTIFACT_KIND = 'dotnet'
                        ARTIFACT_HELPERS_BASE64 = '%s'
                    }
                    stages {
                        stage('Check Environment') {
                            steps {
                                echo 'JDA_BUILD_ONLY'
                                bat 'git --version'
                                powershell '''
                                    $ErrorActionPreference = 'Stop'
                                    $dotnet = Get-Command dotnet -ErrorAction Stop
                                    $sdks = @(& $dotnet.Source --list-sdks)
                                    if ($LASTEXITCODE -ne 0 -or -not ($sdks | Where-Object { $_ -match '^10[.]' })) {
                                        throw '.NET 10 SDK is required on this Jenkins Agent.'
                                    }
                                    if (-not (Test-Path $env:WINSW_SOURCE -PathType Leaf)) {
                                        throw 'Bundled WinSW is missing from the Agent directory.'
                                    }
                                    Write-Host ".NET executable: $($dotnet.Source)"
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
                        stage('Build And Package') {
                            steps {
                                dir(env.APP_DIR) {
                                    powershell '''
                                        $ErrorActionPreference = 'Stop'
                                        . ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:ARTIFACT_HELPERS_BASE64))))
                                        $dotnetVersion = (& dotnet --version).Trim()
                                        if ($LASTEXITCODE -ne 0 -or [int]$dotnetVersion.Split('.')[0] -ne 10) {
                                            throw ".NET 10 SDK is required. Current: $dotnetVersion"
                                        }

                                        $projects = @(Get-ChildItem -LiteralPath $PWD -Recurse -Filter '*.csproj' -File | Where-Object {
                                            $_.FullName.Replace([char]92, '/') -notmatch '/(?:bin|obj)/'
                                        })
                                        if ($projects.Count -eq 0) { throw 'No .csproj file was found.' }
                                        $webProjects = @($projects | Where-Object {
                                            (Get-Content -LiteralPath $_.FullName -Raw) -match 'Microsoft[.]NET[.]Sdk[.]Web'
                                        })
                                        if ($webProjects.Count -eq 1) {
                                            $deployProject = $webProjects[0]
                                        } elseif ($projects.Count -eq 1) {
                                            $deployProject = $projects[0]
                                        } else {
                                            $names = ($projects | ForEach-Object FullName) -join ', '
                                            throw "Cannot choose a unique deployable .csproj. Projects: $names"
                                        }

                                        $artifacts = Join-Path $env:WORKSPACE 'artifacts'
                                        $publishDir = Join-Path $artifacts 'dotnet-publish'
                                        $debugDir = Join-Path $artifacts 'msbuild-debug'
                                        $packagePath = Join-Path $artifacts 'backend-dotnet.zip'
                                        New-Item -ItemType Directory -Path $artifacts,$debugDir -Force | Out-Null
                                        $env:MSBUILDDEBUGPATH = $debugDir

                                        $restoreSucceeded = $false
                                        for ($attempt = 1; $attempt -le 2; $attempt++) {
                                            Write-Host "dotnet restore attempt $attempt/2"
                                            dotnet restore $deployProject.FullName --disable-parallel --disable-build-servers
                                            if ($LASTEXITCODE -eq 0) {
                                                $restoreSucceeded = $true
                                                break
                                            }
                                            if ($attempt -lt 2) { Start-Sleep -Seconds 3 }
                                        }
                                        if (-not $restoreSucceeded) { throw 'dotnet restore failed after 2 attempts.' }

                                        $assemblyOutput = @(& dotnet msbuild $deployProject.FullName -nologo -getProperty:AssemblyName)
                                        $assemblyName = [string]($assemblyOutput | Where-Object { $_ -and $_.Trim() } | Select-Object -Last 1)
                                        $assemblyName = $assemblyName.Trim()
                                        if ($LASTEXITCODE -ne 0 -or -not $assemblyName) {
                                            throw 'Unable to resolve the deployable assembly name.'
                                        }
                                        $entryPoint = "$assemblyName.dll"

                                        dotnet publish $deployProject.FullName --configuration Release --no-restore --no-self-contained --output $publishDir
                                        if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }
                                        foreach ($excluded in @('appsettings.Production.json', 'upload', 'uploads', 'logs')) {
                                            $excludedPath = Join-Path $publishDir $excluded
                                            if (Test-Path -LiteralPath $excludedPath) { Remove-Item -LiteralPath $excludedPath -Recurse -Force }
                                        }
                                        if (-not (Test-Path (Join-Path $publishDir $entryPoint) -PathType Leaf)) {
                                            throw "Published entry DLL is missing: $entryPoint"
                                        }
                                        [IO.File]::WriteAllText(
                                            (Join-Path $publishDir 'jda-entrypoint.txt'),
                                            $entryPoint,
                                            [Text.UTF8Encoding]::new($false))
                                        New-VerifiedPackage $publishDir $packagePath @($entryPoint, "$assemblyName.deps.json", "$assemblyName.runtimeconfig.json", 'jda-entrypoint.txt')
                                        $global:LASTEXITCODE = 0
                                    '''
                                }
                            }
                        }
                        stage('Archive Package') {
                            steps {
                                archiveArtifacts artifacts: 'artifacts/backend-dotnet.zip', fingerprint: true
                            }
                        }
                        stage('Deploy Backend') {
                            steps {
                                echo 'JDA_DEPLOYMENT_STARTED'
                                powershell '''
                                    $ErrorActionPreference = 'Stop'
                                    . ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:ARTIFACT_HELPERS_BASE64))))
                                    function Sync-Folder($source, $target) {
                                        New-Item -ItemType Directory -Path $target -Force | Out-Null
                                        & robocopy.exe $source $target /MIR /XD upload uploads logs /R:2 /W:2 /XJ /NFL /NDL /NJH /NJS /NP
                                        $copyCode = $LASTEXITCODE
                                        $global:LASTEXITCODE = 0
                                        if ($copyCode -ge 8) { throw "Robocopy failed: $copyCode" }
                                    }
                                    function Escape-Xml($value) {
                                        return [Security.SecurityElement]::Escape([string]$value)
                                    }
                                    function Wait-ServiceReady {
                                        $service = Get-Service -Name $env:SERVICE_ID -ErrorAction Stop
                                        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromSeconds(90))
                                        $healthUrl = "http://127.0.0.1:$($env:BACKEND_PORT)$($env:BACKEND_HEALTH_PATH)"
                                        $readyChecks = 0
                                        for ($attempt = 1; $attempt -le 12; $attempt++) {
                                            $service = Get-Service -Name $env:SERVICE_ID -ErrorAction Stop
                                            $ready = $service.Status -eq 'Running'
                                            if ($ready) {
                                                $client = [Net.Sockets.TcpClient]::new()
                                                try {
                                                    $connection = $client.ConnectAsync('127.0.0.1', [int]$env:BACKEND_PORT)
                                                    $ready = $connection.Wait(2000) -and $client.Connected
                                                    if ($ready -and $env:BACKEND_HEALTH_PATH) {
                                                        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 5
                                                        $ready = $response.StatusCode -eq 200
                                                    }
                                                } catch { $ready = $false }
                                                finally { $client.Dispose() }
                                            }
                                            if ($ready) {
                                                $readyChecks++
                                                if ($readyChecks -ge 3) { return }
                                            } else { $readyChecks = 0 }
                                            if ($attempt -lt 12) { Start-Sleep -Seconds 5 }
                                        }
                                        throw "Backend did not stay ready on port $($env:BACKEND_PORT). Check backend/service/logs."
                                    }

                                    $backend = Join-Path $env:PROJECT_ROOT 'backend'
                                    $app = Join-Path $backend 'app'
                                    $config = Join-Path $backend 'config'
                                    $serviceDirectory = Join-Path $backend 'service'
                                    $staging = Join-Path $env:PROJECT_ROOT 'staging/backend'
                                    $backup = Join-Path $env:PROJECT_ROOT ("backups/backend/" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
                                    $backupApp = Join-Path $backup 'app'
                                    New-Item -ItemType Directory -Path $backend,$config,$serviceDirectory -Force | Out-Null
                                    if (Test-Path $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
                                    New-Item -ItemType Directory -Path $staging -Force | Out-Null
                                    Assert-Package "$env:WORKSPACE/artifacts/backend-dotnet.zip" @('jda-entrypoint.txt') $staging

                                    $entryPointFile = Join-Path $staging 'jda-entrypoint.txt'
                                    if (-not (Test-Path $entryPointFile -PathType Leaf)) { throw 'Backend package entrypoint manifest is missing.' }
                                    $entryPoint = [IO.File]::ReadAllText($entryPointFile).Trim()
                                    if ($entryPoint -notmatch '^[A-Za-z0-9._-]+[.]dll$') { throw 'Backend package entrypoint is invalid.' }
                                    if (-not (Test-Path (Join-Path $staging $entryPoint) -PathType Leaf)) { throw "Backend package is missing $entryPoint" }
                                    foreach ($extension in @('.deps.json', '.runtimeconfig.json')) {
                                        $required = Join-Path $staging ([IO.Path]::GetFileNameWithoutExtension($entryPoint) + $extension)
                                        if (-not (Test-Path -LiteralPath $required -PathType Leaf) -or (Get-Item -LiteralPath $required).Length -eq 0) {
                                            throw "Backend runtime file is missing or empty: $required"
                                        }
                                    }
                                    Remove-Item -LiteralPath $entryPointFile -Force

                                    $dotnetExe = (Get-Command dotnet -ErrorAction Stop).Source
                                    $wrapper = Join-Path $serviceDirectory ($env:SERVICE_ID + '.exe')
                                    $serviceXml = Join-Path $serviceDirectory ($env:SERVICE_ID + '.xml')
                                    $escapedServiceId = $env:SERVICE_ID.Replace("'", "''")
                                    $registered = Get-CimInstance Win32_Service -Filter "Name='$escapedServiceId'" -ErrorAction SilentlyContinue
                                    if ($registered -and $registered.PathName -notlike "*$wrapper*") {
                                        throw "Service ID is already used by another executable: $($env:SERVICE_ID)"
                                    }
                                    $manuallyDisabled = $registered -and $registered.StartMode -eq 'Disabled'
                                    $service = Get-Service -Name $env:SERVICE_ID -ErrorAction SilentlyContinue
                                    $serviceExisted = [bool]$service
                                    $serviceStopped = $false
                                    $serviceInstalled = $false
                                    $backupReady = $false
                                    $appExisted = Test-Path -LiteralPath $app -PathType Container
                                    $appModified = $false
                                    $previousServiceXml = if (Test-Path -LiteralPath $serviceXml -PathType Leaf) { [IO.File]::ReadAllBytes($serviceXml) } else { $null }
                                    $serviceXmlModified = $false

                                    try {
                                        if ($service -and $service.Status -ne 'Stopped') {
                                            Stop-Service -Name $service.Name -ErrorAction Stop
                                            $serviceStopped = $true
                                            $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(90))
                                        }
                                        if ($appExisted) {
                                            New-Item -ItemType Directory -Path $backupApp -Force | Out-Null
                                            Sync-Folder $app $backupApp
                                            $backupReady = $true
                                        }
                                        $appModified = $true
                                        Sync-Folder $staging $app
                                        foreach ($runtimeDirectory in @('upload', 'uploads', 'logs')) {
                                            [IO.Directory]::CreateDirectory((Join-Path $app $runtimeDirectory)) | Out-Null
                                        }
                                        $externalConfig = Join-Path $config 'appsettings.Production.json'
                                        if (Test-Path $externalConfig -PathType Leaf) {
                                            Copy-Item $externalConfig (Join-Path $app 'appsettings.Production.json') -Force
                                        }

                                        if (-not (Test-Path $env:WINSW_SOURCE -PathType Leaf)) { throw 'Bundled WinSW is missing from the Agent directory.' }
                                        if (-not (Test-Path $wrapper -PathType Leaf)) { Copy-Item $env:WINSW_SOURCE $wrapper -Force }
                                        $template = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:SERVICE_XML_TEMPLATE_BASE64))
                                        $definition = $template.Replace('__DOTNET_EXE__', (Escape-Xml $dotnetExe)).Replace('__ENTRYPOINT__', (Escape-Xml $entryPoint))
                                        $serviceXmlModified = $true
                                        [IO.File]::WriteAllText($serviceXml, $definition, [Text.UTF8Encoding]::new($false))

                                        $serviceCommand = if ($serviceExisted) { 'refresh' } else { 'install' }
                                        & $wrapper $serviceCommand
                                        $serviceExitCode = $LASTEXITCODE
                                        $serviceInstalled = -not $serviceExisted -and [bool](Get-Service -Name $env:SERVICE_ID -ErrorAction SilentlyContinue)
                                        if ($serviceExitCode -ne 0) { throw "WinSW $serviceCommand failed: $serviceExitCode" }
                                        if ($manuallyDisabled) {
                                            Set-Service -Name $env:SERVICE_ID -StartupType Disabled
                                            Write-Host 'Backend service remains manually stopped.'
                                        } else {
                                            Set-Service -Name $env:SERVICE_ID -StartupType Automatic
                                            & $wrapper start
                                            if ($LASTEXITCODE -ne 0) { throw "WinSW start failed: $LASTEXITCODE" }
                                            Wait-ServiceReady
                                        }
                                    } catch {
                                        $deploymentError = $_
                                        try {
                                        $latest = Get-Service -Name $env:SERVICE_ID -ErrorAction SilentlyContinue
                                        if (($appModified -or $serviceStopped) -and $latest -and $latest.Status -ne 'Stopped') {
                                            Stop-Service -Name $latest.Name -ErrorAction Stop
                                            $latest.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(90))
                                        }
                                        if ($appModified -and $backupReady) {
                                            Sync-Folder $backupApp $app
                                        } elseif ($appModified -and -not $appExisted -and (Test-Path -LiteralPath $app)) {
                                            Remove-Item -LiteralPath $app -Recurse -Force
                                        }
                                        if ($serviceInstalled) {
                                            & $wrapper uninstall | Out-Null
                                            if ($LASTEXITCODE -ne 0) { throw "WinSW uninstall failed: $LASTEXITCODE" }
                                            $global:LASTEXITCODE = 0
                                        }
                                        if ($serviceXmlModified) {
                                            if ($null -ne $previousServiceXml) {
                                                [IO.File]::WriteAllBytes($serviceXml, $previousServiceXml)
                                                if ($serviceExisted) {
                                                    & $wrapper refresh | Out-Null
                                                    if ($LASTEXITCODE -ne 0) { throw "WinSW rollback refresh failed: $LASTEXITCODE" }
                                                }
                                            } elseif (Test-Path -LiteralPath $serviceXml) { Remove-Item -LiteralPath $serviceXml -Force }
                                        }
                                        if ($manuallyDisabled -and $latest -and -not $serviceInstalled) {
                                            Set-Service -Name $latest.Name -StartupType Disabled -ErrorAction Stop
                                        } elseif ($serviceStopped -and $latest) {
                                            Start-Service -Name $latest.Name -ErrorAction Stop
                                            Wait-ServiceReady
                                        }
                                        } catch { throw "Deployment failed: $($deploymentError.Exception.Message). Rollback failed: $($_.Exception.Message)" }
                                        throw $deploymentError
                                    }
                                '''
                            }
                        }
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'artifacts/msbuild-debug/**', allowEmptyArchive: true
                        }
                        success {
                            echo '.NET backend was built, deployed and verified successfully.'
                        }
                        failure {
                            echo '.NET backend pipeline failed. Check the first failed stage and archived MSBuild logs.'
                        }
                    }
                }
                """.formatted(
                groovy(settings.agentLabel()),
                groovy(settings.repositoryUrl()),
                groovy(sourceDirectory),
                groovy(slash(settings.productionRoot().toString())),
                groovy(settings.backendServiceId()),
                groovy(slash(settings.backendWinSwSource().toString())),
                settings.backendPort(),
                groovy(settings.backendHealthPath()),
                serviceXmlTemplateBase64(settings),
                PipelineArtifactScripts.base64(),
                groovy(settings.triggerSettings().manualCheckoutRef()));
    }

    private static String serviceXmlTemplateBase64(DeploymentSettings settings) {
        String xml = """
                <service>
                  <id>%s</id>
                  <name>项目后端服务（%s）</name>
                  <description>Jenkins一键部署助手自动管理的 .NET 10 服务</description>
                  <executable>__DOTNET_EXE__</executable>
                  <arguments>&quot;%%BASE%%\\..\\app\\__ENTRYPOINT__&quot;</arguments>
                  <workingdirectory>%%BASE%%\\..\\app</workingdirectory>
                  <env name="DOTNET_ENVIRONMENT" value="Production" />
                  <env name="ASPNETCORE_ENVIRONMENT" value="Production" />
                  <env name="ASPNETCORE_URLS" value="http://127.0.0.1:%d" />
                  <startmode>Automatic</startmode>
                  <onfailure action="restart" delay="10 sec"/>
                  <stoptimeout>90 sec</stoptimeout>
                  <logpath>%%BASE%%\\logs</logpath>
                  <log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>10</keepFiles></log>
                </service>
                """.formatted(
                xml(settings.backendServiceId()),
                xml(settings.gerritProject()),
                settings.backendPort());
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
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
