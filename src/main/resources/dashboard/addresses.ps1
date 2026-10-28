# Read-only address helpers. These describe configuration, not a connectivity probe.
function Get-LocalAddressHosts {
    $hosts = @('127.0.0.1')
    try { $adapters = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() } catch { return $hosts }
    foreach ($adapter in $adapters) {
        if ($adapter.OperationalStatus -ne 'Up' -or $adapter.NetworkInterfaceType -eq 'Loopback') { continue }
        try { $addresses = $adapter.GetIPProperties().UnicastAddresses } catch { continue }
        foreach ($address in $addresses) {
            $ip = $address.Address
            if ($ip.AddressFamily -eq 'InterNetwork' -and -not $ip.ToString().StartsWith('169.254.')) {
                $hosts += $ip.ToString()
            }
        }
    }
    $hosts | Select-Object -Unique
}

function Get-AddressUrls([string]$Protocol, [string]$BindingHost, [int]$Port) {
    if ($Protocol -notin @('http', 'https') -or $Port -lt 1 -or $Port -gt 65535) { return }
    $targets = if ($BindingHost -in @('', '*', '+', '0.0.0.0')) { @(Get-LocalAddressHosts) }
        elseif ($BindingHost -in @('::', '[::]')) { @('::1') }
        else { @($BindingHost.Trim('[', ']')) }
    foreach ($target in $targets) {
        try {
            $builder = [UriBuilder]::new($Protocol, $target, $Port)
            $builder.Host = $builder.Uri.IdnHost
            $builder.Uri.AbsoluteUri
        } catch { }
    }
}

function Get-IisAddresses($Site) {
    foreach ($binding in $Site.Bindings) {
        if ($binding.Protocol -notin @('http', 'https')) { continue }
        $bindingHost = if ($binding.Host) { $binding.Host } else { $binding.EndPoint.Address.IPAddressToString }
        Get-AddressUrls $binding.Protocol $bindingHost $binding.EndPoint.Port
    }
}

function Get-BackendAddresses([string]$WrapperPath, [string]$ServiceId) {
    $reader = $null
    try {
        $xmlPath = [IO.Path]::ChangeExtension($WrapperPath, '.xml')
        if (-not (Test-Path -LiteralPath $xmlPath -PathType Leaf) -or (Get-Item -LiteralPath $xmlPath).Length -gt 1MB) { return }
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($xmlPath, $settings)
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
        if ($document.DocumentElement.Name -ne 'service' -or $document.service.id -ne $ServiceId) { return }
        $urls = @($document.SelectNodes('/service/env') | Where-Object { $_.GetAttribute('name') -eq 'ASPNETCORE_URLS' })
        if ($urls.Count -gt 0) {
            foreach ($url in $urls[0].GetAttribute('value').Split(';')) {
                if ($url.Trim() -match '^(https?)://(\[[^\]]+\]|[^/:@?#\s]+)(?::([0-9]+))?/?$') {
                    $protocol = $Matches[1]
                    $bindingHost = $Matches[2]
                    $port = if ($Matches[3]) { [int]$Matches[3] } elseif ($protocol -eq 'https') { 443 } else { 80 }
                    Get-AddressUrls $protocol $bindingHost $port
                }
            }
            return
        }
        $arguments = [string]$document.service.arguments
        if ($arguments -match '(?:^|\s)--server[.]port(?:=|\s+)([0-9]{1,5})(?=\s|$)') {
            $port = [int]$Matches[1]
            $bindingHost = '*'
            if ($arguments -match '(?:^|\s)--server[.]address(?:=|\s+)([^\s"'']+)(?=\s|$)') { $bindingHost = $Matches[1] }
            Get-AddressUrls 'http' $bindingHost $port
        }
    } catch {
        # Missing, locked or unrecognized configuration must not hide the service or leak XML contents.
    } finally {
        if ($reader) { $reader.Dispose() }
    }
}
