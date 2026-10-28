Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Get-StreamHash($stream) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-FileChecksum($path) {
    $stream = [IO.File]::OpenRead($path)
    try { return Get-StreamHash $stream } finally { $stream.Dispose() }
}

function Get-ArchiveIndex($archive) {
    $index = @{}
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ($name.StartsWith('/') -or $name.Contains(':') -or $name.Split('/') -contains '..') {
            throw "Unsafe archive entry: $name"
        }
        if ($name.EndsWith('/')) { continue }
        if (-not $name -or $index.ContainsKey($name)) { throw "Duplicate or empty archive entry: $name" }
        $index[$name] = $entry
    }
    return $index
}

function Read-ArchiveText($entry) {
    $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Assert-Package($package, [string[]]$requiredFiles, $destination = '') {
    $archive = [IO.Compression.ZipFile]::OpenRead($package)
    try {
        $index = Get-ArchiveIndex $archive
        if (-not $index.ContainsKey('.jda-package.json')) { throw 'Package manifest is missing.' }
        $manifest = (Read-ArchiveText $index['.jda-package.json']) | ConvertFrom-Json
        if ($manifest.version -ne 1 -or $manifest.repository -cne $env:REPO_URL -or
                $manifest.kind -cne $env:ARTIFACT_KIND -or $manifest.build -cne $env:BUILD_TAG) {
            throw 'Package project or build does not match this deployment.'
        }
        $files = @($manifest.files)
        if ($files.Count -eq 0 -or $index.Count -ne $files.Count + 1) { throw 'Package file count mismatch.' }
        $seen = @{}
        foreach ($file in $files) {
            if (-not $file.path -or $file.path -eq '.jda-package.json' -or $seen.ContainsKey($file.path) -or
                    -not $index.ContainsKey($file.path)) { throw "Package file is missing or duplicated: $($file.path)" }
            $seen[$file.path] = $true
            $entry = $index[$file.path]
            $stream = $entry.Open()
            try { $hash = Get-StreamHash $stream } finally { $stream.Dispose() }
            if ($entry.Length -ne $file.length -or $hash -cne $file.sha256) {
                throw "Package file checksum mismatch: $($file.path)"
            }
        }
        foreach ($required in $requiredFiles) {
            if (-not $seen.ContainsKey($required) -or $index[$required].Length -eq 0) {
                throw "Required package file is missing or empty: $required"
            }
        }
        if ($destination) {
            # Validate and extract through the same open archive, before touching production files.
            [IO.Compression.ZipFileExtensions]::ExtractToDirectory($archive, $destination)
            Remove-Item -LiteralPath (Join-Path $destination '.jda-package.json') -Force
        }
    } finally { $archive.Dispose() }
}

function New-VerifiedPackage($source, $package, [string[]]$requiredFiles) {
    $root = (Resolve-Path -LiteralPath $source).Path.TrimEnd('\', '/')
    $manifestPath = Join-Path $root '.jda-package.json'
    if (Test-Path -LiteralPath $manifestPath) { throw 'Source contains the reserved .jda-package.json filename.' }
    $partial = $package + '.partial'
    # Only packaging is retried. A partial archive is never passed to deployment.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Write-Host "Packaging attempt $attempt/3"
        try {
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
            $items = @(Get-ChildItem -LiteralPath $root -Recurse -Force)
            if ($items | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
                throw 'Package source contains a link or junction.'
            }
            $files = @($items | Where-Object { -not $_.PSIsContainer } | Sort-Object FullName | ForEach-Object {
                [ordered]@{
                    path = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
                    length = $_.Length
                    sha256 = Get-FileChecksum $_.FullName
                }
            })
            $manifest = [ordered]@{ version = 1; repository = $env:REPO_URL; kind = $env:ARTIFACT_KIND; build = $env:BUILD_TAG; files = $files }
            [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
            [IO.Compression.ZipFile]::CreateFromDirectory($root, $partial, [IO.Compression.CompressionLevel]::Optimal, $false)
            Assert-Package $partial $requiredFiles
            Move-Item -LiteralPath $partial -Destination $package -Force
            return
        } catch {
            if ($attempt -eq 3) { throw }
            Write-Warning "Packaging failed: $($_.Exception.Message). Retrying in 5 seconds."
            Start-Sleep -Seconds 5
        } finally {
            if (Test-Path -LiteralPath $manifestPath) { Remove-Item -LiteralPath $manifestPath -Force }
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        }
    }
}

function Get-DeployableJar($pattern) {
    $jars = @(Get-ChildItem -Path $pattern -File | Where-Object { $_.Name -notmatch '(^original-|-sources[.]jar$|-javadoc[.]jar$|-plain[.]jar$)' })
    if ($jars.Count -ne 1) { throw "Expected one deployable JAR, found $($jars.Count)." }
    return $jars[0]
}

function Assert-Jar($path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        $index = Get-ArchiveIndex $archive
        if (-not $index.ContainsKey('META-INF/MANIFEST.MF')) { throw 'JAR manifest is missing.' }
        $manifest = Read-ArchiveText $index['META-INF/MANIFEST.MF']
        $main = [regex]::Match($manifest, '(?m)^Main-Class:\s*(\S+)')
        if (-not $main.Success -or -not $index.ContainsKey($main.Groups[1].Value.Replace('.', '/') + '.class')) {
            throw 'JAR executable main class is missing.'
        }
        foreach ($entry in $index.Values) {
            $stream = $entry.Open()
            try { Get-StreamHash $stream | Out-Null } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}

function Write-JarReceipt($jar, $receipt) {
    Assert-Jar $jar.FullName
    $record = [ordered]@{
        repository = $env:REPO_URL; kind = $env:ARTIFACT_KIND; build = $env:BUILD_TAG
        filename = $jar.Name; sha256 = Get-FileChecksum $jar.FullName
    }
    [IO.File]::WriteAllText($receipt, ($record | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
}

function Assert-JarReceipt($jar, $receipt) {
    $record = [IO.File]::ReadAllText($receipt, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($record.repository -cne $env:REPO_URL -or $record.kind -cne $env:ARTIFACT_KIND -or
            $record.build -cne $env:BUILD_TAG -or $record.filename -cne $jar.Name -or
            $record.sha256 -cne (Get-FileChecksum $jar.FullName)) {
        throw 'JAR project, build or checksum does not match this deployment.'
    }
    Assert-Jar $jar.FullName
}
