param(
    [ValidateSet('app-image', 'exe')]
    [string]$PackageType = 'app-image'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$targetDirectory = Join-Path $projectRoot 'target'
$inputDirectory = Join-Path $targetDirectory 'app-input'
$distDirectory = Join-Path $projectRoot 'dist'
$productName = 'Jenkins' + (-join [char[]](0x4E00, 0x952E, 0x90E8, 0x7F72, 0x52A9, 0x624B))
$runtimeLauncherName = 'JenkinsDeployAssistant'
$applicationVersion = '0.10.8'
$distributionName = "$productName-$applicationVersion-windows-amd64"
$jpackageOutput = Join-Path $distDirectory $runtimeLauncherName
$applicationOutput = Join-Path $distDirectory $distributionName
$archiveOutput = Join-Path $distDirectory "$distributionName.zip"
$iconFile = Join-Path $projectRoot 'src\main\resources\icons\app-icon.ico'
$bootstrapSource = Join-Path $projectRoot 'packaging\LauncherBootstrap.cs'
$diagnosticsReadme = Join-Path $projectRoot 'packaging\diagnostics-README.txt'
$winSwSource = Join-Path $projectRoot 'packaging\winsw\WinSW-x64.exe'
$iisSupportSource = Join-Path $projectRoot 'packaging\iis'
$winSwExpectedSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$iisPackages = @(
    @{ Path = (Join-Path $iisSupportSource 'rewrite_amd64_zh-CN.msi'); Sha256 = 'A25FADB796EAB91B70C77294857EA1D3BC225B3B14F4EC3A7984184F2106C935' },
    @{ Path = (Join-Path $iisSupportSource 'requestRouter_amd64.msi'); Sha256 = 'FB61FDB7101795A34D5129CB37EEE43AB675C7ED76BA3A3B23B039D8C90C2A4B' }
)

function Assert-ChildPath([string]$Path, [string]$Parent) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the project: $fullPath"
    }
}

Set-Location $projectRoot
& mvn clean package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
$actualWinSwSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $winSwSource).Hash
if ($actualWinSwSha256 -ne $winSwExpectedSha256) {
    throw "Bundled WinSW checksum mismatch: $actualWinSwSha256"
}
foreach ($package in $iisPackages) {
    if (-not (Test-Path -LiteralPath $package.Path -PathType Leaf)) {
        throw "Bundled IIS package is missing: $($package.Path)"
    }
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $package.Path).Hash
    if ($actualSha256 -ne $package.Sha256) {
        throw "Bundled IIS package checksum mismatch: $($package.Path)"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $package.Path
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch '(^|,\s*)O=Microsoft Corporation(,|$)') {
        throw "Bundled IIS package is not signed by Microsoft: $($package.Path)"
    }
}
$winSwInputDirectory = Join-Path $inputDirectory 'winsw'
New-Item -ItemType Directory -Path $winSwInputDirectory -Force | Out-Null
Copy-Item -LiteralPath $winSwSource -Destination (Join-Path $winSwInputDirectory 'WinSW-x64.exe') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\winsw\README.txt') -Destination $winSwInputDirectory -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\winsw\LICENSE.txt') -Destination $winSwInputDirectory -Force
$iisInputDirectory = Join-Path $inputDirectory 'iis'
New-Item -ItemType Directory -Path $iisInputDirectory -Force | Out-Null
Copy-Item -Path (Join-Path $iisSupportSource '*') -Destination $iisInputDirectory -Recurse -Force
$agentRuntime = Join-Path $inputDirectory 'agent-runtime'
& jlink '--add-modules' 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.zipfs' `
    '--output' $agentRuntime '--strip-debug' '--no-man-pages' '--no-header-files' '--compress=zip-6'
if ($LASTEXITCODE -ne 0) { throw 'Agent Java runtime creation failed.' }

& mvn dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$inputDirectory"
if ($LASTEXITCODE -ne 0) { throw 'Dependency collection failed.' }
Copy-Item (Join-Path $targetDirectory "jenkins-deploy-assistant-$applicationVersion-SNAPSHOT.jar") `
    (Join-Path $inputDirectory 'jenkins-deploy-assistant.jar') -Force

New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null
if ($PackageType -eq 'app-image') {
    foreach ($generatedPath in @($jpackageOutput, $applicationOutput)) {
        if (Test-Path -LiteralPath $generatedPath) {
            Assert-ChildPath $generatedPath $distDirectory
            Remove-Item -LiteralPath $generatedPath -Recurse -Force
        }
    }
}

$arguments = @(
    '--type', $PackageType,
    '--name', $runtimeLauncherName,
    '--app-version', $applicationVersion,
    '--vendor', 'LocalOps',
    '--description', 'Windows Jenkins Agent and deployment assistant',
    '--input', $inputDirectory,
    '--dest', $distDirectory,
    '--main-jar', 'jenkins-deploy-assistant.jar',
    '--main-class', 'cn.localops.jda.Launcher',
    '--icon', $iconFile,
    '--add-modules', 'java.base,java.desktop,java.logging,java.net.http,java.scripting,java.xml,jdk.crypto.ec,jdk.unsupported',
    '--java-options', '-Dfile.encoding=UTF-8'
)

if ($PackageType -eq 'exe') {
    $arguments += @('--win-menu', '--win-shortcut', '--win-dir-chooser')
}

& jpackage @arguments
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

if ($PackageType -eq 'app-image') {
    New-Item -ItemType Directory -Path $applicationOutput -Force | Out-Null
    $diagnosticsDirectory = Join-Path $applicationOutput 'diagnostics'
    New-Item -ItemType Directory -Path $diagnosticsDirectory -Force | Out-Null
    Copy-Item -LiteralPath $diagnosticsReadme -Destination (Join-Path $diagnosticsDirectory 'README.txt') -Force
    $runtimeDirectory = Join-Path $applicationOutput 'resources'
    Move-Item -LiteralPath $jpackageOutput -Destination $runtimeDirectory
    Move-Item -LiteralPath (Join-Path $runtimeDirectory "$runtimeLauncherName.exe") `
        -Destination (Join-Path $runtimeDirectory 'runtime.exe')
    Move-Item -LiteralPath (Join-Path $runtimeDirectory "app\$runtimeLauncherName.cfg") `
        -Destination (Join-Path $runtimeDirectory 'app\runtime.cfg')
    $bootstrapOutput = Join-Path $applicationOutput "$productName.exe"
    $csharpCompiler = Join-Path ([System.Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()) 'csc.exe'
    if (-not (Test-Path -LiteralPath $csharpCompiler)) {
        throw 'The .NET Framework C# compiler required for the friendly launcher was not found.'
    }
    & $csharpCompiler '/nologo' '/target:winexe' '/reference:System.Windows.Forms.dll' `
        "/win32icon:$iconFile" "/out:$bootstrapOutput" $bootstrapSource
    if ($LASTEXITCODE -ne 0) { throw 'Friendly launcher creation failed.' }
    $runtimeModulesFile = Join-Path $runtimeDirectory 'runtime\lib\modules'
    $runtimeModules = & jimage list $runtimeModulesFile
    if ($LASTEXITCODE -ne 0 -or -not ($runtimeModules -match '^Module: jdk\.unsupported$')) {
        throw 'Packaged runtime is missing the JavaFX-required jdk.unsupported module.'
    }
    Compress-Archive -LiteralPath $applicationOutput -DestinationPath $archiveOutput -CompressionLevel Optimal -Force
    Write-Host "Application image created: $applicationOutput\$productName.exe"
    Write-Host "Versioned archive created: $archiveOutput"
} else {
    Write-Host "Installer created in: $distDirectory"
}
